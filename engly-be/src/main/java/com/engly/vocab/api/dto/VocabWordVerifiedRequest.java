package com.engly.vocab.api.dto;

/**
 * Request body for PATCH /api/admin/vocab-words/{id}/verified.
 * Single boolean field to toggle/set the verified flag.
 */
public record VocabWordVerifiedRequest(boolean verified) {}
