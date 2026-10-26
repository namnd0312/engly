package com.engly.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** A 6-digit TOTP code from the user's authenticator app. */
public record MfaCodeRequest(
        @NotBlank @Pattern(regexp = "\\d{6}", message = "code must be 6 digits") String code) {}
