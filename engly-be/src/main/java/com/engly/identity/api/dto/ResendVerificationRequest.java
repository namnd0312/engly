package com.engly.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Payload for POST /api/auth/resend-verification. Always answered generically (202). */
public record ResendVerificationRequest(@NotBlank @Email String email) {
}
