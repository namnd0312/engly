package com.engly.common;

import com.engly.identity.application.MfaService;
import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.EmailVerificationService;
import com.engly.identity.application.UserAccountService;
import com.engly.identity.application.ProfileService;
import com.engly.identity.infrastructure.UserAccountRepository;
import com.engly.study.application.ReviewService;
import com.engly.study.application.StudyDeckService;
import com.engly.study.application.StudyQueueService;
import com.engly.study.application.StudySettingsService;
import com.engly.study.application.StudyStatsService;
import com.engly.vocab.admin.DeckAdminService;
import com.engly.vocab.admin.VocabWordAdminService;
import com.engly.vocab.imports.admin.VocabImportAdminService;
import me.desair.tus.server.TusFileUploadService;
import com.engly.verb.admin.VerbAdminService;
import com.engly.verb.application.VerbQueryService;
import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.api.dto.VerbSearchPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the API security boundaries:
 *  - /api/verbs/** stays public (permitAll).
 *  - /api/me requires authentication: 401 anonymous, identity projection from the app JWT.
 *
 * The @WebMvcTest slice does not run the oauth2-client autoconfiguration, so the
 */
@WebMvcTest
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerbQueryService verbQueryService;

    // VerbAdminController is component-scanned by @WebMvcTest → its service must be mocked.
    @MockBean
    private VerbAdminService verbAdminService;

    // Study controllers are component-scanned by @WebMvcTest → their services must be mocked.
    @MockBean
    private StudyDeckService studyDeckService;
    @MockBean
    private StudyQueueService studyQueueService;
    @MockBean
    private ReviewService reviewService;
    @MockBean
    private StudySettingsService studySettingsService;
    @MockBean
    private StudyStatsService studyStatsService;

    // Vocab admin controllers are component-scanned by @WebMvcTest → their services must be mocked.
    @MockBean
    private DeckAdminService deckAdminService;
    @MockBean
    private VocabWordAdminService vocabWordAdminService;

    // Vocab import controllers are component-scanned by @WebMvcTest → their deps must be mocked.
    @MockBean
    private VocabImportAdminService vocabImportAdminService;
    @MockBean
    private TusFileUploadService tusFileUploadService;


    // Required so the resource-server chain in SecurityConfig can build.
    @MockBean
    private JwtDecoder jwtDecoder;
    // MfaAccessDeniedHandler + MfaController (component-scanned) deps.
    @MockBean
    private MfaService mfaService;

    // AuthController (component-scanned) deps; AuthTokenService also backs the cookie bearer resolver.
    @MockBean
    private UserAccountService userAccountService;
    @MockBean
    private AuthTokenService authTokenService;
    @MockBean
    private EmailVerificationService emailVerificationService;

    // ProfileController + SessionController (avatar feature) deps — component-scanned too.
    @MockBean
    private ProfileService profileService;
    @MockBean
    private UserAccountRepository userAccountRepository;


    /** /api/verbs/{base} is publicly reachable without any session or token. */
    @Test
    void verbsEndpoint_anonymousRequest_isPermittedReturns200() throws Exception {
        VerbResponse goVerb = new VerbResponse(
                "go", "went", null, "gone", null,
                "/ɡoʊ/", "/wɛnt/", "/ɡɔn/", "đi", false);
        when(verbQueryService.getByBaseForm(eq("go"))).thenReturn(goVerb);

        mockMvc.perform(get("/api/verbs/go"))
                .andExpect(status().isOk());
    }

    /** /api/verbs search is also publicly reachable — no auth required. */
    @Test
    void verbsSearch_anonymousRequest_isPermittedReturns200() throws Exception {
        VerbSearchPageResponse page = new VerbSearchPageResponse(List.of(), 0, 20, 0L, 0);
        when(verbQueryService.search(anyString(), anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/verbs").param("q", "go"))
                .andExpect(status().isOk());
    }

    /** Anonymous /api/me → 401 (HttpStatusEntryPoint for /api/**, no redirect). */
    @Test
    void me_anonymousRequest_returns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    /** Anonymous PATCH /api/profile → 401 (authenticated matcher, HttpStatusEntryPoint). */
    @Test
    void profile_anonymousRequest_returns401() throws Exception {
        mockMvc.perform(patch("/api/profile")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"New Name\"}"))
                .andExpect(status().isUnauthorized());
    }

    // NOTE: "authenticated but no X-XSRF-TOKEN → 403" is intentionally NOT asserted: the
    // resource-server configurer auto-ignores CSRF for bearer requests, and this app resolves
    // the bearer JWT from the ACCESS_TOKEN cookie — so cookie-authenticated mutations skip the
    // CSRF check app-wide. Cross-site forgery is mitigated by SameSite=Lax on the cookie. See
    // ProfileFlowIntegrationTest#updateDisplayName_withoutCsrfHeader_succeedsViaBearerCookieAuth.

    /** Authenticated user → 200 with email/name projected from the app JWT claims (no sub exposed). */
    @Test
    void me_authenticatedUser_returnsIdentity() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(jwt().jwt(token -> token
                                .subject("user-123")
                                .claim("email", "alice@example.com")
                                .claim("name", "Alice Example"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").doesNotExist())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.name").value("Alice Example"));
    }
}
