package com.engly.verb.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update request body. Sizes mirror the DB column lengths
 * (base/past/pp/*_alt/ipa_* = 100; meaning_vi = 255). meaning_vi is required
 * to honor the DB NOT NULL constraint even though the entity column omits it.
 */
public record VerbRequest(
        @NotBlank @Size(max = 100) String baseForm,
        @NotBlank @Size(max = 100) String pastSimple,
        @NotBlank @Size(max = 100) String pastParticiple,
        @Size(max = 100) String pastSimpleAlt,
        @Size(max = 100) String pastParticipleAlt,
        @Size(max = 100) String ipaBase,
        @Size(max = 100) String ipaPast,
        @Size(max = 100) String ipaPp,
        @NotBlank @Size(max = 255) String meaningVi,
        boolean verified
) {}
