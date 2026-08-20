package com.engly.identity.domain;

/** Thrown when an activation token is unknown, expired, or already used → mapped to HTTP 400. */
public class InvalidVerificationTokenException extends RuntimeException {
    public InvalidVerificationTokenException() {
        super("Invalid or expired verification token");
    }
}
