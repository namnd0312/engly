package com.engly.identity.application;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The access-JWT claim that records a completed TOTP step-up. Absent means false. */
public final class MfaClaims {
    public static final String CLAIM = "mfa";

    private MfaClaims() {}

    public static boolean isVerified(Jwt jwt) {
        return jwt != null && Boolean.TRUE.equals(jwt.getClaim(CLAIM));
    }

    /** Authorization rule: the caller's app JWT carries {@code mfa=true}. */
    public static AuthorizationManager<RequestAuthorizationContext> verifiedSession() {
        return (authentication, context) -> new AuthorizationDecision(
                authentication.get() instanceof JwtAuthenticationToken jwtAuth && isVerified(jwtAuth.getToken()));
    }
}
