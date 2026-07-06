package com.engly.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the app's own JWT auth (bound from {@code app.security.jwt.*}).
 *
 * <p>Tokens are signed with HS256 (HMAC-SHA256) using a single shared {@code secret}. The secret is
 * optional: when absent the app generates an ephemeral random key at startup (fine for
 * single-instance dev — tokens drop on restart). Provide a fixed secret via env/secret
 * ({@code JWT_SECRET}) for any multi-instance or production deployment.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record AuthProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String secret,
        Cookie cookie) {

    public AuthProperties {
        if (issuer == null || issuer.isBlank()) issuer = "engly";
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
        if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(30);
        if (cookie == null) cookie = new Cookie(null, null, null, false, null);
    }

    /** httpOnly cookie settings for the access + refresh tokens. */
    public record Cookie(String accessName, String refreshName, String refreshPath,
                         boolean secure, String sameSite) {
        public Cookie {
            if (accessName == null || accessName.isBlank()) accessName = "ACCESS_TOKEN";
            if (refreshName == null || refreshName.isBlank()) refreshName = "REFRESH_TOKEN";
            if (refreshPath == null || refreshPath.isBlank()) refreshPath = "/api/auth";
            if (sameSite == null || sameSite.isBlank()) sameSite = "Lax";
        }
    }
}
