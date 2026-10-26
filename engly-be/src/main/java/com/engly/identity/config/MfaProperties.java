package com.engly.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for admin two-factor auth (bound from {@code app.security.mfa.*}).
 *
 * <p>{@code secretKey} protects the stored TOTP secrets (AES-256-GCM, key derived via SHA-256 — any
 * length input). It is optional: when absent an ephemeral key is generated at startup, which means
 * after a restart enrolled admins are locked out until a DB reset (dev only). Provide a fixed value via env
 * ({@code MFA_SECRET_KEY}) for any persistent deployment.
 */
@ConfigurationProperties(prefix = "app.security.mfa")
public record MfaProperties(
        String secretKey,
        String issuer,
        Integer digits,
        Integer periodSeconds,
        Integer window) {
    public MfaProperties {
        if (issuer == null || issuer.isBlank()) issuer = "Engly";
        if (digits == null || digits <= 0) digits = 6;
        if (periodSeconds == null || periodSeconds <= 0) periodSeconds = 30;
        if (window == null || window < 0) window = 1;
    }
}
