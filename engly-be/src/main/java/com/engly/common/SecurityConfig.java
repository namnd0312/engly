package com.engly.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * BFF security (phase-05): OAuth2/OIDC login via the OPEN-GATE IdP. Tokens stay in the
 * server-side authorized-client store; the browser only ever holds an httpOnly session
 * cookie — never an access/refresh token.
 *
 * Authorization rules:
 *  - permitAll: verb lookup, OAuth2 login/callback endpoints, root + static assets.
 *  - authenticated: /api/me (identity projection).
 *
 * Entry points:
 *  - /api/** unauthenticated → 401 (no redirect) so the SPA can stay anonymous gracefully.
 *  - Other protected routes fall back to oauth2Login's 302 redirect (browser-driven login).
 *
 * CSRF: CookieCsrfTokenRepository (httpOnly=false) + plain request handler so Angular's
 * auto-sent X-XSRF-TOKEN (raw cookie value) validates correctly on state-changing requests.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Where the browser lands after login/logout — the SPA origin. The FE and BE are
     * separate origins in dev (Angular :4200 proxies /api → :8081), so a successful OAuth2
     * round-trip must redirect back to the SPA, not to "/" on the backend.
     */
    @Value("${app.frontend-url:http://localhost:4200/}")
    private String frontendUrl;

    /** OIDC/JWT claim carrying the user's roles (OPEN-GATE emits Spring-form names, e.g. ROLE_ADMIN). */
    @Value("${app.security.roles-claim-name:roles}")
    private String rolesClaimName;

    /**
     * Enable PKCE on the outbound authorization request. The OPEN-GATE client is confidential
     * (has a secret) but the IdP also mandates proof-key; Spring only auto-applies PKCE for
     * public clients, so a confidential client must opt in explicitly or the IdP rejects the
     * request with "OAuth 2.0 Parameter: code_challenge".
     */
    private OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository repo) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    /**
     * Chain 1 — stateless OAuth2 Resource Server for the admin API. Every {@code /api/admin/**}
     * request MUST carry a valid {@code Authorization: Bearer <jwt>} (verified against OPEN-GATE's
     * JWK set); no session, no CSRF (irrelevant for bearer auth). The {@code roles} claim is mapped
     * to authorities and {@code ROLE_ADMIN} is required. Used by external clients AND the SPA (which
     * fetches its token from {@code /api/token}). Ordered first so it owns the /api/admin/** matcher.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Maps the JWT {@code roles} claim → Spring authorities, reusing the same shape-tolerant
     * extraction + no-double-prefix normalization as the session login path (single source of truth).
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object raw = jwt.getClaims().get(rolesClaimName);
            Collection<GrantedAuthority> authorities = OidcRoleMappingConfig.extractRoles(raw).stream()
                    .map(OidcRoleMappingConfig::toAuthority)
                    .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                    .toList();
            return authorities;
        });
        return converter;
    }

    /**
     * Chain 2 — session/BFF for everything else: OAuth2 login, identity ({@code /api/me},
     * {@code /api/token}), public verb lookup, logout. CSRF stays on (cookie) for state-changing
     * session requests like POST /logout.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, ClientRegistrationRepository clientRegistrationRepository,
            OidcUserService oidcUserService) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/verbs/**").permitAll()
                .requestMatchers("/oauth2/**", "/login/**").permitAll()
                .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                // /api/token hands the SPA its bearer JWT for admin calls — session-protected.
                .requestMatchers("/api/me", "/api/token").authenticated()
                .anyRequest().permitAll())
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(authz -> authz
                    .authorizationRequestResolver(pkceResolver(clientRegistrationRepository)))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                .defaultSuccessUrl(frontendUrl, true))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl(frontendUrl)
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")))
            // Force the deferred CSRF token to render on every response so the XSRF-TOKEN
            // cookie is actually written (see CsrfCookieFilter). Without it the SPA has no
            // token to echo as X-XSRF-TOKEN and POST /logout fails CSRF with 403.
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Spring Security 6 loads the CSRF token lazily — CookieCsrfTokenRepository only writes the
     * XSRF-TOKEN cookie when the token value is actually read. Safe requests (GET /api/me, the
     * SPA shell) never read it, so the cookie was never set and Angular could not attach the
     * X-XSRF-TOKEN header, breaking the logout POST. This filter reads the token on every
     * request, forcing the cookie to be issued.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
            if (csrfToken != null) {
                csrfToken.getToken(); // triggers the deferred load → cookie write
            }
            filterChain.doFilter(request, response);
        }
    }
}
