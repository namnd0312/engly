package com.engly.study;

import com.engly.study.application.ReviewService;
import com.engly.study.application.StudyDeckService;
import com.engly.study.application.StudyQueueService;
import com.engly.study.application.StudySettingsService;
import com.engly.vocab.admin.DeckAdminService;
import com.engly.vocab.admin.VocabWordAdminService;
import com.engly.vocab.imports.admin.VocabImportAdminService;
import me.desair.tus.server.TusFileUploadService;
import com.engly.verb.admin.VerbAdminService;
import com.engly.verb.application.VerbQueryService;
import com.engly.common.GlobalExceptionHandler;
import com.engly.common.SecurityConfig;
import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security rules matrix for /api/study/** endpoints (resource-server chain, app JWT):
 *  - Anonymous request → 401 (HttpStatusEntryPoint for /api/**)
 *  - Authenticated user (any role) → 200 (study routes require only authentication)
 *  - /api/admin/decks requires ROLE_ADMIN (401 without token, 403 without the role)
 *
 * Uses @WebMvcTest slice with mocked services to avoid loading the full application context.
 * All study-layer services are mocked; test only asserts HTTP status codes.
 */
@WebMvcTest
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class StudySecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    // Verb services needed because VerbAdminController + VerbController are component-scanned.
    @MockBean
    private VerbAdminService verbAdminService;
    @MockBean
    private VerbQueryService verbQueryService;

    // Vocab admin services needed because DeckAdminController + VocabWordAdminController are component-scanned.
    @MockBean
    private DeckAdminService deckAdminService;
    @MockBean
    private VocabWordAdminService vocabWordAdminService;

    // Vocab import controllers are component-scanned by @WebMvcTest → their deps must be mocked.
    @MockBean
    private VocabImportAdminService vocabImportAdminService;
    @MockBean
    private TusFileUploadService tusFileUploadService;

    // Study service mocks — controllers are component-scanned by @WebMvcTest.
    @MockBean
    private StudyDeckService studyDeckService;
    @MockBean
    private StudyQueueService studyQueueService;
    @MockBean
    private ReviewService reviewService;
    @MockBean
    private StudySettingsService studySettingsService;

    // Required by Chain 1 (resource server) to build.
    @MockBean
    private JwtDecoder jwtDecoder;

    // Required by Chain 1 (OAuth2 SSO login) to build.
    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // AuthController (component-scanned) deps; AuthTokenService also backs the cookie bearer resolver.
    @MockBean
    private UserAccountService userAccountService;
    @MockBean
    private AuthTokenService authTokenService;

    // The SSO chain wires this @Component success handler (not component-scanned in this slice).
    @MockBean(name = "openGateSsoSuccessHandler")
    private org.springframework.security.web.authentication.AuthenticationSuccessHandler openGateSsoSuccessHandler;

    // -------------------------------------------------------------------------
    // /api/study/** — anonymous → 401
    // -------------------------------------------------------------------------

    @Test
    void studyDecks_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/study/decks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studySettings_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/study/settings"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // /api/study/** — authenticated (any user, no admin role required) → 200
    // -------------------------------------------------------------------------

    @Test
    void studyDecks_authenticatedUser_returns200() throws Exception {
        // Return empty list from mock so the controller responds 200 OK
        org.mockito.Mockito.when(studyDeckService.listDecks(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/study/decks")
                        .with(jwt().jwt(j -> j.subject("any-user"))))
                .andExpect(status().isOk());
    }

    @Test
    void studySettings_authenticatedUser_returns200() throws Exception {
        org.mockito.Mockito.when(studySettingsService.getOrCreate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.engly.study.api.dto.StudySettingsResponse(20, null));

        mockMvc.perform(get("/api/study/settings")
                        .with(jwt().jwt(j -> j.subject("any-user"))))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // /api/admin/** — anonymous → 401 (Chain 1, no Bearer)
    // -------------------------------------------------------------------------

    @Test
    void adminDecks_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/decks"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // /api/admin/** — JWT without ROLE_ADMIN → 403
    // -------------------------------------------------------------------------

    @Test
    void adminDecks_jwtWithoutAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/decks").with(jwt()))
                .andExpect(status().isForbidden());
    }
}
