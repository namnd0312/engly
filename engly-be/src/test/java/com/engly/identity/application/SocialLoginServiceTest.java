package com.engly.identity.application;

import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.UserAccountRepository;
import com.engly.identity.infrastructure.UserIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolve-or-link behavior against a real Postgres (Flyway incl. V10). Exercises the verified-both-sides
 * linking policy and the concurrent-create isolation — both need real transactions/constraints, so this
 * is an integration test rather than a mocked unit slice.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class SocialLoginServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private SocialLoginService service;
    @Autowired private UserAccountRepository users;
    @Autowired private UserIdentityRepository identities;

    private static OAuth2User google(String sub, String email, boolean emailVerified, String name) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", sub);
        if (email != null) attrs.put("email", email);
        attrs.put("email_verified", emailVerified);
        attrs.put("name", name);
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, "sub");
    }

    private static OAuth2User facebook(String id, String email, String name) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", id);
        if (email != null) attrs.put("email", email);
        attrs.put("name", name);
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, "id");
    }

    /** Seed a local account directly (bypasses the SSO flow) to set up link/no-merge preconditions. */
    private UserAccount seed(String email, boolean verified, String passwordHash) {
        UserAccount user = passwordHash != null
                ? UserAccount.localUser("local-" + UUID.randomUUID(), email, passwordHash, "Seeded")
                : UserAccount.ssoUser("sso-" + UUID.randomUUID(), email, "Seeded", verified);
        return users.save(user);
    }

    @Test
    void returningLogin_resolvesToSameAccount_noDuplicateIdentity() {
        String sub = "g-return-" + UUID.randomUUID();
        UserAccount first = service.resolveOrLink("google", google(sub, "return@example.com", true, "R"));
        UserAccount again = service.resolveOrLink("google", google(sub, "return@example.com", true, "R"));

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(identities.findByProviderAndProviderUserId("google", sub)).isPresent();
    }

    @Test
    void googleVerified_linksIntoExistingVerifiedAccount() {
        UserAccount existing = seed("verified-link@example.com", true, null);
        String sub = "g-link-" + UUID.randomUUID();

        UserAccount result = service.resolveOrLink("google", google(sub, "verified-link@example.com", true, "V"));

        assertThat(result.getId()).isEqualTo(existing.getId());          // linked, not duplicated
        assertThat(identities.findByProviderAndProviderUserId("google", sub)).isPresent();
    }

    @Test
    void googleVerified_doesNotMergeIntoUnverifiedPasswordAccount() {
        UserAccount password = seed("squatted@example.com", false, "$2a$10$hash");
        String sub = "g-nomerge-" + UUID.randomUUID();

        UserAccount result = service.resolveOrLink("google", google(sub, "squatted@example.com", true, "V"));

        assertThat(result.getId()).isNotEqualTo(password.getId());       // separate account, no takeover
        assertThat(result.hasPassword()).isFalse();
    }

    @Test
    void googleVerified_noMatch_createsVerifiedAccount() {
        String sub = "g-new-" + UUID.randomUUID();
        UserAccount result = service.resolveOrLink("google", google(sub, "brandnew@example.com", true, "N"));

        assertThat(result.isEmailVerified()).isTrue();
        assertThat(result.getEmail()).isEqualTo("brandnew@example.com");
    }

    @Test
    void facebook_neverLinksByEmail_evenWhenEmailMatchesVerifiedAccount() {
        UserAccount existing = seed("fbmatch@example.com", true, null);
        String id = "fb-nolink-" + UUID.randomUUID();

        UserAccount result = service.resolveOrLink("facebook", facebook(id, "fbmatch@example.com", "F"));

        assertThat(result.getId()).isNotEqualTo(existing.getId());       // Facebook email not trusted
        assertThat(result.isEmailVerified()).isFalse();
    }

    @Test
    void facebook_withoutEmail_createsEmaillessAccount() {
        String id = "fb-noemail-" + UUID.randomUUID();
        UserAccount result = service.resolveOrLink("facebook", facebook(id, null, "F"));

        assertThat(result.getEmail()).isNull();
        assertThat(identities.findByProviderAndProviderUserId("facebook", id)).isPresent();
    }

    @Test
    void googleVerified_mixedCaseEmail_normalizesAndLinks() {
        UserAccount existing = seed("mixed@example.com", true, null);   // stored lowercase
        String sub = "g-mixed-" + UUID.randomUUID();

        UserAccount result = service.resolveOrLink("google", google(sub, "Mixed@Example.COM", true, "M"));

        assertThat(result.getId()).isEqualTo(existing.getId());          // matched despite casing
    }

    @Test
    void concurrentFirstLogin_sameIdentity_yieldsSingleAccount() throws Exception {
        String sub = "g-race-" + UUID.randomUUID();
        OAuth2User principal = google(sub, "race@example.com", true, "Race");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<UUID> a = CompletableFuture.supplyAsync(
                    () -> service.resolveOrLink("google", principal).getId(), pool);
            CompletableFuture<UUID> b = CompletableFuture.supplyAsync(
                    () -> service.resolveOrLink("google", principal).getId(), pool);

            assertThat(a.get()).isEqualTo(b.get());                      // both resolve to one account
            assertThat(users.findByEmail("race@example.com")).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }
}
