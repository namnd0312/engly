package com.engly.verb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end admin CRUD against a real Postgres container (Flyway V1 schema + V2 seed).
 * Drives requests through the stateless resource-server chain with a Bearer JWT bearing ROLE_ADMIN,
 * proving authz, persistence, the duplicate→409 mapping, and delete→404-on-refetch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class VerbAdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    /** Bearer JWT bearing ROLE_ADMIN — the standard admin-request decorator (no CSRF: stateless). */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static final String NEW_VERB = """
            {"baseForm":"zztestverb","pastSimple":"zztested","pastParticiple":"zztested",
             "pastSimpleAlt":null,"pastParticipleAlt":null,
             "ipaBase":null,"ipaPast":null,"ipaPp":null,
             "meaningVi":"kiểm thử","verified":false}
            """;

    @Test
    void create_thenListContainsItWithId() throws Exception {
        String location = mockMvc.perform(post("/api/admin/verbs")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_VERB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.baseForm").value("zztestverb"))
                .andReturn().getResponse().getContentAsString();

        // The created row is searchable in the admin list.
        mockMvc.perform(get("/api/admin/verbs").param("q", "zztestverb").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].baseForm").value("zztestverb"))
                .andExpect(jsonPath("$.content[0].id").isNumber());
    }

    @Test
    void create_duplicateBaseForm_returns409() throws Exception {
        // "go" is in the V2 seed → inserting it again violates the unique constraint.
        String duplicate = """
                {"baseForm":"go","pastSimple":"went","pastParticiple":"gone",
                 "meaningVi":"đi","verified":false}
                """;
        mockMvc.perform(post("/api/admin/verbs")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("resource already exists"));
    }

    @Test
    void update_thenDelete_thenRefetch404() throws Exception {
        // Create a throwaway verb.
        String body = mockMvc.perform(post("/api/admin/verbs")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"baseForm":"zzdeleteme","pastSimple":"zzd","pastParticiple":"zzd",
                                 "meaningVi":"xoá","verified":false}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);

        // Update it.
        mockMvc.perform(put("/api/admin/verbs/" + id)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"baseForm":"zzdeleteme","pastSimple":"zzd2","pastParticiple":"zzd2",
                                 "meaningVi":"xoá updated","verified":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pastSimple").value("zzd2"))
                .andExpect(jsonPath("$.verified").value(true));

        // Delete → 204; re-fetch → 404.
        mockMvc.perform(delete("/api/admin/verbs/" + id).with(admin()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/verbs/" + id).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleVerified_flipsBoolean() throws Exception {
        String body = mockMvc.perform(post("/api/admin/verbs")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"baseForm":"zztoggle","pastSimple":"zzt","pastParticiple":"zzt",
                                 "meaningVi":"bật tắt","verified":false}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);

        mockMvc.perform(patch("/api/admin/verbs/" + id + "/verified")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verified\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    /** Minimal id extraction from a JSON body without pulling in a parser dependency. */
    private static long extractId(String json) {
        int idx = json.indexOf("\"id\":");
        int start = idx + 5;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end).trim());
    }
}
