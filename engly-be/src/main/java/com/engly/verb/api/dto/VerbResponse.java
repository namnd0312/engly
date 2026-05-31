package com.engly.verb.api.dto;

/**
 * API response DTO for a single irregular verb.
 * Excludes internal fields (id, createdAt) to keep contract stable.
 */
public record VerbResponse(
        String baseForm,
        String pastSimple,
        String pastSimpleAlt,
        String pastParticiple,
        String pastParticipleAlt,
        String ipaBase,
        String ipaPast,
        String ipaPp,
        String meaningVi,
        boolean verified
) {}
