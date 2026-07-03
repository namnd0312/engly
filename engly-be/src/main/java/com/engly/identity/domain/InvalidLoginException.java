package com.engly.identity.domain;

/**
 * Thrown for a failed password login or an invalid/expired refresh token → mapped to HTTP 401.
 * The message is intentionally generic so it never reveals whether the email exists.
 */
public class InvalidLoginException extends RuntimeException {
    public InvalidLoginException(String message) {
        super(message);
    }
}
