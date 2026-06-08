package com.engly.vocab.domain;

/**
 * Thrown when a VocabWord lookup by id yields no result.
 * Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class VocabWordNotFoundException extends RuntimeException {

    public VocabWordNotFoundException(Long id) {
        super("Vocab word not found: " + id);
    }
}
