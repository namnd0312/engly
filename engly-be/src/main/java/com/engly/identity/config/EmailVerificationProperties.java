package com.engly.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Email-activation-on-registration config, bound from {@code app.email-verification.*}.
 *
 * <p>{@code enabled} is the rollback kill-switch: when false, registration auto-verifies and issues
 * a session immediately (today's pre-feature behavior) and login is not gated on verification — lets
 * ops revert the hard gate without a redeploy if SMTP breaks in prod.
 */
@ConfigurationProperties(prefix = "app.email-verification")
public record EmailVerificationProperties(
        boolean enabled,
        Duration tokenTtl,
        Duration resendCooldown,
        String fromAddress,
        String verifyPath) {

    public EmailVerificationProperties {
        if (tokenTtl == null) tokenTtl = Duration.ofHours(24);
        if (resendCooldown == null) resendCooldown = Duration.ofSeconds(60);
        if (verifyPath == null || verifyPath.isBlank()) verifyPath = "/verify-email";
    }
}
