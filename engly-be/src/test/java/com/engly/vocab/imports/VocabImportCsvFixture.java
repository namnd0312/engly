package com.engly.vocab.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper to materialize test CSV files to temporary paths.
 * Rows are written with headers; used across resume, idempotency, bad-row, and memory tests.
 */
public class VocabImportCsvFixture {

    public static final String CSV_HEADER = "term,meaning_vi,ipa,example_en,example_vi,part_of_speech";

    public record CsvRow(String term, String meaningVi, String ipa, String exampleEn,
                         String exampleVi, String partOfSpeech) {
        public String toLine() {
            return escapeCsv(term) + "," +
                   escapeCsv(meaningVi) + "," +
                   escapeCsv(ipa) + "," +
                   escapeCsv(exampleEn) + "," +
                   escapeCsv(exampleVi) + "," +
                   escapeCsv(partOfSpeech);
        }

        private static String escapeCsv(String s) {
            if (s == null) return "";
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                return "\"" + s.replace("\"", "\"\"") + "\"";
            }
            return s;
        }
    }

    /**
     * Create a temp CSV file with header + rows.
     * @param tempDir path to write CSV to
     * @param rows data rows (header auto-prepended)
     * @return path to the created CSV file
     */
    public static Path writeCsv(Path tempDir, List<CsvRow> rows) throws IOException {
        Path csvFile = tempDir.resolve("test_vocab_import.csv");
        StringBuilder sb = new StringBuilder();
        sb.append(CSV_HEADER).append("\n");
        for (CsvRow row : rows) {
            sb.append(row.toLine()).append("\n");
        }
        Files.writeString(csvFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return csvFile;
    }

    /**
     * Helper to generate valid rows for bulk tests.
     */
    public static List<CsvRow> generateValidRows(int count) {
        List<CsvRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new CsvRow(
                    "term_" + i,
                    "meaning_vi_" + i,
                    "/term_" + i + "/",
                    "example en " + i,
                    "ví dụ " + i,
                    "noun"
            ));
        }
        return rows;
    }

    /**
     * Helper to generate rows with specific bad-row scenarios.
     * @return list with 1 valid row, then various bad rows to test error handling
     */
    public static List<CsvRow> generateBadRowScenarios() {
        List<CsvRow> rows = new ArrayList<>();
        // Valid row
        rows.add(new CsvRow("apple", "táo", "/æp(ə)l/", "red apple", "quả táo đỏ", "noun"));
        // Note: we can't directly create "bad" rows as CsvRow here (it has all fields).
        // Instead, we'll add raw lines that the test file will write directly.
        // This will be handled in the bad-row test by writing custom lines.
        return rows;
    }

    /**
     * Write a CSV with mixed valid and bad rows (raw strings for full control).
     * @param tempDir path to write CSV to
     * @param lines raw CSV lines (includes header)
     * @return path to the created CSV file
     */
    public static Path writeCsvRaw(Path tempDir, List<String> lines) throws IOException {
        Path csvFile = tempDir.resolve("test_vocab_import.csv");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        Files.writeString(csvFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return csvFile;
    }
}
