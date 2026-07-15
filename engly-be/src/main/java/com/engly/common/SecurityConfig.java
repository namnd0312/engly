package com.engly.common;

import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.CookieBearerTokenResolver;
import com.engly.identity.application.SsoAuthenticationSuccessHandler;
import com.engly.identity.config.SocialLoginClientsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

/**
 * App-owned JWT security. The app issues its OWN access/refresh tokens (see AuthTokenService),
 * delivered as httpOnly cookies. Authentication is email/password only (no external SSO).
 *
 * <p>A single stateless chain runs an OAuth2 resource server that validates the app's own JWT,
 * read from the access cookie (or Authorization header). Roles come from the JWT's {@code roles}
 * claim, which is sourced from the local DB at token-mint time.
 *
 * <p>CSRF stays enabled because the access token lives in a browser-sent cookie: cookie-based JWT
 * auth needs CSRF protection for state-changing requests (login, logout, study writes). The SPA
 * echoes the XSRF-TOKEN cookie as X-XSRF-TOKEN.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** App JWT claim carrying the user's roles (Spring-form names, e.g. ROLE_ADMIN). */
    @Value("${app.security.jwt.roles-claim-name:roles}")
    private String rolesClaimName;

    /** SPA origin the social-login handshake redirects back to (success and failure). */
    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Social-login (Google/Facebook) handshake chain. Present only when at least one provider is
     * configured (a {@link ClientRegistrationRepository} bean exists — see {@code SocialLoginClientsConfig}).
     *
     * <p>Scoped with {@code securityMatcher} to ONLY the OAuth2 endpoints so it never touches
     * {@code /api/**} (which stays on the stateless chain below). Unlike that chain this one allows a
     * session ({@code IF_REQUIRED}) — Spring must persist the authorization request + {@code state}
     * between the outbound redirect and the callback. CSRF is disabled here because these endpoints are
     * provider-driven and the OAuth2 {@code state} parameter is the callback's forgery defense. On
     * success {@link SsoAuthenticationSuccessHandler} mints the app JWT cookies and the session is
     * discarded, so the app is effectively stateless again for all subsequent API calls.
     *
     * <p>Gated on the same provider-configured condition as the {@code ClientRegistrationRepository} bean
     * so the two are consistent regardless of config-class ordering.
     */
    @Bean
    @Order(1)
    @ConditionalOnExpression(SocialLoginClientsConfig.ANY_PROVIDER_CONFIGURED)
    public SecurityFilterChain oauth2LoginFilterChain(
            HttpSecurity http, SsoAuthenticationSuccessHandler successHandler) throws Exception {
        http
            .securityMatcher("/oauth2/**", "/login/oauth2/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .oauth2Login(oauth2 -> oauth2
                .successHandler(successHandler)
                .failureUrl(frontendUrl + "/login?error=sso"));
        return http.build();
    }

    /**
     * Stateless resource server for the SPA + API. Validates the app's own JWT (local key)
     * read from the access cookie via {@link CookieBearerTokenResolver}.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, JwtDecoder jwtDecoder, AuthTokenService tokenService) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // Auth cookies are issued by the response itself; these endpoints set/clear the
                // session and run before any XSRF cookie exists on a fresh client.
                .ignoringRequestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh")
                // tus resumable upload is driven by a non-Angular client (no auto X-XSRF-TOKEN) and
                // is already admin-gated; tus mandates a custom Tus-Resumable header, which CORS
                // preflight blocks cross-site, so this path is not CSRF-forgeable.
                .ignoringRequestMatchers("/api/admin/vocab-imports/upload", "/api/admin/vocab-imports/upload/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/verbs/**").permitAll()
                .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/me").authenticated()
                .requestMatchers("/api/profile/**").authenticated()
                .requestMatchers("/api/study/**").authenticated()
                .anyRequest().permitAll())
            .oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(new CookieBearerTokenResolver(tokenService.accessCookieName()))
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /** Maps the app JWT {@code roles} claim → Spring authorities (shape-tolerant, no double-prefix). */
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
     * Spring Security loads the CSRF token lazily — CookieCsrfTokenRepository only writes the
     * XSRF-TOKEN cookie when the token value is read. Safe requests (GET /api/me, the SPA shell)
     * never read it, so the SPA would have no token to echo as X-XSRF-TOKEN. This filter reads the
     * token on every request, forcing the cookie to be issued.
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
