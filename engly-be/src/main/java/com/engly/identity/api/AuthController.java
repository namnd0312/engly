package com.engly.identity.api;

import com.engly.identity.api.dto.LoginRequest;
import com.engly.identity.api.dto.RegisterRequest;
import com.engly.identity.api.dto.UserInfoResponse;
import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.UserAccountService;
import com.engly.identity.domain.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

    public AuthController(UserAccountService accountService, AuthTokenService tokenService) {
        this.accountService = accountService;
        this.tokenService = tokenService;
    }

    /** Self-service sign-up → creates the account and starts a session. */
    @PostMapping("/register")
    public ResponseEntity<UserInfoResponse> register(
            @Valid @RequestBody RegisterRequest req, HttpServletResponse response) {
        UserAccount user = accountService.register(req.email(), req.password(), req.displayName());
        tokenService.issueSession(user, response);
        return ResponseEntity.ok(UserInfoResponse.from(user));
    }

    /** Password login → starts a session. Generic 401 on any credential failure. */
    @PostMapping("/login")
    public ResponseEntity<UserInfoResponse> login(
            @Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        UserAccount user = accountService.login(req.email(), req.password());
        tokenService.issueSession(user, response);
        return ResponseEntity.ok(UserInfoResponse.from(user));
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
