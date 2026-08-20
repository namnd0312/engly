package com.engly.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Shared SHA-256 hashing for opaque tokens (refresh tokens, email verification tokens). Only the
 * hash is persisted; the raw value lives solely in the cookie/emailed link.
 */
final class TokenHashing {

    private TokenHashing() {}

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
