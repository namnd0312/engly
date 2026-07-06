package com.engly.identity.api;

import com.engly.common.GlobalExceptionHandler;
import com.engly.common.SecurityConfig;
import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.ProfileService;
import com.engly.identity.application.UserAccountService;
import com.engly.identity.infrastructure.UserAccountRepository;
import com.engly.study.application.ReviewService;
import com.engly.study.application.StudyDeckService;
import com.engly.study.application.StudyQueueService;
import com.engly.study.application.StudySettingsService;
import com.engly.study.application.StudyStatsService;
import com.engly.verb.admin.VerbAdminService;
import com.engly.verb.application.VerbQueryService;
import com.engly.vocab.admin.DeckAdminService;
import com.engly.vocab.admin.VocabWordAdminService;
import com.engly.vocab.imports.admin.VocabImportAdminService;
import me.desair.tus.server.TusFileUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice tests for {@link SessionController#me}: the /api/me identity projection.
 *
 * Identity comes from the app's own access JWT (resource-server chain). The {@code jwt()}
 * postprocessor injects a {@link org.springframework.security.oauth2.jwt.Jwt} principal with the
 * given claims/authorities — no real key or IdP needed. Anonymous /api/** → 401.
 *
 * @WebMvcTest still component-scans all controllers, so every controller's service dependency
 * must be mocked to satisfy the slice context.
 */
@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // Security infrastructure beans required by SecurityConfig
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserAccountService userAccountService;
    @MockitoBean private AuthTokenService authTokenService;

    // SessionController's own deps for the DB-backed /api/me projection. findBySub defaults to
    // Optional.empty in these tests → the controller exercises the claims-fallback path.
    @MockitoBean private UserAccountRepository userAccountRepository;
    @MockitoBean private ProfileService profileService;

    // Other controllers component-scanned by @WebMvcTest → their services must be mocked
    @MockitoBean private VerbQueryService verbQueryService;
    @MockitoBean private VerbAdminService verbAdminService;
    @MockitoBean private StudyDeckService studyDeckService;
    @MockitoBean private StudyQueueService studyQueueService;
    @MockitoBean private ReviewService reviewService;
    @MockitoBean private StudySettingsService studySettingsService;
    @MockitoBean private StudyStatsService studyStatsService;
    @MockitoBean private DeckAdminService deckAdminService;
    @MockitoBean private VocabWordAdminService vocabWordAdminService;
    @MockitoBean private VocabImportAdminService vocabImportAdminService;
    @MockitoBean private TusFileUploadService tusFileUploadService;

    @Test
    void me_authenticatedUser_returns200WithIdentityProjection() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(t -> t
                                .subject("user-42")
                                .claim("email", "user@example.com")
                                .claim("name", "Test User"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("Test User"))
                .andExpect(jsonPath("$.isAdmin").value(false));
    }

    @Test
    void me_authenticatedUserWithAdminRole_isAdminTrue() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt()
                                .jwt(t -> t.subject("admin-1").claim("email", "admin@example.com"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(true));
    }

    @Test
    void me_anonymousRequest_returns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_principalWithoutEmailClaim_returns200WithNullEmail() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(t -> t
                                .subject("no-email-user")
                                .claim("name", "No Email"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void me_responseContainsAllExpectedJsonFields() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(t -> t
                                .subject("full-user")
                                .claim("email", "full@example.com")
                                .claim("name", "Full User"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.isAdmin").exists());
    }
}
