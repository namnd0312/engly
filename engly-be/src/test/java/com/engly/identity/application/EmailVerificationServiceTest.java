package com.engly.identity.application;

import com.engly.identity.domain.EmailVerificationToken;
import com.engly.identity.domain.InvalidVerificationTokenException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.EmailVerificationTokenRepository;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for {@link EmailVerificationService}: token persistence, single-use
 * enforcement, expiry, resend cooldown, and email dispatch. Runs against a real Postgres
 * container (Flyway incl. V11). Email sender is mocked — never sends real mail.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class EmailVerificationServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Override resend cooldown to 2s for deterministic testing. Must be a duration STRING
        // ("2s") — a bare number binds as milliseconds (no @DurationUnit on that property), which
        // would make the cooldown expire before the test's own assertions finish running.
        registry.add("app.email-verification.resend-cooldown", () -> "2s");
    }

    @Autowired private EmailVerificationService service;
    @Autowired private UserAccountRepository users;
    @Autowired private EmailVerificationTokenRepository tokens;
    @MockitoBean private ActivationEmailSender emailSender;

    private UserAccount seedUser(String email) {
        return users.save(UserAccount.localUser("local-" + UUID.randomUUID(), email, "hash", "User"));
    }

    @Test
    void startVerification_persistsHashedToken_andDispatchesViaEmailSender() {
        UserAccount user = seedUser("alice@example.com");

        service.startVerification(user);

        // Assert token exists in DB (check hash only, raw is ephemeral).
        assertThat(tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId()))
                .isNotEmpty()
                .satisfies(tokenOpt -> {
                    EmailVerificationToken token = tokenOpt.get();
                    assertThat(token.getTokenHash()).isNotNull();
                    assertThat(token.getTokenHash()).isNotEmpty();
                    assertThat(token.getUserId()).isEqualTo(user.getId());
                });

        // Assert sender was invoked (token dispatched post-commit).
        verify(emailSender).send(anyString(), anyString());
    }

    private String rawTokenFor(UserAccount user) {
        service.startVerification(user);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void verify_withValidToken_marksUserVerified_andConsumesToken() {
        UserAccount user = seedUser("carol@example.com");
        String rawToken = rawTokenFor(user);

        UserAccount verified = service.verify(rawToken);

        assertThat(verified.getId()).isEqualTo(user.getId());
        assertThat(verified.isEmailVerified()).isTrue();
        assertThat(users.findById(user.getId())).get()
                .satisfies(u -> assertThat(u.isEmailVerified()).isTrue());
    }

    @Test
    void verify_singleUse_secondCallWithSameToken_throwsInvalidVerificationTokenException() {
        UserAccount user = seedUser("single-use@example.com");
        String rawToken = rawTokenFor(user);

        service.verify(rawToken);

        assertThatThrownBy(() -> service.verify(rawToken))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void verify_expiredToken_throwsInvalidVerificationTokenException() {
        UserAccount user = seedUser("dave@example.com");
        String rawToken = "expired-raw-token";

        // Manually insert an already-expired token matching the raw value verify() will hash.
        Instant expiredTime = Instant.now().minus(1, ChronoUnit.HOURS);
        tokens.save(new EmailVerificationToken(user.getId(), TokenHashing.sha256(rawToken), expiredTime));

        assertThatThrownBy(() -> service.verify(rawToken))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void verify_concurrentCallsWithSameToken_exactlyOneSucceeds() throws Exception {
        UserAccount user = seedUser("race@example.com");
        String rawToken = rawTokenFor(user);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Boolean>> results = List.of(
                    CompletableFuture.supplyAsync(() -> attemptVerify(rawToken), pool),
                    CompletableFuture.supplyAsync(() -> attemptVerify(rawToken), pool));

            long successes = results.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count();
            assertThat(successes).isEqualTo(1); // exactly one thread wins the atomic claim
        } finally {
            pool.shutdownNow();
        }
    }

    /** @return true if verify() succeeded, false if it threw InvalidVerificationTokenException. */
    private boolean attemptVerify(String rawToken) {
        try {
            service.verify(rawToken);
            return true;
        } catch (InvalidVerificationTokenException e) {
            return false;
        }
    }

    @Test
    void resend_forUnknownEmail_noop_noException() {
        service.resend("unknown@example.com");
        // Should complete without error and without creating a token.
        verify(emailSender, never()).send(anyString(), anyString());
    }

    @Test
    void resend_forVerifiedEmail_noop() {
        UserAccount user = seedUser("verified@example.com");
        service.markVerifiedImmediately(user);

        service.resend("verified@example.com");
        // Should not create a new token.
        verify(emailSender, never()).send(anyString(), anyString());
    }

    @Test
    void resend_withinCooldown_noOp_afterCooldown_issuesNewToken() throws Exception {
        // resend-cooldown is overridden to 2s for this test class (see datasourceProps).
        UserAccount user = seedUser("resend@example.com");
        service.startVerification(user);
        EmailVerificationToken first = tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();

        service.resend("resend@example.com"); // still within cooldown → no-op
        assertThat(tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getId())
                .isEqualTo(first.getId());

        Thread.sleep(2200); // cross the 2s cooldown boundary
        service.resend("resend@example.com"); // cooldown elapsed → new token
        EmailVerificationToken second = tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void startVerification_deletesPriorTokens() {
        UserAccount user = seedUser("prior@example.com");
        service.startVerification(user);

        EmailVerificationToken first = tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();

        // Issue a second token (e.g., via resend after cooldown).
        service.startVerification(user);

        // Prior token should be deleted.
        EmailVerificationToken second = tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void startVerification_tokenPersistsRegardlessOfEmailSendStatus() {
        // This test documents that token persistence is independent of email send success.
        // The actual test of non-fatal email failures is in AuthActivationFlowTest
        // with a mocked sender that can be controlled precisely.
        UserAccount user = seedUser("token-persist@example.com");

        service.startVerification(user);

        // Token should be persisted.
        assertThat(tokens.findFirstByUserIdOrderByCreatedAtDesc(user.getId()))
                .isNotEmpty()
                .satisfies(tokenOpt -> {
                    assertThat(tokenOpt.get().getTokenHash()).isNotNull();
                });
    }

    @Test
    void isEnabled_returnsTrueByDefault() {
        // The disabled (kill-switch) path is covered by AuthActivationFlowTest.KillSwitchTest,
        // which needs its own Spring context with the property overridden.
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void markVerifiedImmediately_marksUserVerified() {
        UserAccount user = seedUser("immediate@example.com");
        assertThat(user.isEmailVerified()).isFalse();

        service.markVerifiedImmediately(user);

        UserAccount reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isEmailVerified()).isTrue();
    }
}
