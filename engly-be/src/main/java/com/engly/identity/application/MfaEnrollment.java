package com.engly.identity.application;

/** What the SPA needs to show the enrollment QR: the Base32 secret (manual entry) and the otpauth URI. */
public record MfaEnrollment(String secretBase32, String otpauthUri) {}
