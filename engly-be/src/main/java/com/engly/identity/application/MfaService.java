package com.engly.identity.application;

import com.engly.identity.domain.InvalidMfaCodeException;
import com.engly.identity.domain.MfaAlreadyEnrolledException;
import com.engly.identity.domain.MfaLockedException;
import com.engly.identity.domain.MfaNotEnrolledException;
import com.engly.identity.domain.MfaUnavailableException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.domain.UserMfaTotp;
import com.engly.identity.infrastructure.RefreshTokenRepository;
import com.engly.identity.infrastructure.UserMfaTotpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * TOTP enrollment and verification rules. State per user: none → (enroll) pending → (confirm)
 * enrolled → (disable) none. Every code check enforces the lockout (5 failures → 15 min) and the
 * replay guard (a step can be accepted once). Failures are persisted even though the request ends
 * in an exception — see {@code noRollbackFor}.
 *
 * <p>Submitted codes and decrypted secrets are never logged.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UserMfaTotpRepository enrollments;
    private final TotpCodec codec;
    private final MfaSecretCipher cipher;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public MfaService(UserMfaTotpRepository enrollments, TotpCodec codec, MfaSecretCipher cipher,
                      RefreshTokenRepository refreshTokens, Clock clock) {
        this.enrollments = enrollments;
        this.codec = codec;
        this.cipher = cipher;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    /** Start (or restart an unconfirmed) enrollment. Refused once a confirmed enrollment exists. */
    @Transactional
    public MfaEnrollment beginEnrollment(UserAccount user) {
        byte[] secret = codec.newSecret();
        String secretEnc = cipher.encrypt(secret);
        UserMfaTotp row = enrollments.findForUpdate(user.getId()).orElse(null);
        if (row == null) {
            enrollments.save(new UserMfaTotp(user.getId(), secretEnc));
        } else if (row.isConfirmed()) {
            throw new MfaAlreadyEnrolledException();
        } else {
            row.restartEnrollment(secretEnc);
        }
        String label = user.getEmail() != null ? user.getEmail() : user.getSub();
        return new MfaEnrollment(codec.toBase32(secret), codec.otpauthUri(label, secret));
    }

    /** Prove the authenticator holds the secret; activates the enrollment. */
    @Transactional(noRollbackFor = {InvalidMfaCodeException.class, MfaLockedException.class})
    public void confirmEnrollment(UserAccount user, String code) {
        UserMfaTotp row = enrollments.findForUpdate(user.getId()).orElseThrow(MfaNotEnrolledException::new);
        if (row.isConfirmed()) {
            throw new MfaAlreadyEnrolledException();
        }
        long step = checkCode(row, code);
        row.confirm(clock.instant(), step);
    }

    /** Step-up check against a confirmed enrollment. */
    @Transactional(noRollbackFor = {InvalidMfaCodeException.class, MfaLockedException.class})
    public void verify(UserAccount user, String code) {
        UserMfaTotp row = requireConfirmed(user);
        row.recordSuccess(checkCode(row, code));
    }

    /** Remove the enrollment (needs a valid code) and drop the step-up from every live session. */
    @Transactional(noRollbackFor = {InvalidMfaCodeException.class, MfaLockedException.class})
    public void disable(UserAccount user, String code) {
        UserMfaTotp row = requireConfirmed(user);
        checkCode(row, code);
        enrollments.delete(row);
        refreshTokens.clearMfaVerifiedForUser(user.getId());
    }

    @Transactional(readOnly = true)
    public boolean isEnrolled(UUID userId) {
        return enrollments.existsByUserIdAndConfirmedAtIsNotNull(userId);
    }

    private UserMfaTotp requireConfirmed(UserAccount user) {
        return enrollments.findForUpdate(user.getId())
                .filter(UserMfaTotp::isConfirmed)
                .orElseThrow(MfaNotEnrolledException::new);
    }

    /**
     * Validate a code against the row. Returns the matched step; a non-match or a replayed step counts
     * as a failure (persisted via the caller's no-rollback transaction) and throws.
     */
    private long checkCode(UserMfaTotp row, String code) {
        Instant now = clock.instant();
        if (row.isLocked(now)) {
            throw new MfaLockedException();
        }
        byte[] secret;
        try {
            secret = cipher.decrypt(row.getSecretEnc());
        } catch (IllegalStateException e) {
            // Key changed (e.g. ephemeral MFA_SECRET_KEY + restart): no code can ever match. Surface
            // it as an operator problem rather than a generic 500; recovery is the documented SQL reset.
            log.error("TOTP secret for user {} cannot be decrypted — MFA_SECRET_KEY changed?", row.getUserId());
            throw new MfaUnavailableException();
        }
        OptionalLong matched = codec.verify(secret, code, now);
        if (matched.isEmpty() || row.isReplay(matched.getAsLong())) {
            row.recordFailure(now, MAX_ATTEMPTS, LOCK_DURATION);
            enrollments.save(row);
            throw new InvalidMfaCodeException();
        }
        return matched.getAsLong();
    }
}
