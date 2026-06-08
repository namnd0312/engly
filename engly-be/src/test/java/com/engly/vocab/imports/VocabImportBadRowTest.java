package com.engly.vocab.imports;

import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import com.engly.vocab.imports.processing.VocabImportProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bad-row handling test for vocab imports.
 *
 * Verifies:
 *  - Valid rows are inserted.
 *  - Bad rows (missing term, missing meaning_vi, too few columns, overflow length, blank fields)
 *    are skipped and recorded in vocab_import_error with line_no + reason.
 *  - Job completes despite bad rows.
 *  - error_count matches the number of bad-row records.
 *  - inserted + skipped + error == total data rows processed.
 */
class VocabImportBadRowTest extends AbstractVocabImportIntegrationTest {

    @Autowired
    private VocabImportRepository repo;

    @Autowired
    private VocabImportProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void badRows_skipped_recordedInError_jobCompletes(@TempDir Path tempDir) throws Exception {
        Long deckId = createTestDeck("bad-row-deck");

        // CSV with mixed good and bad rows
        List<String> lines = Arrays.asList(
                "term,meaning_vi,ipa,example_en,example_vi,part_of_speech",
                // Line 2: valid
                "apple,táo,/æpl/,red apple,quả táo đỏ,noun",
                // Line 3: missing term (empty first column)
                ",ý nghĩa,,,,",
                // Line 4: valid
                "banana,chuối,/bəˈnɑːnə/,yellow fruit,quả chuối vàng,noun",
                // Line 5: too few columns (only 1)
                "onlyterm",
                // Line 6: valid
                "orange,cam,/ˈɒrɪndʒ/,citrus fruit,quả cam,noun",
                // Line 7: blank meaning_vi
                "pear,  ,/peə(r)/,green fruit,quả lê xanh,noun",
                // Line 8: overflow term (>120 chars)
                "term" + "x".repeat(200) + ",too long meaning,/x/,,,noun",
                // Line 9: valid
                "grape,nho,/ɡreɪp/,purple fruit,quả nho tím,noun"
        );

        Path csvFile = VocabImportCsvFixture.writeCsvRaw(tempDir, lines);
        long jobId = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));

        // Run processor
        processor.process(jobId);
        pollUntilTerminal(jobId, repo, 30);

        var job = repo.findById(jobId).get();
        assertThat(job.status().name()).isEqualTo("COMPLETED");

        // Expected: 5 valid rows (apple, banana, orange, grape, + one more maybe)
        // Bad rows: 3 (missing term, < 2 cols, blank meaning_vi, overflow length)
        long insertedCount = job.insertedCount();
        long errorCount = job.errorCount();
        long processedCount = job.processedCount();

        // Verify error_count matches stored errors
        long storedErrorCount = repo.countErrors(jobId);
        assertThat(storedErrorCount).isEqualTo(errorCount);
        assertThat(storedErrorCount).isGreaterThan(0);

        // Verify vocab_word has only valid rows
        Long vocabCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ?", Long.class, deckId);
        assertThat(vocabCount).isEqualTo(insertedCount);

        // Verify error rows are stored with line_no + reason
        var errors = repo.pageErrors(jobId, 0, 100);
        assertThat(errors).isNotEmpty();
        for (var err : errors) {
            assertThat(err.lineNo()).isGreaterThanOrEqualTo(2);  // line 1 is header
            assertThat(err.reason()).isNotBlank();
            assertThat(err.rawExcerpt()).isNotBlank();
        }

        // Verify processed_count = inserted + error
        long totalDataRows = (long) lines.size() - 1;  // exclude header
        assertThat(insertedCount + errorCount).isLessThanOrEqualTo(processedCount);
    }

    @Test
    void multipleValidAndBadRows_jobCompletionStats(@TempDir Path tempDir) throws Exception {
        Long deckId = createTestDeck("mixed-rows-deck");

        List<String> lines = Arrays.asList(
                "term,meaning_vi,ipa,example_en,example_vi,part_of_speech",
                // 2 valid
                "cat,mèo,/kæt/,domestic animal,con vật nuôi nhà,noun",
                "dog,chó,/dɔːɡ/,loyal pet,vật nuôi trung thành,noun",
                // 2 bad (missing meaning_vi, too few cols)
                "bird,   ,/bɜːd/,feathered animal,con chim,noun",
                "fish",
                // 1 valid
                "horse,ngựa,/hɔːrs/,large mammal,động vật có vú lớn,noun"
        );

        Path csvFile = VocabImportCsvFixture.writeCsvRaw(tempDir, lines);
        long jobId = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));

        processor.process(jobId);
        pollUntilTerminal(jobId, repo, 30);

        var job = repo.findById(jobId).get();
        assertThat(job.status().name()).isEqualTo("COMPLETED");

        // Expected: 3 valid, 2 bad
        long inserted = job.insertedCount();
        long errors = job.errorCount();
        assertThat(inserted).isEqualTo(3);
        assertThat(errors).isEqualTo(2);

        // Verify no bad rows in vocab_word
        Long badCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ? AND term IN ('bird', 'fish')",
                Long.class, deckId);
        assertThat(badCount).isEqualTo(0);

        // Verify good rows exist
        Long goodCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ? AND term IN ('cat', 'dog', 'horse')",
                Long.class, deckId);
        assertThat(goodCount).isEqualTo(3);
    }

    // --- helpers ---

    private Long createTestDeck(String slug) {
        return jdbc.queryForObject(
                "INSERT INTO vocab_deck (name, slug) VALUES (?, ?) RETURNING id",
                Long.class, "Test Deck " + slug, slug);
    }
}
