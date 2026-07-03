package com.engly.identity.application;

import com.engly.identity.domain.EmailAlreadyExistsException;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAccountService}: registration, password login, and SSO provisioning.
 * Uses a real BCrypt encoder and a mocked repository.
 */
class UserAccountServiceTest {

    private UserAccountRepository users;
    private UserAccountService service;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        service = new UserAccountService(users, encoder);
        when(users.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void register_newEmail_createsLocalUserWithHashedPasswordAndUserRole() {
        when(users.existsByEmail("alice@example.com")).thenReturn(false);

        UserAccount user = service.register("Alice@Example.com ", "supersecret", "Alice");

        assertThat(user.getEmail()).isEqualTo("alice@example.com"); // normalized
        assertThat(user.getSub()).startsWith("local-");
        assertThat(user.getRoles()).containsExactly("ROLE_USER");
        assertThat(user.hasPassword()).isTrue();
        assertThat(encoder.matches("supersecret", user.getPasswordHash())).isTrue();
    }

    @Test
    void register_existingEmail_throwsConflict() {
        when(users.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register("taken@example.com", "supersecret", "X"))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(users, never()).save(any());
    }

    @Test
    void login_correctPassword_returnsUser() {
        UserAccount stored = UserAccount.localUser("local-1", "bob@example.com",
                encoder.encode("rightpass"), "Bob");
        when(users.findByEmail("bob@example.com")).thenReturn(Optional.of(stored));

        assertThat(service.login("bob@example.com", "rightpass")).isSameAs(stored);
    }

    @Test
    void login_wrongPassword_throws401() {
        UserAccount stored = UserAccount.localUser("local-1", "bob@example.com",
                encoder.encode("rightpass"), "Bob");
        when(users.findByEmail("bob@example.com")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.login("bob@example.com", "wrongpass"))
                .isInstanceOf(InvalidLoginException.class);
    }

    @Test
    void login_unknownEmail_throws401() {
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost@example.com", "whatever"))
                .isInstanceOf(InvalidLoginException.class);
    }

    @Test
    void login_ssoOnlyAccountWithoutPassword_throws401() {
        UserAccount sso = UserAccount.ssoUser("opengate-1", "sso@example.com", "SSO User");
        when(users.findByEmail("sso@example.com")).thenReturn(Optional.of(sso));

        assertThatThrownBy(() -> service.login("sso@example.com", "anything"))
                .isInstanceOf(InvalidLoginException.class);
    }

    @Test
    void provisionSsoUser_existingEmail_keepsExistingSub() {
        // A user who first registered with a password owns a local sub; SSO must reuse it.
        UserAccount existing = UserAccount.localUser("local-keep-me", "linked@example.com",
                encoder.encode("pw"), "Old Name");
        when(users.findByEmail("linked@example.com")).thenReturn(Optional.of(existing));

        UserAccount result = service.provisionSsoUser("opengate-different-sub", "linked@example.com", "New Name");

        assertThat(result.getSub()).isEqualTo("local-keep-me"); // OPEN-GATE sub ignored — link by email
        assertThat(result.getDisplayName()).isEqualTo("New Name"); // profile refreshed
        verify(users, never()).save(any()); // existing managed entity updated via dirty checking
    }

    @Test
    void provisionSsoUser_newEmail_adoptsOpenGateSub() {
        when(users.findByEmail("fresh@example.com")).thenReturn(Optional.empty());

        UserAccount result = service.provisionSsoUser("opengate-sub-99", "fresh@example.com", "Fresh");

        assertThat(result.getSub()).isEqualTo("opengate-sub-99");
        assertThat(result.hasPassword()).isFalse();
        assertThat(result.getRoles()).containsExactly("ROLE_USER");
    }
}
