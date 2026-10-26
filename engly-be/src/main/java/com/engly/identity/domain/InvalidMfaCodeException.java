package com.engly.identity.domain;

/** Submitted TOTP code does not match (or was already used) → HTTP 400 INVALID_MFA_CODE. */
public class InvalidMfaCodeException extends RuntimeException {
    public InvalidMfaCodeException() {
        super("Invalid verification code");
    }
}
