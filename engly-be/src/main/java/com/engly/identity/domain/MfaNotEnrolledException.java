package com.engly.identity.domain;

/** Operation needs a confirmed TOTP enrollment the user does not have → HTTP 409 MFA_NOT_ENROLLED. */
public class MfaNotEnrolledException extends RuntimeException {
    public MfaNotEnrolledException() {
        super("Two-factor authentication is not enabled");
    }
}
