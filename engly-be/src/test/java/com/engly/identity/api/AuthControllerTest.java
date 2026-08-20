package com.engly.identity.api;

import com.engly.common.GlobalExceptionHandler;
import com.engly.common.SecurityConfig;
import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.EmailVerificationService;
import com.engly.identity.application.UserAccountService;
import com.engly.identity.domain.EmailAlreadyExistsException;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link AuthController}: register/login/refresh/logout status codes, response
 * projection, and error mapping. Service + token layers are mocked; CSRF is disabled for
 * login/register/refresh (see SecurityConfig) but required for logout.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private UserAccountService accountService;
    @MockitoBean private AuthTokenService tokenService;
    @MockitoBean private EmailVerificationService emailVerificationService;

    // SecurityConfig chain deps.
    @MockitoBean private JwtDecoder jwtDecoder;

    private static UserAccount sampleUser() {
        return UserAccount.localUser("local-x", "alice@example.com", "hash", "Alice");
    }

    @Test
    void register_valid_withEmailVerificationEnabled_returns202WithEmail() throws Exception {
        when(accountService.register(any(), any(), any())).thenReturn(sampleUser());
        when(emailVerificationService.isEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"supersecret\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.sub").doesNotExist());

        verify(emailVerificationService).startVerification(any());
        verify(tokenService, never()).issueSession(any(), any());
    }

    @Test
    void register_valid_withEmailVerificationDisabled_returns200WithIdentity() throws Exception {
        when(accountService.register(any(), any(), any())).thenReturn(sampleUser());
        when(emailVerificationService.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"supersecret\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.isAdmin").value(false));

        verify(emailVerificationService).markVerifiedImmediately(any());
        verify(tokenService).issueSession(any(), any());
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        when(accountService.register(any(), any(), any()))
                .thenThrow(new EmailAlreadyExistsException("alice@example.com"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"supersecret\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void register_invalidPayload_returns400() throws Exception {
        // Blank email + too-short password → bean validation fails before the service is touched.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"short\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_valid_returns200() throws Exception {
        when(accountService.login(any(), any())).thenReturn(sampleUser());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"supersecret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.sub").doesNotExist());

        verify(tokenService).issueSession(any(), any());
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        when(accountService.login(any(), any()))
                .thenThrow(new InvalidLoginException("Invalid email or password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_valid_returns200() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isOk());

        verify(tokenService).rotate(any(), any());
    }

    @Test
    void refresh_invalid_returns401() throws Exception {
        doThrow(new InvalidLoginException("Invalid refresh token"))
                .when(tokenService).rotate(any(), any());

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withCsrf_returns200() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isOk());

        verify(tokenService).clearSession(any(), any());
    }
}
