package com.engly.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's TOTP enrollment. Lifecycle: created unconfirmed by "enroll" → {@link #confirm} once the
 * user proves they scanned the secret → deleted by "disable". Also owns the two guards that make a
 * 6-digit code safe: a replay guard ({@code lastUsedStep}) and a failed-attempt lockout.
 *
 * <p>{@code secretEnc} is the AES-GCM ciphertext produced by {@code MfaSecretCipher}; this class
 * never sees the raw secret.
 */
@Entity
@Table(name = "user_mfa_totp")
public class UserMfaTotp {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "secret_enc", length = 255, nullable = false)
    private String secretEnc;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected UserMfaTotp() {}

    public UserMfaTotp(UUID userId, String secretEnc) {
        this.userId = userId;
        this.secretEnc = secretEnc;
    }

    /** Restart an unconfirmed enrollment with a new secret (the previous QR is abandoned). */
    public void restartEnrollment(String newSecretEnc) {
        this.secretEnc = newSecretEnc;
        this.confirmedAt = null;
        this.lastUsedStep = null;
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** A code is a replay if its step is not strictly newer than the last accepted one. */
    public boolean isReplay(long step) {
        return lastUsedStep != null && step <= lastUsedStep;
    }

    /** Count a failure; on reaching {@code maxAttempts} lock verification for {@code lockFor}. */
    public void recordFailure(Instant now, int maxAttempts, Duration lockFor) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockFor);
            failedAttempts = 0;
        }
    }

    public void recordSuccess(long step) {
        failedAttempts = 0;
        lockedUntil = null;
        lastUsedStep = step;
    }

    public void confirm(Instant now, long step) {
        recordSuccess(step);
        confirmedAt = now;
    }

    public UUID getUserId()          { return userId; }
    public String getSecretEnc()     { return secretEnc; }
    public Instant getConfirmedAt()  { return confirmedAt; }
    public Long getLastUsedStep()    { return lastUsedStep; }
    public int getFailedAttempts()   { return failedAttempts; }
    public Instant getLockedUntil()  { return lockedUntil; }
}
