package com.engly.vocab.imports;

import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import com.engly.vocab.imports.processing.VocabImportProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency test for vocab imports.
 *
 * Verifies that running the same import twice:
 *  - inserted_count remains stable (no growth on 2nd run).
 *  - vocab_word count unchanged (all rows are ON CONFLICT skipped on 2nd run).
 *  - No duplicate (deck_id, term) rows (unique index enforced).
 */
class VocabImportIdempotencyTest extends AbstractVocabImportIntegrationTest {

    @Autowired
    private VocabImportRepository repo;

    @Autowired
    private VocabImportProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sameJobTwice_secondRunSkipsAllRows_noDuplicates(@TempDir Path tempDir) throws Exception {
        List<VocabImportCsvFixture.CsvRow> rows = VocabImportCsvFixture.generateValidRows(25);
        Path csvFile = VocabImportCsvFixture.writeCsv(tempDir, rows);

        Long deckId = createTestDeck("idem-deck-1");

        // First import
        long jobId1 = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));
        processor.process(jobId1);
        pollUntilTerminal(jobId1, repo, 30);

        var job1 = repo.findById(jobId1).get();
        assertThat(job1.status().name()).isEqualTo("COMPLETED");
        long inserted1 = job1.insertedCount();
        assertThat(inserted1).isEqualTo(25);

        Long vocabCount1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ?", Long.class, deckId);
        assertThat(vocabCount1).isEqualTo(25);

        // Second import (same file, same deck)
        long jobId2 = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));
        processor.process(jobId2);
        pollUntilTerminal(jobId2, repo, 30);

        var job2 = repo.findById(jobId2).get();
        assertThat(job2.status().name()).isEqualTo("COMPLETED");
        // All rows should be skipped (ON CONFLICT) — inserted_count should be 0
        long inserted2 = job2.insertedCount();
        assertThat(inserted2).isEqualTo(0);
        // skipped_count should equal the number of rows in the batch
        assertThat(job2.skippedCount()).isGreaterThanOrEqualTo(25);

        // vocab_word count should not have grown
        Long vocabCount2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ?", Long.class, deckId);
        assertThat(vocabCount2).isEqualTo(25);

        // No duplicates
        Long dupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT deck_id, term FROM vocab_word " +
                "WHERE deck_id = ? GROUP BY deck_id, term HAVING count(*) > 1) t",
                Long.class, deckId);
        assertThat(dupCount).isEqualTo(0);
    }

    @Test
    void twoJobsSameDeckFile_secondJobSkipsAll_stableCount(@TempDir Path tempDir) throws Exception {
        List<VocabImportCsvFixture.CsvRow> rows = VocabImportCsvFixture.generateValidRows(30);
        Path csvFile = VocabImportCsvFixture.writeCsv(tempDir, rows);

        Long deckId = createTestDeck("idem-deck-2");

        // Job 1
        long jobId1 = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));
        processor.process(jobId1);
        pollUntilTerminal(jobId1, repo, 30);

        // Job 2 (second job on same deck + same file)
        long jobId2 = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));
        processor.process(jobId2);
        pollUntilTerminal(jobId2, repo, 30);

        var job1Final = repo.findById(jobId1).get();
        var job2Final = repo.findById(jobId2).get();

        // Job 1 inserted all, Job 2 inserted 0 (all skipped)
        assertThat(job1Final.insertedCount()).isEqualTo(30);
        assertThat(job2Final.insertedCount()).isEqualTo(0);

        // Total vocab_word count should be exactly 30
        Long totalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ?", Long.class, deckId);
        assertThat(totalCount).isEqualTo(30);

        // No duplicates
        Long dupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT deck_id, term FROM vocab_word " +
                "WHERE deck_id = ? GROUP BY deck_id, term HAVING count(*) > 1) t",
                Long.class, deckId);
        assertThat(dupCount).isEqualTo(0);
    }

    // --- helpers ---

    private Long createTestDeck(String slug) {
        return jdbc.queryForObject(
                "INSERT INTO vocab_deck (name, slug) VALUES (?, ?) RETURNING id",
                Long.class, "Test Deck " + slug, slug);
    }
}
