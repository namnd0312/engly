package com.engly.vocab.imports.domain;

/**
 * Thrown when a vocab_import_job lookup by id yields no result.
 * Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class VocabImportJobNotFoundException extends RuntimeException {

    public VocabImportJobNotFoundException(Long id) {
        super("Vocab import job not found: " + id);
    }
}
