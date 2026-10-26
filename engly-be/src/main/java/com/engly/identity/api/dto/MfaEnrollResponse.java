package com.engly.identity.api.dto;

/** Enrollment material for the SPA: Base32 secret for manual entry and the otpauth URI for the QR. */
public record MfaEnrollResponse(String secret, String otpauthUri) {}
