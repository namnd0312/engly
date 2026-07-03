package com.engly.vocab;

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
 * End-to-end admin CRUD integration tests for vocab decks and vocab words
 * against a real Postgres container (Flyway V1–V4 schema + seed).
 *
 * Covers:
 *  - Deck CRUD: create → list (searchable), duplicate slug → 409, update, delete cascade.
 *  - Word CRUD: create with valid deck, bad deckId → 404, deckId filter, verified toggle.
 *
 * Auth: stateless JWT Bearer with ROLE_ADMIN, mirroring VerbAdminIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class VocabAdminIntegrationTest {

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

    /** Bearer JWT with ROLE_ADMIN — same helper as VerbAdminIntegrationTest. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // -------------------------------------------------------------------------
    // Deck CRUD
    // -------------------------------------------------------------------------

    @Test
    void createDeck_thenListContainsIt() throws Exception {
        mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test Deck","slug":"test-deck-list","description":"desc","topic":"Testing"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Test Deck"))
                .andExpect(jsonPath("$.slug").value("test-deck-list"));

        mockMvc.perform(get("/api/admin/decks").param("q", "Test Deck").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Test Deck"));
    }

    @Test
    void createDeck_duplicateSlug_returns409WithGenericMessage() throws Exception {
        // First create succeeds
        mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dup Deck","slug":"dup-slug","description":null,"topic":null}
                                """))
                .andExpect(status().isCreated());

        // Second with same slug → 409 + generic conflict message
        mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dup Deck2","slug":"dup-slug","description":null,"topic":null}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("resource already exists"));
    }

    @Test
    void updateDeck_thenDeleteAndRefetch404() throws Exception {
        String body = mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Delete Me","slug":"delete-me-deck","description":null,"topic":"temp"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);

        // Update name (slug is immutable — PUT ignores slug changes)
        mockMvc.perform(put("/api/admin/decks/" + id)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Delete Me Updated","slug":"delete-me-deck","description":"updated","topic":"temp2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Delete Me Updated"));

        // Delete → 204
        mockMvc.perform(delete("/api/admin/decks/" + id).with(admin()))
                .andExpect(status().isNoContent());

        // Re-fetch → 404
        mockMvc.perform(get("/api/admin/decks/" + id).with(admin()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Vocab word CRUD
    // -------------------------------------------------------------------------

    @Test
    void createWord_validDeck_thenListByDeckId() throws Exception {
        // Create a deck first
        String deckBody = mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Word Test Deck","slug":"word-test-deck","description":null,"topic":"words"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long deckId = extractId(deckBody);

        // Create a word in that deck
        mockMvc.perform(post("/api/admin/vocab-words")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"deckId":%d,"term":"hello","meaningVi":"xin chào",
                                 "ipa":"/həˈloʊ/","exampleEn":null,"exampleVi":null,
                                 "partOfSpeech":"exclamation","verified":false}
                                """, deckId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.term").value("hello"))
                .andExpect(jsonPath("$.deckId").value(deckId));

        // List filtered by deckId
        mockMvc.perform(get("/api/admin/vocab-words")
                        .param("deckId", String.valueOf(deckId))
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].term").value("hello"))
                .andExpect(jsonPath("$.content[0].deckId").value(deckId));
    }

    @Test
    void createWord_nonExistentDeckId_returns404() throws Exception {
        mockMvc.perform(post("/api/admin/vocab-words")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deckId":999999,"term":"ghost","meaningVi":"ma",
                                 "ipa":null,"exampleEn":null,"exampleVi":null,
                                 "partOfSpeech":null,"verified":false}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleVerified_flipsWordVerifiedFlag() throws Exception {
        // Create deck
        String deckBody = mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Toggle Deck","slug":"toggle-deck","description":null,"topic":"toggle"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long deckId = extractId(deckBody);

        // Create word (verified=false)
        String wordBody = mockMvc.perform(post("/api/admin/vocab-words")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"deckId":%d,"term":"toggle-word","meaningVi":"từ bật tắt",
                                 "ipa":null,"exampleEn":null,"exampleVi":null,
                                 "partOfSpeech":null,"verified":false}
                                """, deckId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long wordId = extractId(wordBody);

        // Toggle to verified=true
        mockMvc.perform(patch("/api/admin/vocab-words/" + wordId + "/verified")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verified\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    void deleteDeck_cascadesWords() throws Exception {
        // Create deck + word
        String deckBody = mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cascade Deck","slug":"cascade-deck","description":null,"topic":"cascade"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long deckId = extractId(deckBody);

        // Create a word
        String wordBody = mockMvc.perform(post("/api/admin/vocab-words")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"deckId":%d,"term":"cascade-word","meaningVi":"từ cascade",
                                 "ipa":null,"exampleEn":null,"exampleVi":null,
                                 "partOfSpeech":null,"verified":false}
                                """, deckId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long wordId = extractId(wordBody);

        // Delete deck → 204
        mockMvc.perform(delete("/api/admin/decks/" + deckId).with(admin()))
                .andExpect(status().isNoContent());

        // Word should be gone too (cascade) → 404
        mockMvc.perform(get("/api/admin/vocab-words/" + wordId).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listWords_pagingAndSearch() throws Exception {
        // Unique slug per test run to avoid cross-test slug collisions in shared container
        String uniqueSlug = "paging-deck-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String deckBody = mockMvc.perform(post("/api/admin/decks")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"name":"Paging Deck","slug":"%s","description":null,"topic":"paging"}
                                """, uniqueSlug)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long deckId = extractId(deckBody);

        // Create 2 words with unique terms unlikely to appear in V4 seed
        for (String term : new String[]{"zztestapple", "zztestbanana"}) {
            mockMvc.perform(post("/api/admin/vocab-words")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format("""
                                    {"deckId":%d,"term":"%s","meaningVi":"trái cây",
                                     "ipa":null,"exampleEn":null,"exampleVi":null,
                                     "partOfSpeech":"noun","verified":false}
                                    """, deckId, term)))
                    .andExpect(status().isCreated());
        }

        // Search by deckId only → returns both words in that deck
        mockMvc.perform(get("/api/admin/vocab-words")
                        .param("deckId", String.valueOf(deckId))
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Search by q only (deckId omitted) → term search across all decks, unique term hits only 1
        mockMvc.perform(get("/api/admin/vocab-words")
                        .param("q", "zztestapple")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].term").value("zztestapple"));

        // M3 fix: deckId + q combined → returns only matching word in that deck (not the other)
        mockMvc.perform(get("/api/admin/vocab-words")
                        .param("deckId", String.valueOf(deckId))
                        .param("q", "apple")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].term").value("zztestapple"));

        // M3 fix: deckId + q with no match in that deck → 0 results (q ignored the deck before this fix)
        mockMvc.perform(get("/api/admin/vocab-words")
                        .param("deckId", String.valueOf(deckId))
                        .param("q", "NOMATCH_XYZ_123")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Minimal id extractor from JSON body without extra dependencies. */
    private static long extractId(String json) {
        int idx = json.indexOf("\"id\":");
        int start = idx + 5;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end).trim());
    }
}
