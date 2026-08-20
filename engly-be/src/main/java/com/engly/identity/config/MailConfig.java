package com.engly.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registration point for {@link EmailVerificationProperties} — there is no central
 * {@code @ConfigurationPropertiesScan}, each config class self-registers its own properties (see
 * {@code JwtKeyConfig}, {@code S3ClientConfig}, {@code SocialLoginClientsConfig}). Kept separate
 * from {@code JwtKeyConfig} since mail/activation config is a distinct concern from JWT signing.
 */
@Configuration
@EnableConfigurationProperties(EmailVerificationProperties.class)
public class MailConfig {
}
