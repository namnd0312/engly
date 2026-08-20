package com.engly.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload for POST /api/auth/verify-email — the raw token from the emailed activation link. */
public record VerifyEmailRequest(@NotBlank String token) {
}
