package com.engly.identity.api;

import com.engly.identity.api.dto.UserInfoResponse;
import com.engly.identity.application.ProfileService;
import com.engly.identity.infrastructure.UserAccountRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity projection for the SPA. Auth comes from the app's own access JWT (validated by the
 * resource server from the access cookie); profile fields come from the DB row so edits and the
 * avatar (presigned S3 URLs) are visible without re-login. Falls back to claims if the row is
 * missing (defensive: pre-migration tokens / SSO edge cases). Security restricts {@code /api/me}
 * to authenticated requests, so an anonymous call is rejected with 401 before reaching this method.
 */
@RestController
public class SessionController {

    private final UserAccountRepository users;
    private final ProfileService profileService;

    public SessionController(UserAccountRepository users, ProfileService profileService) {
        this.users = users;
        this.profileService = profileService;
    }

    @GetMapping("/api/me")
    public UserInfoResponse me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return users.findBySub(jwt.getSubject())
                .map(user -> profileService.toUserInfo(user, isAdmin))
                .orElseGet(() -> new UserInfoResponse(
                        jwt.getClaimAsString("email"),
                        jwt.getClaimAsString("name"),
                        isAdmin,
                        null,
                        null));
    }
}
