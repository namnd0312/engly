package com.engly.identity.application;

import com.engly.identity.domain.EmailAlreadyExistsException;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Local account lifecycle: self-service registration, password login, and SSO provisioning.
 *
 * <p>RBAC lives entirely here (roles on {@link UserAccount}). SSO links by email and reuses the
 * existing account's {@code sub} so study data keyed by that sub stays attached; only a brand-new
 * SSO account adopts OPEN-GATE's sub.
 */
@Service
public class UserAccountService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserAccount register(String email, String rawPassword, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException(normalizedEmail);
        }
        UserAccount user = UserAccount.localUser(
                generateSub(), normalizedEmail, passwordEncoder.encode(rawPassword), trimToNull(displayName));
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public UserAccount login(String email, String rawPassword) {
        UserAccount user = users.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new InvalidLoginException("Invalid email or password"));
        if (!user.hasPassword() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidLoginException("Invalid email or password");
        }
        return user;
    }

    /**
     * Find-or-create the local account for an OPEN-GATE SSO login. Existing accounts (matched by
     * email) keep their sub and just refresh profile fields; new accounts adopt the IdP sub.
     */
    @Transactional
    public UserAccount provisionSsoUser(String openGateSub, String email, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        return users.findByEmail(normalizedEmail)
                .map(existing -> {
                    existing.applySsoProfile(normalizedEmail, trimToNull(displayName));
                    return existing;
                })
                .orElseGet(() -> users.save(
                        UserAccount.ssoUser(openGateSub, normalizedEmail, trimToNull(displayName))));
    }

    /** Unique canonical key for password users — distinct from any OPEN-GATE sub namespace. */
    private static String generateSub() {
        return "local-" + UUID.randomUUID();
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
