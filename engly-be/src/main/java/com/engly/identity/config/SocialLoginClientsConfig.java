package com.engly.identity.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the OAuth2 {@link ClientRegistrationRepository} for social login from environment variables,
 * including only providers whose client-id is actually set.
 *
 * <p>Why not the plain {@code spring.security.oauth2.client.registration.*} YAML: with the oauth2-client
 * starter on the classpath, Spring binds any registration keys present and validates them at startup
 * ({@code Assert.hasText(clientId)}). Blank-default env (`${GOOGLE_CLIENT_ID:}`) would therefore crash
 * boot in every environment that hasn't configured SSO yet — taking password login down with it. This
 * bean sidesteps that: when neither provider is configured the whole bean is absent
 * ({@link ConditionalOnExpression}), so no repository exists and the OAuth2 login filter chain
 * ({@code @ConditionalOnBean(ClientRegistrationRepository.class)}) is simply not created.
 *
 * <p>Facebook's {@link CommonOAuth2Provider#FACEBOOK} built-in pins deprecated Graph API v2.8 endpoints;
 * we override them to a current version (configurable via {@code FACEBOOK_API_VERSION}).
 */
@Configuration
@EnableConfigurationProperties(SocialLoginProperties.class)
@ConditionalOnExpression(SocialLoginClientsConfig.ANY_PROVIDER_CONFIGURED)
public class SocialLoginClientsConfig {

    /**
     * True when at least one social provider is configured. Shared with the OAuth2 login filter chain so
     * both are gated by the same condition — using {@code @ConditionalOnBean} on the chain instead would
     * be order-sensitive (it may evaluate before this repository bean is registered) and silently skip
     * the chain even when a provider is configured.
     */
    public static final String ANY_PROVIDER_CONFIGURED =
            "'${GOOGLE_CLIENT_ID:}' != '' or '${FACEBOOK_CLIENT_ID:}' != ''";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(SocialLoginProperties props) {
        List<ClientRegistration> registrations = new ArrayList<>();
        if (StringUtils.hasText(props.googleClientId())) {
            registrations.add(googleRegistration(props));
        }
        if (StringUtils.hasText(props.facebookClientId())) {
            registrations.add(facebookRegistration(props));
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    private ClientRegistration googleRegistration(SocialLoginProperties props) {
        // Google is OIDC: the built-in provides authorization/token/userinfo/jwks URIs and openid scope.
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(props.googleClientId())
                .clientSecret(props.googleClientSecret())
                .build();
    }

    private ClientRegistration facebookRegistration(SocialLoginProperties props) {
        String version = props.facebookApiVersion();
        // Start from the built-in (correct auth method + public_profile,email scopes, userNameAttribute=id),
        // then override the version-pinned endpoints to a supported Graph API version.
        return CommonOAuth2Provider.FACEBOOK.getBuilder("facebook")
                .clientId(props.facebookClientId())
                .clientSecret(props.facebookClientSecret())
                .authorizationUri("https://www.facebook.com/" + version + "/dialog/oauth")
                .tokenUri("https://graph.facebook.com/" + version + "/oauth/access_token")
                .userInfoUri("https://graph.facebook.com/" + version + "/me?fields=id,name,email")
                .build();
    }
}
