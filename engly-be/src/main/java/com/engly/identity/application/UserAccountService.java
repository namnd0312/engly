package com.engly.identity.application;

import com.engly.identity.domain.EmailAlreadyExistsException;
import com.engly.identity.domain.EmailNotVerifiedException;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        // Only accounts that pass the password check learn they are unverified — this never becomes
        // an enumeration oracle for unknown emails or bad passwords (those stay generic 401 above).
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        return user;
    }

    /** Unique canonical key for password users. */
    private static String generateSub() {
        return "local-" + UUID.randomUUID();
    }

    private static String normalizeEmail(String email) {
        return EmailNormalizer.normalize(email);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
