package com.engly.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Self-service profile edit (PATCH /api/profile). */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 255) String displayName) {
}
