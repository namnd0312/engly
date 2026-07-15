package com.engly.identity.application;

import com.engly.identity.domain.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Bridges a completed Google/Facebook handshake into the app's own session: resolve/link the
 * {@link UserAccount}, mint the app JWT cookies via {@link AuthTokenService} (identical to password
 * login), drop the throwaway OAuth handshake session, and redirect to the SPA.
 *
 * <p>The redirect target is always the server-side {@code app.frontend-url} — never a request-supplied
 * value — so this cannot become an open redirect.
 */
@Component
public class SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final SocialLoginService socialLoginService;
    private final AuthTokenService authTokenService;
    private final String frontendUrl;

    public SsoAuthenticationSuccessHandler(SocialLoginService socialLoginService,
                                           AuthTokenService authTokenService,
                                           @Value("${app.frontend-url}") String frontendUrl) {
        this.socialLoginService = socialLoginService;
        this.authTokenService = authTokenService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String provider = token.getAuthorizedClientRegistrationId();
        OAuth2User principal = token.getPrincipal();

        UserAccount user = socialLoginService.resolveOrLink(provider, principal);
        authTokenService.issueSession(user, response);

        // The OAuth handshake session existed only to carry the authorization request; discard it so
        // the app stays effectively stateless once the JWT cookies are set.
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        response.sendRedirect(homeUrl());
    }

    private String homeUrl() {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return base + "/";
    }
}
