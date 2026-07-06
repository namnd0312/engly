package com.engly.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Confirm a completed avatar upload; the key must be one this user was issued. */
public record AvatarConfirmRequest(
        @NotBlank @Size(max = 512) String objectKey) {
}
