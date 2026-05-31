package com.engly.verb.api.dto;

/**
 * Admin-facing verb DTO. Unlike the public {@link VerbResponse}, it exposes the numeric {@code id}
 * (stable admin identifier — base_form is editable). Field names match the FE admin model.
 */
public record VerbAdminResponse(
        Long id,
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
