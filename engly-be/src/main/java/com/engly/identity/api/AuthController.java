package com.engly.identity.api;

import com.engly.identity.api.dto.LoginRequest;
import com.engly.identity.api.dto.RegisterRequest;
import com.engly.identity.api.dto.RegisterResponse;
import com.engly.identity.api.dto.ResendVerificationRequest;
import com.engly.identity.api.dto.UserInfoResponse;
import com.engly.identity.api.dto.VerifyEmailRequest;
import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.EmailVerificationService;
import com.engly.identity.application.UserAccountService;
import com.engly.identity.domain.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App-owned authentication API. Issues the app's own JWT session (access + refresh) as httpOnly
 * cookies — OPEN-GATE is no longer the token issuer (it remains only an SSO option, handled by the
 * oauth2Login chain). All endpoints are permitAll; auth is established by the cookies they set.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAccountService accountService;
    private final AuthTokenService tokenService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(UserAccountService accountService, AuthTokenService tokenService,
                          EmailVerificationService emailVerificationService) {
        this.accountService = accountService;
        this.tokenService = tokenService;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Self-service sign-up. Hard gate (default): creates the account, sends the activation email,
     * and issues NO session — 202 + email only. Rollback switch
     * ({@code app.email-verification.enabled=false}): auto-verifies and issues a session immediately,
     * i.e. the pre-feature behavior.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody RegisterRequest req, HttpServletResponse response) {
        UserAccount user = accountService.register(req.email(), req.password(), req.displayName());
        if (!emailVerificationService.isEnabled()) {
            emailVerificationService.markVerifiedImmediately(user);
            tokenService.issueSession(user, response);
            return ResponseEntity.ok(UserInfoResponse.from(user));
        }
        emailVerificationService.startVerification(user);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RegisterResponse(user.getEmail()));
    }

    /**
     * Password login → starts a session. Generic 401 on unknown email/bad password; 403 with
     * {@code code=EMAIL_NOT_VERIFIED} once credentials are correct but the account is unactivated.
     */
    @PostMapping("/login")
    public ResponseEntity<UserInfoResponse> login(
            @Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        UserAccount user = accountService.login(req.email(), req.password());
        tokenService.issueSession(user, response);
        return ResponseEntity.ok(UserInfoResponse.from(user));
    }

    /**
     * Consume the emailed activation token and start a session. Login-CSRF guard: this endpoint is
     * CSRF-exempt and session-creating with a caller-supplied (forgeable) token, so it must never
     * silently replace an ALREADY authenticated session — reject with 409 instead. The SPA only
     * calls this while anonymous (see the verify-email page's authenticated short-circuit).
     */
    @PostMapping("/verify-email")
    public ResponseEntity<UserInfoResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest req, HttpServletResponse response,
            Authentication authentication) {
        if (isAuthenticatedUser(authentication)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        UserAccount user = emailVerificationService.verify(req.token());
        tokenService.issueSession(user, response);
        return ResponseEntity.ok(UserInfoResponse.from(user));
    }

    /** Always 202 regardless of input — no account-existence enumeration. */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        emailVerificationService.resend(req.email());
        return ResponseEntity.accepted().build();
    }

    private static boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /** Rotate the refresh cookie and mint a fresh access cookie. 401 if the refresh cookie is bad. */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        tokenService.rotate(request, response);
        return ResponseEntity.ok().build();
    }

    /** Revoke the refresh token and clear both cookies. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        tokenService.clearSession(request, response);
        return ResponseEntity.ok().build();
    }
}
