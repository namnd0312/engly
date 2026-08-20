package com.engly.identity.api;

import com.engly.common.GlobalExceptionHandler;
import com.engly.common.SecurityConfig;
import com.engly.identity.application.ActivationEmailSender;
import com.engly.identity.application.EmailVerificationService;
import com.engly.identity.domain.InvalidVerificationTokenException;
import com.engly.identity.infrastructure.EmailVerificationTokenRepository;
import com.engly.identity.infrastructure.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end email activation flow: register (202, no session), login unverified (403),
 * verify-email (200 + session), resend-verification (202), and kill-switch behavior.
 * Uses real Postgres (Flyway incl. V11) and mocks the email sender.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class AuthActivationFlowTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Email verification ENABLED for this flow test.
        registry.add("app.email-verification.enabled", () -> true);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserAccountRepository users;
    @Autowired private EmailVerificationTokenRepository tokens;
    @MockitoBean private ActivationEmailSender emailSender;

    private static Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie " + name).isNotNull();
        return c;
    }

    @Test
    void register_returns202_noSetCookie_bodyHasEmail() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"supersecret\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andReturn();

        // Assert NO cookies set.
        assertThat(result.getResponse().getCookie("ACCESS_TOKEN")).isNull();
        assertThat(result.getResponse().getCookie("REFRESH_TOKEN")).isNull();

        // Assert a token was created and sender was invoked.
        assertThat(users.findByEmail("alice@example.com")).isPresent();
        verify(emailSender).send(anyString(), anyString());
    }

    @Test
    void login_unverified_returns403_withEmailNotVerifiedCode() throws Exception {
        // First, register.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"password\":\"supersecret\",\"displayName\":\"Bob\"}"))
                .andExpect(status().isAccepted());

        // Attempt to login without verifying.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"password\":\"supersecret\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void login_badPassword_returns401_generic() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carol@example.com\",\"password\":\"correctpass\",\"displayName\":\"Carol\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carol@example.com\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyEmail_validToken_returns200_setsCookies_userNowVerified() throws Exception {
        // Register.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dave@example.com\",\"password\":\"supersecret\",\"displayName\":\"Dave\"}"))
                .andExpect(status().isAccepted());

        // Capture the raw token from the sender invocation.
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(anyString(), tokenCaptor.capture());
        String rawToken = tokenCaptor.getValue();

        // Verify email.
        MvcResult verified = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // Assert cookies are set.
        cookie(verified, "ACCESS_TOKEN");
        cookie(verified, "REFRESH_TOKEN");

        // Assert user is now verified in the DB.
        assertThat(users.findByEmail("dave@example.com"))
                .isPresent()
                .get()
                .satisfies(user -> assertThat(user.isEmailVerified()).isTrue());

        // Subsequent login should succeed (user now verified).
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dave@example.com\",\"password\":\"supersecret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dave@example.com"));
    }

    @Test
    void verifyEmail_badToken_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"invalid-token-that-doesnt-exist\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendVerification_returns202_forAnyInput() throws Exception {
        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nonexistent@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void register_always_createsToken() throws Exception {
        // Register successfully creates a token; the send-failure-is-non-fatal guarantee itself
        // (ActivationEmailSender swallowing a JavaMailSender exception) is unit-tested directly in
        // ActivationEmailSenderTest — mocking the whole ActivationEmailSender bean here (as this
        // class does, to capture the raw token) would bypass its own try/catch.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"eve@example.com\",\"password\":\"supersecret\",\"displayName\":\"Eve\"}"))
                .andExpect(status().isAccepted());

        // Assert the user and token were persisted.
        assertThat(users.findByEmail("eve@example.com")).isPresent();
        assertThat(tokens.findFirstByUserIdOrderByCreatedAtDesc(users.findByEmail("eve@example.com").get().getId()))
                .isNotEmpty();
    }

    /**
     * Kill-switch test: with email verification disabled, register returns immediate session.
     */
    @SpringBootTest(properties = "app.email-verification.enabled=false")
    @AutoConfigureMockMvc
    @Testcontainers
    @Tag("integration")
    static class KillSwitchTest {

        @Container
        static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

        @DynamicPropertySource
        static void datasourceProps(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }

        @Autowired private MockMvc mockMvc;
        @Autowired private UserAccountRepository users;
        @MockitoBean private ActivationEmailSender emailSender;

        @Test
        void register_returns200_withIdentity_andSetsCookies() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"frank@example.com\",\"password\":\"supersecret\",\"displayName\":\"Frank\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("frank@example.com"))
                    .andReturn();

            // Assert cookies ARE set (pre-feature behavior).
            assertThat(result.getResponse().getCookie("ACCESS_TOKEN")).isNotNull();
            assertThat(result.getResponse().getCookie("REFRESH_TOKEN")).isNotNull();

            // Email sender should NOT be invoked (no verification flow).
            verify(emailSender, org.mockito.Mockito.never()).send(anyString(), anyString());
        }

        @Test
        void login_unverified_accounts_succeed() throws Exception {
            // Register (returns immediate session, user is marked verified).
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"grace@example.com\",\"password\":\"supersecret\",\"displayName\":\"Grace\"}"))
                    .andExpect(status().isOk());

            // Login should succeed (no gating).
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"grace@example.com\",\"password\":\"supersecret\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("grace@example.com"));
        }
    }
}
