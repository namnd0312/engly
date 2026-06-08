package com.engly.vocab.imports.processing;

import com.engly.vocab.imports.domain.ParsedRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Parses ONE physical CSV line (no embedded newlines, per locked design) into a ParsedRow,
 * honouring quotes/escaped commas via Apache Commons CSV. Fixed positional column order:
 *
 *   term, meaning_vi, ipa, example_en, example_vi, part_of_speech[, verified(ignored)]
 *
 * Validation (no silent truncation — over-length = bad row, per validated decision):
 *  - required: term + meaning_vi non-blank.
 *  - each field length-checked against its vocab_word column max.
 * A blank/garbled line → bad row (skipped + reported), never a crash.
 */
@Component
public class CsvRowParser {

    // Column max lengths mirror V3 vocab_word definitions.
    private static final int MAX_TERM = 120;
    private static final int MAX_MEANING = 255;
    private static final int MAX_IPA = 120;
    private static final int MAX_EXAMPLE = 500;
    private static final int MAX_POS = 40;

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT;

    public CsvParseResult parse(String line) {
        if (line == null || line.isBlank()) {
            return CsvParseResult.bad("blank line");
        }
        CSVRecord rec;
        try (CSVParser parser = CSVParser.parse(line, FORMAT)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                return CsvParseResult.bad("blank line");
            }
            rec = records.get(0);
        } catch (Exception e) {
            return CsvParseResult.bad("malformed CSV: " + e.getMessage());
        }

        int n = rec.size();
        if (n < 2) {
            return CsvParseResult.bad("expected at least 2 columns (term, meaning_vi), got " + n);
        }
        if (n > 7) {
            return CsvParseResult.bad("too many columns: " + n);
        }

        String term = trimToNull(rec.get(0));
        String meaningVi = trimToNull(rec.get(1));
        if (term == null) {
            return CsvParseResult.bad("missing term");
        }
        if (meaningVi == null) {
            return CsvParseResult.bad("missing meaning_vi");
        }

        String ipa = col(rec, 2);
        String exampleEn = col(rec, 3);
        String exampleVi = col(rec, 4);
        String partOfSpeech = col(rec, 5);

        String overflow = checkLengths(term, meaningVi, ipa, exampleEn, exampleVi, partOfSpeech);
        if (overflow != null) {
            return CsvParseResult.bad(overflow);
        }

        return CsvParseResult.ok(
                new ParsedRow(term, meaningVi, ipa, exampleEn, exampleVi, partOfSpeech));
    }

    private static String checkLengths(String term, String meaningVi, String ipa,
                                       String exampleEn, String exampleVi, String partOfSpeech) {
        if (term.length() > MAX_TERM) return "term exceeds " + MAX_TERM + " chars";
        if (meaningVi.length() > MAX_MEANING) return "meaning_vi exceeds " + MAX_MEANING + " chars";
        if (ipa != null && ipa.length() > MAX_IPA) return "ipa exceeds " + MAX_IPA + " chars";
        if (exampleEn != null && exampleEn.length() > MAX_EXAMPLE) return "example_en exceeds " + MAX_EXAMPLE + " chars";
        if (exampleVi != null && exampleVi.length() > MAX_EXAMPLE) return "example_vi exceeds " + MAX_EXAMPLE + " chars";
        if (partOfSpeech != null && partOfSpeech.length() > MAX_POS) return "part_of_speech exceeds " + MAX_POS + " chars";
        return null;
    }

    private static String col(CSVRecord rec, int i) {
        return i < rec.size() ? trimToNull(rec.get(i)) : null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
