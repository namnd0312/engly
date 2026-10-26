package com.engly.identity.api;

import com.engly.identity.api.dto.MfaCodeRequest;
import com.engly.identity.api.dto.MfaEnrollResponse;
import com.engly.identity.api.dto.UserInfoResponse;
import com.engly.identity.application.AuthTokenService;
import com.engly.identity.application.MfaEnrollment;
import com.engly.identity.application.MfaService;
import com.engly.identity.domain.InvalidLoginException;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TOTP two-factor endpoints. Enrollment lives under {@code /api/profile/mfa} (authenticated, same as
 * the rest of the profile). The step-up itself is {@code POST /api/auth/mfa/verify}: it sits under
 * {@code /api/auth} because the refresh cookie is scoped to that path and the verified flag is
 * recorded on the refresh token. All four endpoints are CSRF-protected (not in the ignore list).
 */
@RestController
public class MfaController {

    private final MfaService mfaService;
    private final AuthTokenService tokenService;
    private final UserAccountRepository users;

    public MfaController(MfaService mfaService, AuthTokenService tokenService, UserAccountRepository users) {
        this.mfaService = mfaService;
        this.tokenService = tokenService;
        this.users = users;
    }

    /** Start (or restart an unconfirmed) enrollment; returns the secret to scan. 409 if already enabled. */
    @PostMapping("/api/profile/mfa/enroll")
    public MfaEnrollResponse enroll(@AuthenticationPrincipal Jwt jwt) {
        MfaEnrollment enrollment = mfaService.beginEnrollment(currentUser(jwt));
        return new MfaEnrollResponse(enrollment.secretBase32(), enrollment.otpauthUri());
    }

    /** Confirm the scanned secret with a first code → enrollment becomes active. */
    @PostMapping("/api/profile/mfa/confirm")
    public ResponseEntity<Void> confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MfaCodeRequest req) {
        mfaService.confirmEnrollment(currentUser(jwt), req.code());
        return ResponseEntity.noContent().build();
    }

    /** Turn 2FA off (needs a valid code). Live sessions lose the step-up on their next refresh. */
    @DeleteMapping("/api/profile/mfa")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MfaCodeRequest req) {
        mfaService.disable(currentUser(jwt), req.code());
        return ResponseEntity.noContent().build();
    }

    /**
     * Step-up: check the code, then re-mint the access cookie with {@code mfa=true} and flag the
     * refresh token. Returns the identity with both MFA flags set so the SPA can update its state
     * without a follow-up /api/me.
     */
    @PostMapping("/api/auth/mfa/verify")
    public UserInfoResponse verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MfaCodeRequest req,
                                   HttpServletRequest request, HttpServletResponse response) {
        UserAccount user = currentUser(jwt);
        mfaService.verify(user, req.code());
        tokenService.upgradeToMfa(request, response, user);
        return new UserInfoResponse(user.getEmail(), user.getDisplayName(), user.isAdmin(),
                null, null, true, true);
    }

    private UserAccount currentUser(Jwt jwt) {
        return users.findBySub(jwt.getSubject())
                .orElseThrow(() -> new InvalidLoginException("Unknown user"));
    }
}
