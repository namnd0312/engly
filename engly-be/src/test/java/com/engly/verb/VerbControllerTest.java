package com.engly.verb;

import com.engly.common.GlobalExceptionHandler;
import com.engly.verb.api.VerbController;
import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.api.dto.VerbSearchPageResponse;
import com.engly.verb.application.VerbQueryService;
import com.engly.verb.domain.VerbNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// phase-05: the oauth2-client starter adds OAuth2ClientAutoConfiguration, whose
// oauth2SecurityFilterChain needs an HttpSecurity bean — gone once SecurityAutoConfiguration
// is excluded. This slice tests the controller only, so exclude the oauth2 autoconfig too.
@WebMvcTest(controllers = VerbController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class})
@Import(GlobalExceptionHandler.class)
class VerbControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerbQueryService verbQueryService;

    // --- GET /api/verbs ---

    @Test
    void listVerbs_returnsOkWithEnvelope() throws Exception {
        VerbResponse verbResponse = new VerbResponse(
                "go", "went", null, "gone", null,
                "/ɡoʊ/", "/wɛnt/", "/ɡɒn/", "đi", false);
        VerbSearchPageResponse pageResponse = new VerbSearchPageResponse(
                List.of(verbResponse), 0, 20, 1L, 1);

        when(verbQueryService.search(anyString(), eq(0), eq(20))).thenReturn(pageResponse);

        mockMvc.perform(get("/api/verbs").param("q", "go")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].baseForm").value("go"))
                .andExpect(jsonPath("$.content[0].pastSimple").value("went"))
                .andExpect(jsonPath("$.content[0].pastParticiple").value("gone"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void listVerbs_defaultParams_returnsOk() throws Exception {
        VerbSearchPageResponse empty = new VerbSearchPageResponse(List.of(), 0, 20, 0L, 0);
        when(verbQueryService.search(eq(""), eq(0), eq(20))).thenReturn(empty);

        mockMvc.perform(get("/api/verbs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listVerbs_invalidSizeOver100_returns400() throws Exception {
        when(verbQueryService.search(anyString(), anyInt(), eq(9999)))
                .thenThrow(new IllegalArgumentException("size must be between 1 and 100, got: 9999"));

        mockMvc.perform(get("/api/verbs").param("size", "9999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void listVerbs_invalidSizeZero_returns400() throws Exception {
        when(verbQueryService.search(anyString(), anyInt(), eq(0)))
                .thenThrow(new IllegalArgumentException("size must be between 1 and 100, got: 0"));

        mockMvc.perform(get("/api/verbs").param("size", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // --- GET /api/verbs/{baseForm} ---

    @Test
    void getVerb_found_returns200WithDetail() throws Exception {
        VerbResponse verbResponse = new VerbResponse(
                "go", "went", null, "gone", null,
                "/ɡoʊ/", "/wɛnt/", "/ɡɒn/", "đi", false);
        when(verbQueryService.getByBaseForm("go")).thenReturn(verbResponse);

        mockMvc.perform(get("/api/verbs/go").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseForm").value("go"))
                .andExpect(jsonPath("$.pastSimple").value("went"))
                .andExpect(jsonPath("$.meaningVi").value("đi"));
    }

    @Test
    void getVerb_notFound_returns404() throws Exception {
        when(verbQueryService.getByBaseForm("zzzznotaverb"))
                .thenThrow(new VerbNotFoundException("zzzznotaverb"));

        mockMvc.perform(get("/api/verbs/zzzznotaverb").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Verb not found: zzzznotaverb"));
    }
}
