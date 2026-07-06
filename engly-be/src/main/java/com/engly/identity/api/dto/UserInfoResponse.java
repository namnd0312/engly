package com.engly.identity.api.dto;

import com.engly.identity.domain.UserAccount;

/**
 * Identity projection returned to the SPA by /api/me and the auth endpoints.
 *
 * <p>The internal user id ({@code sub}) is deliberately NOT exposed: {@code email} is the only
 * external identifier the SPA sees. Server-side code that needs the account resolves it from the
 * authenticated token's subject (never from a client-supplied id).
 *
 * <p>{@code avatarUrl} (256px) and {@code avatarThumbUrl} (64px) are short-lived presigned S3
 * GET URLs, or null when the user has no avatar. Auth endpoints return them as null (the SPA's
 * follow-up /api/me fills them in); only ProfileService builds the presigned variants.
 */
public record UserInfoResponse(String email, String name, boolean isAdmin,
                               String avatarUrl, String avatarThumbUrl) {

    public static UserInfoResponse from(UserAccount user) {
        return new UserInfoResponse(user.getEmail(), user.getDisplayName(),
                user.isAdmin(), null, null);
    }
}
