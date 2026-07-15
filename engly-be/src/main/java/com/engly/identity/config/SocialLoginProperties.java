package com.engly.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Social-login provider credentials, bound from {@code app.social-login.*} (env-driven). Blank when a
 * provider is not configured; {@link SocialLoginClientsConfig} includes only the providers whose
 * client-id is present.
 */
@ConfigurationProperties(prefix = "app.social-login")
public record SocialLoginProperties(
        String googleClientId,
        String googleClientSecret,
        String facebookClientId,
        String facebookClientSecret,
        String facebookApiVersion) {

    public SocialLoginProperties {
        if (facebookApiVersion == null || facebookApiVersion.isBlank()) facebookApiVersion = "v19.0";
    }
}
