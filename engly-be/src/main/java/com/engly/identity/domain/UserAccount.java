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

    /** New SSO account provisioned on first OPEN-GATE login: keeps the IdP sub, no local password. */
    public static UserAccount ssoUser(String sub, String email, String displayName) {
        return new UserAccount(sub, email, null, displayName, Set.of("ROLE_USER"));
    }

    /** Update profile fields on a returning SSO login (name/email may have changed at the IdP). */
    public void applySsoProfile(String email, String displayName) {
        if (email != null && !email.isBlank()) {
            this.email = email;
        }
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
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
    public boolean isEmailVerified() { return emailVerified; }
    public Set<String> getRoles()    { return roles; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getUpdatedAt()    { return updatedAt; }
}
