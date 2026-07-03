package com.engly.identity.domain;

/** Thrown when registering with an email that already has an account → mapped to HTTP 409. */
public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("An account with this email already exists");
    }
}
