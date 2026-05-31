package com.engly.verb.domain;

/**
 * Thrown when a verb lookup by baseForm yields no result.
 * Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class VerbNotFoundException extends RuntimeException {

    public VerbNotFoundException(String baseForm) {
        super("Verb not found: " + baseForm);
    }
}
