package com.engly.identity.domain;

/** Too many failed TOTP attempts → HTTP 429 MFA_LOCKED until the lock expires. */
public class MfaLockedException extends RuntimeException {
    public MfaLockedException() {
        super("Too many attempts, try again later");
    }
}
