package com.engly.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Local user account — the app's own identity record (replaces relying solely on OPEN-GATE claims).
 *
 * <p>{@code sub} is the canonical user key that flows into the app JWT subject and links the study
 * tables ({@code user_card_state.user_sub}, {@code user_study_settings.user_sub}). Every account gets a
 * generated sub (password: {@code local-*}, social: {@code sso-*}); linking a provider to an existing
 * account reuses that account's sub, so study progress follows the linked account.
 *
 * <p>{@code passwordHash} is null for SSO-only accounts and {@code email} may be null for social
 * accounts (Facebook can return no email). {@code emailVerified} is the trust anchor for account
 * linking: a verified social identity is only merged into an existing account that is itself verified.
 * A password account starts unverified and becomes a valid link target once its owner clicks the
 * activation email (see {@link #markEmailVerified()}) — this is an intentional "verified-both-sides"
 * merge, not an oversight (see {@code SocialLoginService}).
 * Roles are stored locally and are the single source of truth for RBAC ({@code ROLE_USER}, {@code ROLE_ADMIN}).
 */
@Entity
@Table(name = "app_user")
public class UserAccount {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Canonical user key (app JWT subject, study-table user_sub). Unique, never reassigned. */
    @Column(name = "sub", length = 100, nullable = false, updatable = false)
    private String sub;

    /** May be null for social accounts (e.g. Facebook without email). */
    @Column(name = "email", length = 255)
    private String email;

    /** BCrypt hash; null for SSO-only accounts that never set a local password. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    /**
     * S3 object key of the current avatar ORIGINAL (avatars/{id}/{uploadId}/original);
     * null = no avatar. Resized WebP variants live at deterministic sibling keys.
     */
    @Column(name = "avatar_key", length = 512)
    private String avatarKey;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 50, nullable = false)
    private Set<String> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    // updatable=false (matches createdAt): with spring.jpa.open-in-view (default), an entity
    // touched more than once in the same request shares one persistence context, so the never-
    // refreshed-from-DB in-memory value (Hibernate doesn't read back insertable=false columns
    // after INSERT) would otherwise get merged back as NULL on any later flush in that request.
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected UserAccount() {}

    private UserAccount(String sub, String email, String passwordHash, String displayName,
                        boolean emailVerified, Set<String> roles) {
        this.sub = sub;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.emailVerified = emailVerified;
        this.roles = new HashSet<>(roles);
    }

    /**
     * New self-service account: generated sub, hashed password, default ROLE_USER. {@code emailVerified}
     * starts false (email ownership is not yet confirmed) and flips true once the activation link is
     * clicked ({@link #markEmailVerified()}) — until then this account cannot be an SSO link target.
     */
    public static UserAccount localUser(String sub, String email, String passwordHash, String displayName) {
        return new UserAccount(sub, email, passwordHash, displayName, false, Set.of("ROLE_USER"));
    }

    /**
     * New social (SSO) account: generated sub, no password, default ROLE_USER. {@code emailVerified}
     * reflects the provider — true for a Google-verified email, false for Facebook / unverified. Email
     * may be null (Facebook without email).
     */
    public static UserAccount ssoUser(String sub, String email, String displayName, boolean emailVerified) {
        return new UserAccount(sub, email, null, displayName, emailVerified, Set.of("ROLE_USER"));
    }

    /** Self-service profile edit. Blank names are rejected upstream by request validation. */
    public void updateDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** Point at a newly confirmed avatar original (S3 object key, ownership pre-validated). */
    public void changeAvatar(String avatarKey) {
        this.avatarKey = avatarKey;
    }

    /** Confirms email ownership (activation link clicked). Also makes this account a valid SSO link target. */
    public void markEmailVerified() {
        this.emailVerified = true;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isAdmin() {
        return roles.contains("ROLE_ADMIN");
    }

    public UUID getId()              { return id; }
    public String getSub()           { return sub; }
    public String getEmail()         { return email; }
    public String getPasswordHash()  { return passwordHash; }
    public String getDisplayName()   { return displayName; }
    public String getAvatarKey()     { return avatarKey; }
    public boolean isEmailVerified() { return emailVerified; }
    public Set<String> getRoles()    { return roles; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getUpdatedAt()    { return updatedAt; }
}
