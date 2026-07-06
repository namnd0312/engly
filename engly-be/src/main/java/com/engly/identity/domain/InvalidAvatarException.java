package com.engly.identity.domain;

/**
 * Rejected avatar operation: disallowed content type, malformed or foreign object key,
 * or confirming a key that was never uploaded. Mapped to 400 by the global handler.
 */
public class InvalidAvatarException extends RuntimeException {
    public InvalidAvatarException(String message) {
        super(message);
    }
}
