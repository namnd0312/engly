package com.engly.verb.api.dto;

/** Body for PATCH /api/admin/verbs/{id}/verified. */
public record VerbVerifiedRequest(boolean verified) {}
