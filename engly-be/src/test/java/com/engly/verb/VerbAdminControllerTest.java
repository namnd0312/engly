package com.engly.verb;

import com.engly.common.GlobalExceptionHandler;
import com.engly.verb.admin.VerbAdminController;
import com.engly.verb.admin.VerbAdminService;
import com.engly.verb.api.dto.VerbAdminResponse;
import com.engly.verb.domain.VerbNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-slice tests for the admin CRUD endpoints. Security is excluded (like
 * VerbControllerTest) so CSRF/authz don't interfere — those are covered by AdminSecurityRulesTest.
 * Focus: status codes, validation (400), and 404 propagation from the service.
 */
@WebMvcTest(controllers = VerbAdminController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class})
@Import(GlobalExceptionHandler.class)
class VerbAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerbAdminService service;

    private static final String VALID_BODY = """
            {"baseForm":"swim","pastSimple":"swam","pastParticiple":"swum",
             "pastSimpleAlt":null,"pastParticipleAlt":null,
             "ipaBase":null,"ipaPast":null,"ipaPp":null,
             "meaningVi":"bơi","verified":true}
            """;

    private static VerbAdminResponse sampleResponse(Long id) {
        return new VerbAdminResponse(id, "swim", "swam", null, "swum", null,
                null, null, null, "bơi", true);
    }

    @Test
    void create_validBody_returns201WithId() throws Exception {
        when(service.create(any())).thenReturn(sampleResponse(42L));

        mockMvc.perform(post("/api/admin/verbs")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.baseForm").value("swim"));
    }

    @Test
    void create_blankBaseForm_returns400() throws Exception {
        String body = """
                {"baseForm":"","pastSimple":"swam","pastParticiple":"swum",
                 "meaningVi":"bơi","verified":false}
                """;
        mockMvc.perform(post("/api/admin/verbs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verify(service, never()).create(any());
    }

    @Test
    void update_validBody_returns200() throws Exception {
        when(service.update(eq(42L), any())).thenReturn(sampleResponse(42L));

        mockMvc.perform(put("/api/admin/verbs/42")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        when(service.update(eq(99L), any())).thenThrow(new VerbNotFoundException("99"));

        mockMvc.perform(put("/api/admin/verbs/99")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_existing_returns204() throws Exception {
        mockMvc.perform(delete("/api/admin/verbs/42"))
                .andExpect(status().isNoContent());
        verify(service).delete(42L);
    }

    @Test
    void delete_unknownId_returns404() throws Exception {
        doThrow(new VerbNotFoundException("99")).when(service).delete(99L);

        mockMvc.perform(delete("/api/admin/verbs/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleVerified_returns200WithToggledValue() throws Exception {
        VerbAdminResponse toggled = new VerbAdminResponse(42L, "swim", "swam", null, "swum", null,
                null, null, null, "bơi", false);
        when(service.toggleVerified(eq(42L), eq(false))).thenReturn(toggled);

        mockMvc.perform(patch("/api/admin/verbs/42/verified")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    void getById_found_returns200() throws Exception {
        when(service.getById(42L)).thenReturn(sampleResponse(42L));

        mockMvc.perform(get("/api/admin/verbs/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));
    }
}
