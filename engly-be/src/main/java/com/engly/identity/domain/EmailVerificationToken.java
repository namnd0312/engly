package com.engly.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use, time-limited token for confirming email ownership on registration. Only the
 * SHA-256 {@code tokenHash} is stored — the raw opaque value lives solely in the emailed link.
 * Single-use is enforced atomically by the repository's conditional update, not by a
 * read-then-write on this entity (avoids a TOCTOU race under concurrent verify attempts).
 */
@Entity
@Table(name = "email_verification_token")
public class EmailVerificationToken {

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

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected EmailVerificationToken() {}

    public EmailVerificationToken(UUID userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Not consumed and not past expiry. */
    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public UUID getId()            { return id; }
    public UUID getUserId()        { return userId; }
    public String getTokenHash()   { return tokenHash; }
    public Instant getExpiresAt()  { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt()  { return createdAt; }
}
