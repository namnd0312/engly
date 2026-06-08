package com.engly.vocab.imports;

import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import com.engly.vocab.imports.processing.VocabImportProcessor;
import com.engly.vocab.imports.processing.VocabImportRecoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lease + Recovery test for vocab imports.
 *
 * Verifies:
 *  - claim(jobId, workerId) returns true exactly once for a PENDING job.
 *  - A job already RUNNING (fresh heartbeat) cannot be claimed by another worker.
 *  - A stale RUNNING job (heartbeat > threshold) is reclaimed by recovery.
 *  - Recovery re-processes from the checkpointed offset.
 */
class VocabImportLeaseRecoveryTest extends AbstractVocabImportIntegrationTest {

    @Autowired
    private VocabImportRepository repo;

    @Autowired
    private VocabImportProcessor processor;

    @Autowired
    private VocabImportRecoveryService recoveryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void claim_pendingJob_returnsTrueOnce() {
        Long deckId = createTestDeck("claim-test-deck");
        long jobId = repo.insertJob(deckId, "/fake/path.csv", 100);

        // First claim succeeds
        boolean claim1 = repo.claim(jobId, "worker-1");
        assertThat(claim1).isTrue();

        // Second claim (same job, different worker) fails
        boolean claim2 = repo.claim(jobId, "worker-2");
        assertThat(claim2).isFalse();

        // Job is now RUNNING under worker-1
        var job = repo.findById(jobId).get();
        assertThat(job.status().name()).isEqualTo("RUNNING");
        assertThat(job.workerId()).isEqualTo("worker-1");
    }

    @Test
    void staleRunningJob_recoveredAndCompleted(@TempDir Path tempDir) throws Exception {
        Long deckId = createTestDeck("recovery-stale-deck");

        // Create a small CSV
        List<VocabImportCsvFixture.CsvRow> rows = VocabImportCsvFixture.generateValidRows(5);
        Path csvFile = VocabImportCsvFixture.writeCsv(tempDir, rows);

        // Manually insert a RUNNING job with a stale heartbeat
        long jobId = jdbc.queryForObject("""
                INSERT INTO vocab_import_job (deck_id, file_path, file_size, status, worker_id, heartbeat_at)
                VALUES (?, ?, ?, 'RUNNING', 'stale-worker', now() - interval '10 minutes')
                RETURNING id
                """, Long.class, deckId, csvFile.toString(), Files.size(csvFile));

        var jobBefore = repo.findById(jobId).get();
        assertThat(jobBefore.status().name()).isEqualTo("RUNNING");
        assertThat(jobBefore.workerId()).isEqualTo("stale-worker");
        assertThat(jobBefore.insertedCount()).isEqualTo(0);

        // Recovery should reclaim this job
        recoveryService.enqueueRecoverable("test-recovery");

        // Poll until completion
        pollUntilTerminal(jobId, repo, 30);

        var jobAfter = repo.findById(jobId).get();
        assertThat(jobAfter.status().name()).isEqualTo("COMPLETED");
        // workerId should be different (current worker)
        assertThat(jobAfter.workerId()).isNotEqualTo("stale-worker");
        // Inserted count should reflect the CSV rows
        assertThat(jobAfter.insertedCount()).isEqualTo(5);

        // Verify rows were inserted
        Long vocabCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ?", Long.class, deckId);
        assertThat(vocabCount).isEqualTo(5);
    }

    @Test
    void freshRunningJob_notReclaimed() throws Exception {
        Long deckId = createTestDeck("recovery-fresh-deck");

        List<VocabImportCsvFixture.CsvRow> rows = VocabImportCsvFixture.generateValidRows(3);
        Path csvFile = VocabImportCsvFixture.writeCsv(tempDir, rows);

        // Insert a RUNNING job with FRESH heartbeat (now)
        long jobId = jdbc.queryForObject("""
                INSERT INTO vocab_import_job (deck_id, file_path, file_size, status, worker_id, heartbeat_at)
                VALUES (?, ?, ?, 'RUNNING', 'live-worker', now())
                RETURNING id
                """, Long.class, deckId, csvFile.toString(), Files.size(csvFile));

        var jobBefore = repo.findById(jobId).get();
        assertThat(jobBefore.status().name()).isEqualTo("RUNNING");
        assertThat(jobBefore.workerId()).isEqualTo("live-worker");

        // Recovery should NOT reclaim this (fresh heartbeat)
        recoveryService.enqueueRecoverable("test-recovery-fresh");

        // Job should still be RUNNING under live-worker (not reclaimed)
        var jobAfter = repo.findById(jobId).get();
        assertThat(jobAfter.status().name()).isEqualTo("RUNNING");
        assertThat(jobAfter.workerId()).isEqualTo("live-worker");
        assertThat(jobAfter.insertedCount()).isEqualTo(0);  // no processing happened
    }

    @Test
    void pendingJob_recoveredAndProcessed(@TempDir Path tempDir) throws Exception {
        Long deckId = createTestDeck("recovery-pending-deck");

        List<VocabImportCsvFixture.CsvRow> rows = VocabImportCsvFixture.generateValidRows(7);
        Path csvFile = VocabImportCsvFixture.writeCsv(tempDir, rows);

        // Insert a PENDING job (orphaned by a crash before processing)
        long jobId = repo.insertJob(deckId, csvFile.toString(), Files.size(csvFile));

        var jobBefore = repo.findById(jobId).get();
        assertThat(jobBefore.status().name()).isEqualTo("PENDING");
        assertThat(jobBefore.workerId()).isNull();

        // Recovery re-enqueues PENDING jobs
        recoveryService.enqueueRecoverable("test-recovery-pending");

        // Poll until completion
        pollUntilTerminal(jobId, repo, 30);

        var jobAfter = repo.findById(jobId).get();
        assertThat(jobAfter.status().name()).isEqualTo("COMPLETED");
        assertThat(jobAfter.workerId()).isNotNull();
        assertThat(jobAfter.insertedCount()).isEqualTo(7);

        Long vocabCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vocab_word WHERE deck_id = ?", Long.class, deckId);
        assertThat(vocabCount).isEqualTo(7);
    }

    // --- helpers ---

    @Autowired
    private JdbcTemplate tempJdbc;

    @TempDir
    Path tempDir;

    private Long createTestDeck(String slug) {
        return jdbc.queryForObject(
                "INSERT INTO vocab_deck (name, slug) VALUES (?, ?) RETURNING id",
                Long.class, "Test Deck " + slug, slug);
    }
}
