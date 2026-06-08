package com.engly.vocab.imports.domain;

/**
 * Lifecycle of a vocab CSV import job. Mirrors the status CHECK constraint in V5 migration.
 * PENDING → RUNNING → (COMPLETED | FAILED). FAILED can be reset to PENDING via retry (resumes).
 */
public enum ImportJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
