package com.engly.vocab.imports.domain;

/**
 * A conflicting import request — e.g. creating a job from an upload that isn't complete, or
 * retrying a job that isn't FAILED. Mapped to HTTP 409 by GlobalExceptionHandler.
 */
public class VocabImportConflictException extends RuntimeException {

    public VocabImportConflictException(String message) {
        super(message);
    }
}
