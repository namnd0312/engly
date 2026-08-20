package com.engly.identity.api.dto;

/** Response for a successful registration when the activation gate is enabled — no session yet. */
public record RegisterResponse(String email) {
}
