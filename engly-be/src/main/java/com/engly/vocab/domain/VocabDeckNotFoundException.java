package com.engly.vocab.domain;

/**
 * Thrown when a VocabDeck lookup by id or slug yields no result.
 * Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class VocabDeckNotFoundException extends RuntimeException {

    public VocabDeckNotFoundException(Long id) {
        super("Vocab deck not found: " + id);
    }

    public VocabDeckNotFoundException(String slug) {
        super("Vocab deck not found for slug: " + slug);
    }
}
