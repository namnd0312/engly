package com.engly.vocab.imports.processing;

import com.engly.vocab.imports.domain.ParsedRow;

/**
 * Outcome of parsing one CSV line: either a valid {@link ParsedRow} or a bad-row reason.
 * Avoids per-bad-row exceptions on the hot path.
 */
public record CsvParseResult(ParsedRow row, String error) {

    public boolean ok() {
        return error == null;
    }

    static CsvParseResult ok(ParsedRow row) {
        return new CsvParseResult(row, null);
    }

    static CsvParseResult bad(String reason) {
        return new CsvParseResult(null, reason);
    }
}
