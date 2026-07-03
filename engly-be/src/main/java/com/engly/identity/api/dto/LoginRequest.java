package com.engly.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Password login payload. */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password) {
}
