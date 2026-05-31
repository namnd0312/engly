package com.engly.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/verbs/**").permitAll()
                .requestMatchers("/oauth2/**", "/login/**").permitAll()
                .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                .requestMatchers("/api/me").authenticated()
                .anyRequest().permitAll())
            .oauth2Login(oauth2 -> oauth2.defaultSuccessUrl(frontendUrl, true))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl(frontendUrl)
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")));
        return http.build();
    }
}
