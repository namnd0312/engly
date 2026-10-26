package com.engly.identity.domain;

/** Stored TOTP secret cannot be decrypted (encryption key changed) → HTTP 503 MFA_UNAVAILABLE. */
public class MfaUnavailableException extends RuntimeException {
    public MfaUnavailableException() {
        super("Two-factor authentication is temporarily unavailable. Contact the administrator.");
    }
}
