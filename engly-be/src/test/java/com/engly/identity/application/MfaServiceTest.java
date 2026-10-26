package com.engly.identity.application;

import com.engly.identity.config.MfaProperties;
import com.engly.identity.domain.InvalidMfaCodeException;
import com.engly.identity.domain.MfaAlreadyEnrolledException;
import com.engly.identity.domain.MfaLockedException;
import com.engly.identity.domain.MfaNotEnrolledException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.domain.UserMfaTotp;
import com.engly.identity.infrastructure.RefreshTokenRepository;
import com.engly.identity.infrastructure.UserMfaTotpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MfaService}: enrollment state machine, replay guard and lockout. Real codec
 * and cipher, mocked repositories, a mutable fixed clock.
 */
class MfaServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");

    private final MfaProperties props = new MfaProperties("unit-test-key", null, null, null, null);
    private final TotpCodec codec = new TotpCodec(props);
    private final MfaSecretCipher cipher = new MfaSecretCipher(props);
    private UserMfaTotpRepository enrollments;
    private RefreshTokenRepository refreshTokens;
    private MutableClock clock;
    private MfaService service;
    private UserAccount user;

    /** Minimal settable clock so tests can advance time across the lockout window. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        enrollments = mock(UserMfaTotpRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        clock = new MutableClock(T0);
        service = new MfaService(enrollments, codec, cipher, refreshTokens, clock);
        user = UserAccount.localUser("local-1", "admin@example.com", "hash", "Admin");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(enrollments.save(any(UserMfaTotp.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Enroll, capture the pending row, and return the Base32 secret bytes for computing codes. */
    private byte[] enrollAndStubRow() {
        when(enrollments.findForUpdate(user.getId())).thenReturn(Optional.empty());
        MfaEnrollment enrollment = service.beginEnrollment(user);
        var captor = org.mockito.ArgumentCaptor.forClass(UserMfaTotp.class);
        verify(enrollments).save(captor.capture());
        UserMfaTotp row = captor.getValue();
        when(enrollments.findForUpdate(user.getId())).thenReturn(Optional.of(row));
        return cipher.decrypt(row.getSecretEnc());
    }

    private String codeNow(byte[] secret) {
        return codec.generate(secret, codec.currentStep(clock.instant()));
    }

    @Test
    void beginEnrollment_createsPendingRow_andReturnsScannableSecret() {
        byte[] secret = enrollAndStubRow();
        UserMfaTotp row = enrollments.findForUpdate(user.getId()).orElseThrow();

        assertThat(row.isConfirmed()).isFalse();
        assertThat(codec.toBase32(secret)).hasSize(32);
        assertThat(service.isEnrolled(user.getId())).isFalse();
    }

    @Test
    void beginEnrollment_whilePending_replacesSecret() {
        byte[] first = enrollAndStubRow();
        UserMfaTotp row = enrollments.findForUpdate(user.getId()).orElseThrow();

        MfaEnrollment second = service.beginEnrollment(user);

        assertThat(cipher.decrypt(row.getSecretEnc())).isNotEqualTo(first);
        assertThat(second.secretBase32()).isEqualTo(codec.toBase32(cipher.decrypt(row.getSecretEnc())));
    }

    @Test
    void beginEnrollment_whenConfirmed_throws() {
        byte[] secret = enrollAndStubRow();
        service.confirmEnrollment(user, codeNow(secret));

        assertThatThrownBy(() -> service.beginEnrollment(user)).isInstanceOf(MfaAlreadyEnrolledException.class);
    }

    @Test
    void confirmEnrollment_withValidCode_activates() {
        byte[] secret = enrollAndStubRow();
        service.confirmEnrollment(user, codeNow(secret));

        UserMfaTotp row = enrollments.findForUpdate(user.getId()).orElseThrow();
        assertThat(row.isConfirmed()).isTrue();
        assertThat(row.getLastUsedStep()).isEqualTo(codec.currentStep(T0));
    }

    @Test
    void confirmEnrollment_withWrongCode_countsFailureAndThrows() {
        enrollAndStubRow();

        assertThatThrownBy(() -> service.confirmEnrollment(user, "000000")).isInstanceOf(InvalidMfaCodeException.class);
        assertThat(enrollments.findForUpdate(user.getId()).orElseThrow().getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void verify_withoutConfirmedEnrollment_throwsNotEnrolled() {
        when(enrollments.findForUpdate(user.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(user, "123456")).isInstanceOf(MfaNotEnrolledException.class);

        enrollAndStubRow(); // pending only
        assertThatThrownBy(() -> service.verify(user, "123456")).isInstanceOf(MfaNotEnrolledException.class);
    }

    @Test
    void verify_sameCodeTwice_secondIsRejectedAsReplay() {
        byte[] secret = enrollAndStubRow();
        service.confirmEnrollment(user, codeNow(secret));
        clock.advance(Duration.ofSeconds(30));
        String code = codeNow(secret);

        service.verify(user, code);
        assertThatThrownBy(() -> service.verify(user, code)).isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    void verify_fiveFailures_locksForFifteenMinutes_thenRecovers() {
        byte[] secret = enrollAndStubRow();
        service.confirmEnrollment(user, codeNow(secret));
        clock.advance(Duration.ofSeconds(30));

        for (int i = 0; i < MfaService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service.verify(user, "000000")).isInstanceOf(InvalidMfaCodeException.class);
        }
        // Locked: even the correct code is refused.
        assertThatThrownBy(() -> service.verify(user, codeNow(secret))).isInstanceOf(MfaLockedException.class);

        clock.advance(MfaService.LOCK_DURATION.plusSeconds(1));
        service.verify(user, codeNow(secret)); // no exception
        assertThat(enrollments.findForUpdate(user.getId()).orElseThrow().getFailedAttempts()).isZero();
    }

    @Test
    void disable_withValidCode_deletesRowAndClearsSessionFlags() {
        byte[] secret = enrollAndStubRow();
        service.confirmEnrollment(user, codeNow(secret));
        clock.advance(Duration.ofSeconds(30));

        service.disable(user, codeNow(secret));

        verify(enrollments).delete(any(UserMfaTotp.class));
        verify(refreshTokens).clearMfaVerifiedForUser(user.getId());
    }

    @Test
    void verify_withUndecryptableSecret_throwsUnavailable_withoutCountingFailure() {
        byte[] secret = enrollAndStubRow();
        service.confirmEnrollment(user, codeNow(secret));
        MfaService otherKey = new MfaService(enrollments, codec,
                new MfaSecretCipher(new MfaProperties("rotated-key", null, null, null, null)), refreshTokens, clock);

        assertThatThrownBy(() -> otherKey.verify(user, "123456"))
                .isInstanceOf(com.engly.identity.domain.MfaUnavailableException.class);
        assertThat(enrollments.findForUpdate(user.getId()).orElseThrow().getFailedAttempts()).isZero();
    }

    @Test
    void disable_withWrongCode_keepsEnrollment() {
        byte[] secret = enrollAndStubRow();
        service.confirmEnrollment(user, codeNow(secret));

        assertThatThrownBy(() -> service.disable(user, "000000")).isInstanceOf(InvalidMfaCodeException.class);
        verify(enrollments, org.mockito.Mockito.never()).delete(any(UserMfaTotp.class));
    }
}
