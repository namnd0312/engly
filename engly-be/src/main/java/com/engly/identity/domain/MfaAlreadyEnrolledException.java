package com.engly.identity.domain;

/** Enrollment requested while a confirmed one exists → HTTP 409 MFA_ALREADY_ENROLLED. */
public class MfaAlreadyEnrolledException extends RuntimeException {
    public MfaAlreadyEnrolledException() {
        super("Two-factor authentication is already enabled");
    }
}
