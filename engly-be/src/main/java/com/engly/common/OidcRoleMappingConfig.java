package com.engly.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps an OPEN-GATE OIDC id_token roles claim → Spring Security authorities.
 *
 * <p>The IdP has no /userinfo endpoint, so {@link OidcUserService} builds the principal from
 * the JWK-verified id_token alone. This decorator reads the configurable roles claim and adds
 * a Spring authority per role, which the URL rule {@code /api/admin/** -> hasRole('ADMIN')} then
 * enforces. No DB users/roles involved.
 *
 * <p>OPEN-GATE emits roles already in Spring form (e.g. {@code ["ROLE_ADMIN","ROLE_USER"]}), so
 * {@link #toAuthority} must NOT double-prefix — bare roles get {@code ROLE_}, already-prefixed
 * ones are used as-is.
 */
@Configuration
public class OidcRoleMappingConfig {

    /**
     * Decorating {@link OidcUserService} that augments the delegate's authorities with roles
     * extracted from the id_token. Wired into {@code oauth2Login().userInfoEndpoint(...)}.
     */
    @Bean
    public OidcUserService oidcUserService(
            @Value("${app.security.roles-claim-name:roles}") String rolesClaimName) {
        OidcUserService delegate = new OidcUserService();
        return new OidcUserService() {
            @Override
            public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
                OidcUser user = delegate.loadUser(userRequest);

                Set<GrantedAuthority> authorities = new HashSet<>(user.getAuthorities());
                Object rawRoles = user.getClaims().get(rolesClaimName);
                for (String role : extractRoles(rawRoles)) {
                    authorities.add(new SimpleGrantedAuthority(toAuthority(role)));
                }

                // 3-arg ctor keeps "sub" as the name attribute key (matches user-name-attribute).
                return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo());
            }
        };
    }

    /**
     * Normalize a raw role into a Spring authority. OPEN-GATE already prefixes its roles with
     * {@code ROLE_}, so prepending unconditionally would yield {@code ROLE_ROLE_ADMIN} and break
     * {@code hasRole('ADMIN')}. Bare roles (e.g. "admin") still get the prefix added.
     */
    static String toAuthority(String role) {
        String upper = role.trim().toUpperCase();
        return upper.startsWith("ROLE_") ? upper : "ROLE_" + upper;
    }

    /**
     * Tolerant role extraction — the exact claim shape from OPEN-GATE is unverified, so accept
     * both a JSON array (List/Collection) and a space/comma-delimited string. Any other shape
     * yields no roles (fail-closed: missing admin authority rather than a crash).
     */
    static List<String> extractRoles(Object raw) {
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
