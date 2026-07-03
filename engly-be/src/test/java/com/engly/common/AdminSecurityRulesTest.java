package com.engly.common;

import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.UserAccountService;
import com.engly.study.application.ReviewService;
import com.engly.study.application.StudyDeckService;
import com.engly.study.application.StudyQueueService;
import com.engly.study.application.StudySettingsService;
import com.engly.vocab.admin.DeckAdminService;
import com.engly.vocab.admin.VocabWordAdminService;
import com.engly.vocab.imports.admin.VocabImportAdminService;
import com.engly.verb.admin.VerbAdminService;
import me.desair.tus.server.TusFileUploadService;
import com.engly.verb.api.dto.VerbAdminPageResponse;
import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.application.VerbQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the RBAC boundary on /api/admin/** — now a stateless OAuth2 Resource Server that
 * validates a Bearer JWT:
 *  - no token                → 401 (BearerTokenAuthenticationEntryPoint).
 *  - JWT without ROLE_ADMIN  → 403.
 *  - JWT with ROLE_ADMIN     → 200.
 * Plus a regression that the public /api/verbs/** stays open (session chain).
 */
@WebMvcTest
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminSecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerbAdminService verbAdminService;

    @MockBean
    private VerbQueryService verbQueryService;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // Study controllers are component-scanned by @WebMvcTest → their services must be mocked.
    @MockBean
    private StudyDeckService studyDeckService;
    @MockBean
    private StudyQueueService studyQueueService;
    @MockBean
    private ReviewService reviewService;
    @MockBean
    private StudySettingsService studySettingsService;

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

    // The resource-server chain needs a JwtDecoder bean; jwt() post-processor bypasses it at runtime.
    @MockBean
    private JwtDecoder jwtDecoder;

    // AuthController (component-scanned) deps; AuthTokenService also backs the cookie bearer resolver.
    @MockBean
    private UserAccountService userAccountService;
    @MockBean
    private AuthTokenService authTokenService;

    // The SSO chain wires this @Component success handler (not component-scanned in this slice).
    @MockBean(name = "openGateSsoSuccessHandler")
    private org.springframework.security.web.authentication.AuthenticationSuccessHandler openGateSsoSuccessHandler;

    @Test
    void adminList_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/verbs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminList_jwtWithoutAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/verbs").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminList_jwtWithAdminRole_returns200() throws Exception {
        when(verbAdminService.search(anyString(), anyInt(), anyInt()))
                .thenReturn(new VerbAdminPageResponse(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/admin/verbs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void publicVerbDetail_anonymous_stillReturns200() throws Exception {
        VerbResponse go = new VerbResponse(
                "go", "went", null, "gone", null,
                "/ɡoʊ/", "/wɛnt/", "/ɡɔn/", "đi", false);
        when(verbQueryService.getByBaseForm(eq("go"))).thenReturn(go);

        mockMvc.perform(get("/api/verbs/go"))
                .andExpect(status().isOk());
    }
}
