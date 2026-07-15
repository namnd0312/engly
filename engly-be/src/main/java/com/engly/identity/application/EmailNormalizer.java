package com.engly.identity.application;

import java.util.Locale;

/**
 * Canonical email form used for storage and lookup so the same address always maps to the same row.
 * Both the password path and the social-login path normalize through here — otherwise a provider that
 * returns {@code John.Doe@Gmail.com} would miss the stored {@code john.doe@gmail.com} and create a
 * duplicate account.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    /** Trim + lowercase; null-safe (SSO accounts may have no email). */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
