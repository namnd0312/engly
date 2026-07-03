package com.engly.common;

import com.engly.verb.domain.VerbNotFoundException;
import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.domain.VocabWordNotFoundException;
import com.engly.vocab.imports.domain.VocabImportConflictException;
import com.engly.vocab.imports.domain.VocabImportJobNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone unit tests for {@link GlobalExceptionHandler}.
 *
 * Uses {@code MockMvcBuilders.standaloneSetup(DummyController)} — zero Spring context.
 * Each dummy endpoint throws one specific exception to exercise the corresponding
 * {@code @ExceptionHandler} method and the shared {@code buildResponse} helper.
 *
 * Assertions cover:
 * - Correct HTTP status per exception type
 * - Response body shape: timestamp, status, error, message
 * - No SQL/stacktrace leak (DataIntegrityViolation → generic message)
 * - ConstraintViolation leaf-name stripping (no method-path prefix)
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DummyController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // --- 404 Not Found family ---

    @Test
    void verbNotFound_returns404WithMessage() throws Exception {
        mockMvc.perform(get("/throw/verb-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Verb not found: go"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void vocabDeckNotFound_returns404WithMessage() throws Exception {
        mockMvc.perform(get("/throw/deck-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Vocab deck not found: 99"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void vocabWordNotFound_returns404WithMessage() throws Exception {
        mockMvc.perform(get("/throw/word-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Vocab word not found: 5"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void vocabImportJobNotFound_returns404WithMessage() throws Exception {
        mockMvc.perform(get("/throw/import-job-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Vocab import job not found: 7"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // --- 409 Conflict ---

    @Test
    void vocabImportConflict_returns409WithMessage() throws Exception {
        mockMvc.perform(get("/throw/import-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("upload not complete"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void dataIntegrityViolation_returns409WithGenericMessage_noSqlLeak() throws Exception {
        mockMvc.perform(get("/throw/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                // Must be the generic message — NOT the raw SQL detail
                .andExpect(jsonPath("$.message").value("resource already exists"))
                .andExpect(jsonPath("$.message").value(not(containsString("duplicate key"))))
                .andExpect(jsonPath("$.message").value(not(containsString("SQL"))))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // --- 400 Bad Request ---

    @Test
    void illegalArgument_returns400WithMessage() throws Exception {
        mockMvc.perform(get("/throw/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("page must be >= 0"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void constraintViolation_returns400WithLeafParamName_noMethodPathPrefix() throws Exception {
        mockMvc.perform(get("/throw/constraint-violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                // Leaf name "size" — not "verbQueryService.search.size"
                .andExpect(jsonPath("$.message").value(startsWith("size:")))
                .andExpect(jsonPath("$.message").value(not(containsString("verbQueryService"))))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // --- 500 Internal Server Error ---

    @Test
    void genericException_returns500WithGenericMessage_noStackTrace() throws Exception {
        mockMvc.perform(get("/throw/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                // Confirm no stack trace in body
                .andExpect(jsonPath("$.message").value(not(containsString("at com."))))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // --- Body shape on every response ---

    @Test
    void allResponses_containTimestampStatusErrorMessage() throws Exception {
        // Spot-check with one representative handler — shape is shared via buildResponse
        mockMvc.perform(get("/throw/verb-not-found"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.message").isString());
    }

    // -------------------------------------------------------------------------
    // Dummy controller — one endpoint per handled exception type
    // -------------------------------------------------------------------------

    @RestController
    @Validated
    static class DummyController {

        @GetMapping("/throw/verb-not-found")
        public void throwVerbNotFound() {
            throw new VerbNotFoundException("go");
        }

        @GetMapping("/throw/deck-not-found")
        public void throwDeckNotFound() {
            throw new VocabDeckNotFoundException(99L);
        }

        @GetMapping("/throw/word-not-found")
        public void throwWordNotFound() {
            throw new VocabWordNotFoundException(5L);
        }

        @GetMapping("/throw/import-job-not-found")
        public void throwImportJobNotFound() {
            throw new VocabImportJobNotFoundException(7L);
        }

        @GetMapping("/throw/import-conflict")
        public void throwImportConflict() {
            throw new VocabImportConflictException("upload not complete");
        }

        @GetMapping("/throw/illegal-argument")
        public void throwIllegalArgument() {
            throw new IllegalArgumentException("page must be >= 0");
        }

        @GetMapping("/throw/constraint-violation")
        public void throwConstraintViolation() {
            // Build a ConstraintViolation with path "verbQueryService.search.size"
            // Handler must strip to leaf "size"
            ConstraintViolation<?> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("verbQueryService.search.size");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("must be between 1 and 100");
            throw new ConstraintViolationException("constraint violation", Set.of(violation));
        }

        @GetMapping("/throw/data-integrity")
        public void throwDataIntegrity() {
            // Simulate a duplicate-key SQL exception with raw SQL detail
            throw new DataIntegrityViolationException(
                    "could not execute statement",
                    new RuntimeException("duplicate key value violates unique constraint SQL detail"));
        }

        @GetMapping("/throw/generic")
        public void throwGeneric() throws Exception {
            throw new Exception("Internal system failure with stack trace detail");
        }
    }
}
