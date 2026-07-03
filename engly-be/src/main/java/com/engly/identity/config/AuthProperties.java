package com.engly.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the app's own JWT auth (bound from {@code app.security.jwt.*}).
 *
 * <p>RSA keys (PEM) are optional: when absent the app generates an ephemeral keypair at startup
 * (fine for single-instance dev — tokens drop on restart). Provide real keys via env/secret for
 * any multi-instance or production deployment.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record AuthProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String privateKey,
        String publicKey,
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
