package com.engly.vocab.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update request body for a VocabDeck.
 * Slug must be lowercase-kebab (URL-safe); sizes mirror DB column lengths.
 * slug is immutable after creation — PUT ignores it (service rejects silently via update()).
 */
public record DeckRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 140) @Pattern(regexp = "[a-z0-9-]+",
                message = "slug must be lowercase letters, digits, or hyphens") String slug,
        @Size(max = 500) String description,
        @Size(max = 80) String topic
) {}
