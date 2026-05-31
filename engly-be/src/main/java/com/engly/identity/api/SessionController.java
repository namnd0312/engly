package com.engly.identity.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity projection for the SPA (BFF, phase-05).
 *
 * Security restricts {@code /api/me} to authenticated users, so an anonymous request is
 * rejected with 401 upstream and never reaches this method. Identity is sourced from the
 * OIDC id_token claims (the IdP has no /userinfo endpoint).
 */
@RestController
public class SessionController {

    /** Minimal identity projection returned to the browser. */
    public record UserInfoResponse(String sub, String email, String name, boolean isAdmin) {}

    @GetMapping("/api/me")
    public UserInfoResponse me(@AuthenticationPrincipal OidcUser principal) {
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return new UserInfoResponse(
                principal.getSubject(),
                principal.getEmail(),
                principal.getFullName(),
                isAdmin);
    }

    /** Raw id_token (a JWT) so the SPA can call the stateless admin API with Authorization: Bearer. */
    public record TokenResponse(String token) {}

    @GetMapping("/api/token")
    public TokenResponse token(@AuthenticationPrincipal OidcUser principal) {
        return new TokenResponse(principal.getIdToken().getTokenValue());
    }
}
