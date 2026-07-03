package com.engly.common;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Shape-tolerant parsing of a JWT roles claim into Spring authority names. Shared by the app-JWT
 * authorities converter in {@link SecurityConfig}.
 *
 * <p>Roles are stored locally already in Spring form (e.g. {@code ["ROLE_ADMIN","ROLE_USER"]}), so
 * {@link #toAuthority} must NOT double-prefix — bare roles get {@code ROLE_}, already-prefixed ones
 * are used as-is.
 *
 * <p>(Originally mapped OPEN-GATE id_token roles; OPEN-GATE is now only an SSO source and roles come
 * from the local DB, but the same tolerant parsing is retained for the app's own roles claim.)
 */
public final class OidcRoleMappingConfig {

    private OidcRoleMappingConfig() {}

    /**
     * Normalize a raw role into a Spring authority name. Roles may already be {@code ROLE_}-prefixed,
     * so prepending unconditionally would yield {@code ROLE_ROLE_ADMIN} and break {@code hasRole}.
     */
    public static String toAuthority(String role) {
        String upper = role.trim().toUpperCase();
        return upper.startsWith("ROLE_") ? upper : "ROLE_" + upper;
    }

    /**
     * Tolerant role extraction — accept a JSON array (List/Collection) or a space/comma-delimited
     * string. Any other shape yields no roles (fail-closed: missing authority rather than a crash).
     */
    public static List<String> extractRoles(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split("[ ,]+"))
                    .filter(part -> !part.isBlank())
                    .toList();
        }
        return List.of();
    }
}
