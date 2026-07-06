package com.engly.identity.api.dto;

import java.time.Instant;

/** Presigned PUT the browser uploads the avatar to, plus the key to confirm afterwards. */
public record AvatarUploadUrlResponse(String uploadUrl, String objectKey, Instant expiresAt) {
}
