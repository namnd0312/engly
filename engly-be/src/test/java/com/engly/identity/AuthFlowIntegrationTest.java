package com.engly.identity;

import com.engly.identity.application.ActivationEmailSender;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flow with email verification enabled: self-service register (202, no session),
 * email activation, then login/refresh. Uses mocked SMTP sender to avoid real mail sends.
 * Exercises the full auth chain including the local JWT decoder + {@code CookieBearerTokenResolver}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class AuthFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ActivationEmailSender emailSender;

    private static Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie " + name).isNotNull();
        return c;
    }

    /**
     * Register → 202, no cookies. Capture the token from sender and verify email → 200 + cookies.
     * Then /api/me succeeds with the access cookie.
     */
    @Test
    void register_then_verifyEmail_setsSessionAndMeReturnsIdentity() throws Exception {
        // Register (returns 202 + email, no session).
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flow1@example.com\",\"password\":\"supersecret\",\"displayName\":\"Flow One\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.email").value("flow1@example.com"))
                .andReturn();

        // Assert NO cookies yet.
        assertThat(registered.getResponse().getCookie("ACCESS_TOKEN")).isNull();

        // Capture the token from the sender invocation.
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(emailSender).send(anyString(), tokenCaptor.capture());
        String rawToken = tokenCaptor.getValue();

        // Verify email (returns 200 + cookies).
        MvcResult verified = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie access = cookie(verified, "ACCESS_TOKEN");
        assertThat(access.isHttpOnly()).isTrue();
        cookie(verified, "REFRESH_TOKEN");

        // /api/me authenticates purely from the access cookie.
        mockMvc.perform(get("/api/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow1@example.com"))
                .andExpect(jsonPath("$.sub").doesNotExist());

        // No cookie → anonymous → 401.
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateRegister_returns409() throws Exception {
        String body = "{\"email\":\"dup@example.com\",\"password\":\"supersecret\",\"displayName\":\"Dup\"}";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        // Second register with same email → 409.
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void login_wrongPassword_401_unverified_403() throws Exception {
        // Register (no session yet).
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"rightpass1\",\"displayName\":\"L\"}"))
                .andExpect(status().isAccepted());

        // Wrong password → 401.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized());

        // Correct password but unverified → 403 EMAIL_NOT_VERIFIED.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"rightpass1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        // Verify email then login succeeds.
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(emailSender).send(anyString(), tokenCaptor.capture());
        String rawToken = tokenCaptor.getValue();

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk());

        // Now login succeeds.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"rightpass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("login@example.com"));
    }

    @Test
    void refresh_withRefreshCookie_issuesNewAccessCookie() throws Exception {
        // Register and verify.
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"refresh@example.com\",\"password\":\"supersecret\",\"displayName\":\"R\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(emailSender).send(anyString(), tokenCaptor.capture());
        String rawToken = tokenCaptor.getValue();

        MvcResult verified = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refresh = cookie(verified, "REFRESH_TOKEN");

        // Refresh → new access cookie.
        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andReturn();

        Cookie newAccess = cookie(refreshed, "ACCESS_TOKEN");
        mockMvc.perform(get("/api/me").cookie(newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("refresh@example.com"));
    }
}
