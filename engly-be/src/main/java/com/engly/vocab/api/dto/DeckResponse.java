package com.engly.vocab.api.dto;

/**
 * Read-only DTO returned by all deck admin endpoints.
 * Exposes all mutable fields plus id for client-side navigation.
 */
public record DeckResponse(
        Long id,
        String name,
        String slug,
        String description,
        String topic
) {}
