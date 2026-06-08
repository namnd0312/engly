package com.engly.vocab.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update request body for a VocabWord.
 * deckId is resolved to a VocabDeck entity in the service layer (404 if missing).
 * Sizes mirror DB column lengths defined in the P1 migration.
 */
public record VocabWordRequest(
        @NotNull Long deckId,
        @NotBlank @Size(max = 120) String term,
        @NotBlank @Size(max = 255) String meaningVi,
        @Size(max = 120) String ipa,
        @Size(max = 500) String exampleEn,
        @Size(max = 500) String exampleVi,
        @Size(max = 40) String partOfSpeech,
        boolean verified
) {}
