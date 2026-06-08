package com.engly.vocab.imports.domain;

/**
 * A successfully parsed + validated CSV data row, ready for batch upsert into vocab_word.
 * Produced by the CSV parser, consumed by the batch upserter. `verified` is NOT here —
 * it is always written as literal false (never settable via CSV, per locked design).
 */
public record ParsedRow(
        String term,
        String meaningVi,
        String ipa,
        String exampleEn,
        String exampleVi,
        String partOfSpeech
) {}
