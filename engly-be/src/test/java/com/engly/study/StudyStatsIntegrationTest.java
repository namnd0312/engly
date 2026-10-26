package com.engly.study;

import com.engly.study.application.StudyClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the progress stats API against a real Postgres container (Flyway V1–V9).
 *
 * Exercises the native aggregation SQL (to_char + AT TIME ZONE 'Asia/Saigon' bucketing) and the
 * per-review review_log write — none of which run under plain unit tests. A fixed StudyClock pins
 * "today" (and review_log.reviewed_at) to 2026-06-15 Saigon so buckets are deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class StudyStatsIntegrationTest {

    /** Fixed "today" in Saigon: 2026-06-15T00:00:00Z = 2026-06-15 07:00 Saigon → date 2026-06-15. */
    static final Instant FIXED_INSTANT = Instant.parse("2026-06-15T00:00:00Z");
    static final ZoneId SAIGON = ZoneId.of("Asia/Saigon");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public StudyClock fixedStudyClock() {
            return new StudyClock(Clock.fixed(FIXED_INSTANT, SAIGON));
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String sub) {
        return jwt().jwt(j -> j.subject(sub));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("mfa", true)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * End-to-end: one GOOD review writes a review_log row and is reflected across all stats
     * endpoints, with correct Asia/Saigon date bucketing. Uses a dedicated deck/word + fresh
     * user (never calls the queue) so counts are exact.
     */
    @Test
    void review_reflectedAcrossStatsEndpoints_withSaigonBucketing() throws Exception {
        final String sub = "stats-user-1";

        // Seed an isolated deck + one word via admin.
        String deckJson = mockMvc.perform(post("/api/admin/decks").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stats Deck\",\"slug\":\"stats-deck\",\"description\":null,\"topic\":\"test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long deckId = extractId(deckJson);

        String wordJson = mockMvc.perform(post("/api/admin/vocab-words").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"deckId\":%d,\"term\":\"stat-word\",\"meaningVi\":\"từ\"," +
                                "\"ipa\":null,\"exampleEn\":null,\"exampleVi\":null,\"partOfSpeech\":null,\"verified\":false}", deckId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long wordId = extractId(wordJson);

        // Review GOOD → state REVIEW, interval 1, dueOn 2026-06-16; appends one review_log row.
        mockMvc.perform(post("/api/study/review").with(user(sub)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"wordId\":%d,\"rating\":\"GOOD\"}", wordId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVIEW"));

        // summary: one card, in REVIEW, one lifetime + today review, streak 1, due tomorrow (not today).
        mockMvc.perform(get("/api/study/stats/summary").with(user(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCards").value(1))
                .andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.newCount").value(0))
                .andExpect(jsonPath("$.totalReviews").value(1))
                .andExpect(jsonPath("$.reviewedToday").value(1))
                .andExpect(jsonPath("$.dueToday").value(0))
                .andExpect(jsonPath("$.currentStreakDays").value(1));

        // activity: last point (today, 2026-06-15) has reviews=1 (from review_log, Saigon bucket).
        mockMvc.perform(get("/api/study/stats/activity").with(user(sub)).param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[6].date").value("2026-06-15"))
                .andExpect(jsonPath("$[6].reviews").value(1));

        // accuracy: today's GOOD counted.
        mockMvc.perform(get("/api/study/stats/accuracy").with(user(sub)).param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[6].good").value(1))
                .andExpect(jsonPath("$[6].again").value(0));

        // forecast: card due 2026-06-16 (tomorrow) → appears in the 7-day window at index 1.
        mockMvc.perform(get("/api/study/stats/forecast").with(user(sub)).param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].date").value("2026-06-15"))
                .andExpect(jsonPath("$[1].date").value("2026-06-16"))
                .andExpect(jsonPath("$[1].dueCount").value(1));
    }

    @Test
    void stats_isolatedPerUser() throws Exception {
        // A brand-new user with no history sees all zeros — never another user's data.
        mockMvc.perform(get("/api/study/stats/summary").with(user("stats-user-empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCards").value(0))
                .andExpect(jsonPath("$.totalReviews").value(0))
                .andExpect(jsonPath("$.currentStreakDays").value(0));
    }

    @Test
    void activity_daysOverMax_returns400() throws Exception {
        mockMvc.perform(get("/api/study/stats/activity").with(user("stats-user-1")).param("days", "91"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stats_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/study/stats/summary"))
                .andExpect(status().isUnauthorized());
    }

    /** Generic id extractor for admin create responses. */
    private static long extractId(String json) {
        int idx = json.indexOf("\"id\":");
        if (idx < 0) throw new AssertionError("No id found in: " + json);
        int start = idx + 5;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end).trim());
    }
}
