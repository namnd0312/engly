package com.engly.identity.api;

import com.engly.identity.api.dto.AvatarConfirmRequest;
import com.engly.identity.api.dto.AvatarUploadUrlRequest;
import com.engly.identity.api.dto.AvatarUploadUrlResponse;
import com.engly.identity.api.dto.UpdateProfileRequest;
import com.engly.identity.api.dto.UserInfoResponse;
import com.engly.identity.application.MfaClaims;
import com.engly.identity.application.MfaService;
import com.engly.identity.application.ProfileService;
import com.engly.identity.domain.UserAccount;
import com.engly.identity.infrastructure.S3AvatarStorage.PresignedUpload;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service profile endpoints. All routes require authentication (SecurityConfig) and are
 * CSRF-protected (state-changing, cookie-auth'd — the SPA echoes X-XSRF-TOKEN automatically).
 *
 * <p>Avatar upload never passes through this server: the browser PUTs directly to S3 with the
 * presigned URL from {@code /avatar/upload-url}, then calls {@code /avatar/confirm}.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final MfaService mfaService;

    public ProfileController(ProfileService profileService, MfaService mfaService) {
        this.profileService = profileService;
        this.mfaService = mfaService;
    }

    /** Full identity projection: role from the authorities, MFA flags from DB + the session claim. */
    private UserInfoResponse userInfo(UserAccount user, Jwt jwt, Authentication authentication) {
        return profileService.toUserInfo(user, isAdmin(authentication),
                mfaService.isEnrolled(user.getId()), MfaClaims.isVerified(jwt));
    }

    @PatchMapping
    public UserInfoResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                          Authentication authentication,
                                          @Valid @RequestBody UpdateProfileRequest request) {
        UserAccount user = profileService.updateDisplayName(jwt.getSubject(), request.displayName());
        return userInfo(user, jwt, authentication);
    }

    @PostMapping("/avatar/upload-url")
    public AvatarUploadUrlResponse createUploadUrl(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody AvatarUploadUrlRequest request) {
        PresignedUpload upload = profileService.createAvatarUploadUrl(jwt.getSubject(), request.contentType());
        return new AvatarUploadUrlResponse(upload.url(), upload.objectKey(), upload.expiresAt());
    }

    @PostMapping("/avatar/confirm")
    public UserInfoResponse confirmAvatar(@AuthenticationPrincipal Jwt jwt,
                                          Authentication authentication,
                                          @Valid @RequestBody AvatarConfirmRequest request) {
        UserAccount user = profileService.confirmAvatar(jwt.getSubject(), request.objectKey());
        return userInfo(user, jwt, authentication);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
