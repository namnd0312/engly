package com.engly.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A link between an external social provider account (Google/Facebook) and a local {@link UserAccount}.
 *
 * <p>{@code providerUserId} is the provider's stable subject (Google {@code sub}, Facebook {@code id});
 * {@code (provider, providerUserId)} is unique, so a returning social login always resolves to the same
 * account. {@code email} records the address seen from this provider for audit — it may be null
 * (Facebook can return no email) and is never trusted for linking on its own.
 */
@Entity
@Table(name = "app_user_identity")
public class UserIdentity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The local account this provider identity belongs to. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Registration id, lower-case: {@code google} | {@code facebook}. */
    @Column(name = "provider", length = 30, nullable = false, updatable = false)
    private String provider;

    /** Provider subject/id — stable per provider, the canonical link key. */
    @Column(name = "provider_user_id", length = 255, nullable = false, updatable = false)
    private String providerUserId;

    /** Email seen from this provider (audit only); may be null. */
    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected UserIdentity() {}

    private UserIdentity(UUID userId, String provider, String providerUserId, String email) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
    }

    /** Link a provider account to a local user. */
    public static UserIdentity link(UUID userId, String provider, String providerUserId, String email) {
        return new UserIdentity(userId, provider, providerUserId, email);
    }

    public UUID getId()             { return id; }
    public UUID getUserId()         { return userId; }
    public String getProvider()     { return provider; }
    public String getProviderUserId() { return providerUserId; }
    public String getEmail()        { return email; }
    public Instant getCreatedAt()   { return createdAt; }
}
