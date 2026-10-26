package com.engly.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A rotating refresh token for the httpOnly-cookie session. Only the SHA-256 {@code tokenHash} is
 * stored — the raw opaque value lives solely in the browser cookie. On each refresh the used token
 * is revoked and a new one issued (rotation); logout revokes the active token.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", length = 255, nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    /** True once the owner passed the TOTP step-up; inherited by the token minted on rotation. */
    @Column(name = "mfa_verified", nullable = false)
    private boolean mfaVerified;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
        this(userId, tokenHash, expiresAt, false);
    }

    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt, boolean mfaVerified) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.mfaVerified = mfaVerified;
    }

    /** Usable only if not revoked and not past expiry. */
    public boolean isActive(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }

    public void revoke() {
        this.revoked = true;
    }

    public UUID getId()         { return id; }
    public UUID getUserId()     { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked()  { return revoked; }
    public boolean isMfaVerified() { return mfaVerified; }
}
