package com.engly.identity.application;

import com.engly.identity.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenGateSsoSuccessHandler}: it must provision/link a local user by email,
 * issue the app's own session, and redirect to the SPA — and bounce to an error when the IdP gives
 * no email.
 */
class OpenGateSsoSuccessHandlerTest {

    private static final String FRONTEND = "http://localhost:4200/";

    private UserAccountService accountService;
    private AuthTokenService tokenService;
    private OpenGateSsoSuccessHandler handler;

    @BeforeEach
    void setUp() {
        accountService = mock(UserAccountService.class);
        tokenService = mock(AuthTokenService.class);
        handler = new OpenGateSsoSuccessHandler(accountService, tokenService, FRONTEND);
    }

    private static OidcUser oidcUser(Map<String, Object> claims) {
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(),
                Instant.now().plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    }

    @Test
    void onSuccess_withEmail_provisionsUserIssuesSessionAndRedirectsToSpa() throws Exception {
        OidcUser principal = oidcUser(Map.of("sub", "og-1", "email", "sso@example.com", "name", "SSO User"));
        when(accountService.provisionSsoUser("og-1", "sso@example.com", "SSO User"))
                .thenReturn(UserAccount.ssoUser("og-1", "sso@example.com", "SSO User"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new TestingAuthenticationToken(principal, null));

        verify(accountService).provisionSsoUser("og-1", "sso@example.com", "SSO User");
        verify(tokenService).issueSession(any(UserAccount.class), eq(response));
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND);
    }

    @Test
    void onSuccess_missingEmail_redirectsWithErrorAndIssuesNoSession() throws Exception {
        OidcUser principal = oidcUser(Map.of("sub", "og-2", "name", "No Email"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new TestingAuthenticationToken(principal, null));

        assertThat(response.getRedirectedUrl()).contains("ssoError=missing_email");
        verify(accountService, never()).provisionSsoUser(any(), any(), any());
        verify(tokenService, never()).issueSession(any(), any());
    }
}
