package com.engly.identity;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flow against a real Postgres container (Flyway incl. V6): self-service register,
 * cookie-based identity, password login, refresh rotation, and duplicate-email rejection. Exercises
 * the local JWT decoder + {@code CookieBearerTokenResolver} that unit slices bypass.
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

    @Autowired
    private MockMvc mockMvc;

    private static Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie " + name).isNotNull();
        return c;
    }

    @Test
    void register_setsCookies_andMeReturnsIdentityFromAccessCookie() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flow1@example.com\",\"password\":\"supersecret\",\"displayName\":\"Flow One\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow1@example.com"))
                .andExpect(jsonPath("$.isAdmin").value(false))
                .andReturn();

        Cookie access = cookie(registered, "ACCESS_TOKEN");
        assertThat(access.isHttpOnly()).isTrue();
        cookie(registered, "REFRESH_TOKEN");

        // /api/me authenticates purely from the access cookie (no Authorization header).
        mockMvc.perform(get("/api/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("flow1@example.com"))
                .andExpect(jsonPath("$.sub").value(org.hamcrest.Matchers.startsWith("local-")));

        // No cookie → anonymous → 401.
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateRegister_returns409() throws Exception {
        String body = "{\"email\":\"dup@example.com\",\"password\":\"supersecret\",\"displayName\":\"Dup\"}";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void login_wrongPassword_401_correctPassword_200() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"rightpass1\",\"displayName\":\"L\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@example.com\",\"password\":\"rightpass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("login@example.com"));
    }

    @Test
    void refresh_withRefreshCookie_issuesNewAccessCookie() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"refresh@example.com\",\"password\":\"supersecret\",\"displayName\":\"R\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refresh = cookie(registered, "REFRESH_TOKEN");

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andReturn();

        Cookie newAccess = cookie(refreshed, "ACCESS_TOKEN");
        mockMvc.perform(get("/api/me").cookie(newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("refresh@example.com"));
    }
}
