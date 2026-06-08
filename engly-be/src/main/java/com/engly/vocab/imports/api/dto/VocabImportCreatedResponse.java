package com.engly.vocab.imports.api.dto;

/** Returned by create (201) and retry (200): the job id + its (new) status. */
public record VocabImportCreatedResponse(
        long jobId,
        String status
) {}
