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
 * tables ({@code user_card_state.user_sub}, {@code user_study_settings.user_sub}). Password users
 * get a generated sub; SSO users keep OPEN-GATE's sub so prior progress stays attached.
 *
 * <p>{@code passwordHash} is null for SSO-only accounts. Roles are stored locally and are the single
 * source of truth for RBAC (e.g. {@code ROLE_USER}, {@code ROLE_ADMIN}).
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

    @Column(name = "email", length = 255, nullable = false)
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

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = true)
    private Instant updatedAt;

    /** Required by JPA. */
    protected UserAccount() {}

    private UserAccount(String sub, String email, String passwordHash, String displayName, Set<String> roles) {
        this.sub = sub;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.roles = new HashSet<>(roles);
    }

    /** New self-service account: generated sub, hashed password, default ROLE_USER. */
    public static UserAccount localUser(String sub, String email, String passwordHash, String displayName) {
        return new UserAccount(sub, email, passwordHash, displayName, Set.of("ROLE_USER"));
    }

    /** Self-service profile edit. Blank names are rejected upstream by request validation. */
    public void updateDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** Point at a newly confirmed avatar original (S3 object key, ownership pre-validated). */
    public void changeAvatar(String avatarKey) {
        this.avatarKey = avatarKey;
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
