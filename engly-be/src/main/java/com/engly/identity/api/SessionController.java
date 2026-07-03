package com.engly.identity.api;

import com.engly.identity.api.dto.UserInfoResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity projection for the SPA. Sourced from the app's own access JWT (validated by the resource
 * server from the access cookie). Security restricts {@code /api/me} to authenticated requests, so
 * an anonymous call is rejected with 401 before reaching this method.
 */
@RestController
public class SessionController {

    @GetMapping("/api/me")
    public UserInfoResponse me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return new UserInfoResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                isAdmin);
    }
}
