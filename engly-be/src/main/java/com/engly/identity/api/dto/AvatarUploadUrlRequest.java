package com.engly.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request a presigned S3 PUT for an avatar upload (content type is validated + signed). */
public record AvatarUploadUrlRequest(
        @NotBlank @Size(max = 100) String contentType) {
}
