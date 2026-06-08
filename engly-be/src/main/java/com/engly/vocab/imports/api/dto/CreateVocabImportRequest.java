package com.engly.vocab.imports.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/admin/vocab-imports.
 * @param deckId   target deck the words are imported into (must exist).
 * @param uploadId tus upload id (last path segment of the completed upload URL).
 */
public record CreateVocabImportRequest(
        @NotNull Long deckId,
        @NotBlank String uploadId
) {}
