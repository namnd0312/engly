package com.engly.vocab.api.dto;

/**
 * Read-only DTO returned by all vocab-word admin endpoints.
 * deckId is flattened from the lazy-loaded VocabDeck association.
 */
public record VocabWordResponse(
        Long id,
        Long deckId,
        String term,
        String meaningVi,
        String ipa,
        String exampleEn,
        String exampleVi,
        String partOfSpeech,
        boolean verified
) {}
