package com.engly.identity;

import com.engly.identity.infrastructure.S3AvatarStorage;
import com.engly.identity.infrastructure.S3AvatarStorage.PresignedUpload;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full profile flow against a real Postgres container (Flyway incl. V7 avatar_key): register →
 * rename → request upload URL → confirm avatar → /api/me reflects the change. S3 is mocked at the
 * {@link S3AvatarStorage} seam so CI never needs an emulator; the deterministic-key/ownership
 * logic and DB persistence are fully real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class ProfileFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // This suite tests profile management, not activation — disable the gate so
        // registerAndGetAccessCookie() keeps getting an immediate session (pre-feature behavior),
        // same as email-activation's own kill-switch tests (AuthActivationFlowTest.KillSwitchTest).
        registry.add("app.email-verification.enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private S3AvatarStorage storage;

    private Cookie registerAndGetAccessCookie(String email) throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"supersecret\",\"displayName\":\"Initial Name\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie access = registered.getResponse().getCookie("ACCESS_TOKEN");
        assertThat(access).isNotNull();
        return access;
    }

    @Test
    void updateDisplayName_persists_andMeReflectsIt() throws Exception {
        Cookie access = registerAndGetAccessCookie("rename@example.com");

        mockMvc.perform(patch("/api/profile")
                        .cookie(access).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed User\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed User"));

        mockMvc.perform(get("/api/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed User"));
    }

    @Test
    void avatarFlow_uploadUrlThenConfirm_meServesPresignedUrls() throws Exception {
        Cookie access = registerAndGetAccessCookie("avatar@example.com");

        when(storage.presignUpload(anyString(), eq("image/png")))
                .thenAnswer(inv -> new PresignedUpload(
                        "http://localhost:4566/engly-avatars/put", inv.getArgument(0), Instant.now()));

        MvcResult urlResult = mockMvc.perform(post("/api/profile/avatar/upload-url")
                        .cookie(access).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andReturn();

        String objectKey = com.jayway.jsonpath.JsonPath.read(
                urlResult.getResponse().getContentAsString(), "$.objectKey");
        assertThat(objectKey).endsWith("/original");

        // Browser "uploaded" — the stored object exists with an image content type.
        when(storage.contentTypeOf(objectKey)).thenReturn(Optional.of("image/png"));
        // Lambda already resized (variants exist) → /api/me serves the webp URLs.
        String base = objectKey.substring(0, objectKey.length() - "/original".length());
        when(storage.exists(base + "/256.webp")).thenReturn(true);
        when(storage.presignDownload(base + "/256.webp")).thenReturn("http://s3/256.webp");
        when(storage.presignDownload(base + "/64.webp")).thenReturn("http://s3/64.webp");

        mockMvc.perform(post("/api/profile/avatar/confirm")
                        .cookie(access).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + objectKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("http://s3/256.webp"))
                .andExpect(jsonPath("$.avatarThumbUrl").value("http://s3/64.webp"));

        mockMvc.perform(get("/api/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("http://s3/256.webp"));
    }

    /**
     * Documents actual CSRF posture: the resource-server configurer auto-ignores CSRF for
     * bearer-token requests, and this app resolves the bearer JWT from the ACCESS_TOKEN cookie —
     * so authenticated API mutations pass without X-XSRF-TOKEN. Cross-site forgery is instead
     * blocked by the cookie's SameSite=Lax attribute (browser won't attach it to cross-site
     * POST/PATCH). Applies to all cookie-authenticated APIs (/api/study/** included), not just
     * profile. If this ever flips to 403, the SPA must start echoing X-XSRF-TOKEN here.
     */
    @Test
    void updateDisplayName_withoutCsrfHeader_succeedsViaBearerCookieAuth() throws Exception {
        Cookie access = registerAndGetAccessCookie("nocsrf@example.com");

        mockMvc.perform(patch("/api/profile")
                        .cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Still Works\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Still Works"));
    }

    @Test
    void confirmAvatar_keyOwnedByAnotherUser_returns400() throws Exception {
        Cookie access = registerAndGetAccessCookie("intruder@example.com");
        String foreignKey = "avatars/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/original";

        mockMvc.perform(post("/api/profile/avatar/confirm")
                        .cookie(access).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + foreignKey + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadUrl_nonImageContentType_returns400() throws Exception {
        Cookie access = registerAndGetAccessCookie("pdf@example.com");

        mockMvc.perform(post("/api/profile/avatar/upload-url")
                        .cookie(access).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isBadRequest());
    }
}
