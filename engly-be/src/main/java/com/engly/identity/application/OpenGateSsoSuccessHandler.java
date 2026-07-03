package com.engly.identity.application;

import com.engly.identity.domain.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Bridges an OPEN-GATE OIDC login into the app's OWN session: provision/link a local user by email
 * (keeping the existing sub), issue the app's access + refresh cookies, then redirect to the SPA.
 * OPEN-GATE's tokens are never handed to the browser — it is purely an identity source for SSO.
 */
@Component
public class OpenGateSsoSuccessHandler implements AuthenticationSuccessHandler {

    private final UserAccountService accountService;
    private final AuthTokenService tokenService;
    private final String frontendUrl;

    public OpenGateSsoSuccessHandler(UserAccountService accountService, AuthTokenService tokenService,
                                     @Value("${app.frontend-url:http://localhost:4200/}") String frontendUrl) {
        this.accountService = accountService;
        this.tokenService = tokenService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof OidcUser oidcUser) || isBlank(oidcUser.getEmail())) {
            // No usable email → cannot link an account. Bounce to the SPA with an error flag.
            response.sendRedirect(UriComponentsBuilder.fromUriString(frontendUrl)
                    .replaceQuery("ssoError=missing_email").build().toUriString());
            return;
        }
        UserAccount user = accountService.provisionSsoUser(
                oidcUser.getSubject(), oidcUser.getEmail(), oidcUser.getFullName());
        tokenService.issueSession(user, response);
        response.sendRedirect(frontendUrl);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
