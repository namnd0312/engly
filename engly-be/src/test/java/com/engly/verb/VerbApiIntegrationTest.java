package com.engly.verb;

import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.api.dto.VerbSearchPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the verb REST API against a real Postgres container.
 * Flyway V1 (schema) + V2 (180-row seed) run automatically before tests.
 * Asserts actual SQL queries, pagination envelope, 404, and 400 validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class VerbApiIntegrationTest {

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
    private TestRestTemplate restTemplate;

    // --- Search endpoint ---

    @Test
    void search_go_returnsGoWentGoneWithMeaning() {
        ResponseEntity<VerbSearchPageResponse> resp =
                restTemplate.getForEntity("/api/verbs?q=go", VerbSearchPageResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        VerbSearchPageResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.content()).isNotEmpty();

        VerbResponse go = body.content().stream()
                .filter(v -> "go".equals(v.baseForm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("'go' not found in results"));

        assertThat(go.pastSimple()).isEqualTo("went");
        assertThat(go.pastParticiple()).isEqualTo("gone");
        assertThat(go.meaningVi()).isEqualTo("đi");
    }

    @Test
    void search_emptyQuery_returnsSeedCount() {
        ResponseEntity<VerbSearchPageResponse> resp =
                restTemplate.getForEntity("/api/verbs?q=&size=100", VerbSearchPageResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        VerbSearchPageResponse body = resp.getBody();
        assertThat(body).isNotNull();
        // V2 seed contains 179 rows; total must be >= 179
        assertThat(body.totalElements()).isGreaterThanOrEqualTo(179L);
    }

    @Test
    void search_sizeOver100_returns400() {
        ResponseEntity<String> resp =
                restTemplate.getForEntity("/api/verbs?size=9999", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Detail endpoint ---

    @Test
    void getByBaseForm_go_returns200WithDetail() {
        ResponseEntity<VerbResponse> resp =
                restTemplate.getForEntity("/api/verbs/go", VerbResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        VerbResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.baseForm()).isEqualTo("go");
        assertThat(body.pastSimple()).isEqualTo("went");
        assertThat(body.pastParticiple()).isEqualTo("gone");
        assertThat(body.meaningVi()).isEqualTo("đi");
    }

    @Test
    void getByBaseForm_caseInsensitive_returnsVerb() {
        ResponseEntity<VerbResponse> resp =
                restTemplate.getForEntity("/api/verbs/GO", VerbResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().baseForm()).isEqualTo("go");
    }

    @Test
    void getByBaseForm_unknown_returns404() {
        ResponseEntity<String> resp =
                restTemplate.getForEntity("/api/verbs/zzznotaverb", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
