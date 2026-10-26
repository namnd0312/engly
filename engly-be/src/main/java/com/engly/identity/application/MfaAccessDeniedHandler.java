package com.engly.identity.application;

import com.engly.identity.infrastructure.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Turns the admin step-up denial into a 403 the SPA can act on. The decision itself is made by the
 * {@code /api/admin/**} authorization rule (ROLE_ADMIN AND {@code mfa=true}) in {@code SecurityConfig},
 * which matches the same normalized path as every other rule — so encoded variants cannot slip past.
 *
 * <p>An authenticated admin JWT without {@code mfa=true} can only be denied by that rule (all other
 * rules are permitAll/authenticated), so it gets {@code MFA_ENROLLMENT_REQUIRED} or {@code MFA_REQUIRED}.
 * Every other denial (non-admins, CSRF failures) keeps the default bare 403.
 */
public class MfaAccessDeniedHandler implements AccessDeniedHandler {

    static final String CODE_REQUIRED = "MFA_REQUIRED";
    static final String CODE_ENROLLMENT_REQUIRED = "MFA_ENROLLMENT_REQUIRED";

    private final MfaService mfaService;
    private final UserAccountRepository users;
    private final ObjectMapper objectMapper;

    public MfaAccessDeniedHandler(MfaService mfaService, UserAccountRepository users, ObjectMapper objectMapper) {
        this.mfaService = mfaService;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException denied) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (denied instanceof CsrfException
                || !(auth instanceof JwtAuthenticationToken jwtAuth)
                || !isAdmin(jwtAuth)
                || MfaClaims.isVerified(jwtAuth.getToken())) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }
        boolean enrolled = users.findBySub(jwtAuth.getToken().getSubject())
                .map(user -> mfaService.isEnrolled(user.getId()))
                .orElse(false);
        writeCoded(response, enrolled ? CODE_REQUIRED : CODE_ENROLLMENT_REQUIRED);
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** Same body shape as GlobalExceptionHandler's coded responses. */
    private void writeCoded(HttpServletResponse response, String code) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.FORBIDDEN.value(),
                "error", HttpStatus.FORBIDDEN.getReasonPhrase(),
                "message", "Second factor required",
                "code", code));
    }
}
