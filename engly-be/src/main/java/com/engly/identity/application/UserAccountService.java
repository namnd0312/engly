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
 * Local account lifecycle: self-service registration and password login.
 *
 * <p>RBAC lives entirely here (roles on {@link UserAccount}).
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

    /** Unique canonical key for password users. */
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
