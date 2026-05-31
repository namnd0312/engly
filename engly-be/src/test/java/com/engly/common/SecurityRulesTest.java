package com.engly.common;

import com.engly.verb.application.VerbQueryService;
import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.api.dto.VerbSearchPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the phase-05 BFF security boundaries:
 *  - /api/verbs/** stays public (permitAll).
 *  - /api/me requires authentication: 401 anonymous, identity projection when authenticated.
 *
 * The @WebMvcTest slice does not run the oauth2-client autoconfiguration, so the
 * ClientRegistrationRepository required by .oauth2Login() is supplied as a mock bean.
 */
@WebMvcTest
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerbQueryService verbQueryService;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

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

    /** Authenticated OIDC user → 200 with sub/email/name projected from id_token claims. */
    @Test
    void me_authenticatedOidcUser_returnsIdentity() throws Exception {
        mockMvc.perform(get("/api/me")
                        .with(oidcLogin().idToken(token -> token
                                .subject("user-123")
                                .claim("email", "alice@example.com")
                                .claim("name", "Alice Example"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("user-123"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.name").value("Alice Example"));
    }
}
