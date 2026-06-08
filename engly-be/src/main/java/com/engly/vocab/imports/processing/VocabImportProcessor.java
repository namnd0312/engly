package com.engly.vocab.imports.processing;

import com.engly.vocab.imports.domain.BadRow;
import com.engly.vocab.imports.domain.BatchCheckpoint;
import com.engly.vocab.imports.domain.ParsedRow;
import com.engly.vocab.imports.domain.VocabImportJob;
import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The streaming import engine. For one job: claim the lease, seek to the last checkpointed byte
 * offset, read line-by-line counting exact bytes, parse + validate each row, accumulate a bounded
 * batch, and atomically checkpoint (upsert + errors + counters + offset) every batch.
 *
 * Crash-resumable: because the offset is committed only at a batch boundary (right after a '\n')
 * in the SAME transaction as the upsert, a crash mid-batch rolls back fully → resume re-reads that
 * batch cleanly (no dup via ON CONFLICT, no loss because the offset never advanced).
 *
 * Constant heap regardless of file size: never holds more than one batch of rows in memory.
 * Runs on the single bounded {@code csvImportExecutor}; the lease makes re-enqueue idempotent.
 */
@Component
public class VocabImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(VocabImportProcessor.class);
    private static final int HEADER_LINES = 1;
    private static final int EXCERPT_MAX = 500;

    private final VocabImportRepository repo;
    private final CsvRowParser parser;
    private final WorkerIdProvider workerIdProvider;
    private final int batchSize;

    public VocabImportProcessor(VocabImportRepository repo,
                                CsvRowParser parser,
                                WorkerIdProvider workerIdProvider,
                                @Value("${import.batch-size:500}") int batchSize) {
        this.repo = repo;
        this.parser = parser;
        this.workerIdProvider = workerIdProvider;
        this.batchSize = batchSize;
    }

    /**
     * Process (or resume) a job. Safe to call optimistically/concurrently — if the lease cannot be
     * claimed (already RUNNING under a live worker) this returns immediately.
     */
    @Async("csvImportExecutor")
    public void process(Long jobId) {
        String workerId = workerIdProvider.workerId();
        Optional<VocabImportJob> opt = repo.findById(jobId);
        if (opt.isEmpty()) {
            log.warn("Import job {} not found; skipping", jobId);
            return;
        }
        if (!repo.claim(jobId, workerId)) {
            log.debug("Could not claim import job {} (already owned)", jobId);
            return;
        }
        VocabImportJob job = opt.get();
        try {
            runImport(job, workerId);
            repo.markCompleted(jobId);
            log.info("Import job {} completed", jobId);
        } catch (Exception e) {
            log.error("Import job {} failed", jobId, e);
            repo.markFailed(jobId);
        }
    }

    private void runImport(VocabImportJob job, String workerId) throws Exception {
        Long jobId = job.id();
        Long deckId = job.deckId();
        Path file = Path.of(job.filePath());
        if (!Files.exists(file)) {
            throw new IllegalStateException("Import file missing: " + job.filePath());
        }

        long offset = job.processedOffset();
        // header is physical line 1; processedCount data rows already done → next line continues from there
        long physicalLine = HEADER_LINES + job.processedCount();

        try (ByteCountingLineReader reader = new ByteCountingLineReader(file)) {
            reader.seek(offset);

            if (offset == 0) {
                String header = reader.readLine();
                if (header == null) {
                    return; // empty file → nothing to import (caller marks COMPLETED)
                }
                offset += reader.lastBytesConsumed();
                physicalLine = HEADER_LINES; // header consumed; data rows start at line 2
            }

            List<ParsedRow> rows = new ArrayList<>(batchSize);
            List<BadRow> errors = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                offset += reader.lastBytesConsumed();
                physicalLine++;

                CsvParseResult res = parser.parse(line);
                if (res.ok()) {
                    rows.add(res.row());
                } else {
                    errors.add(new BadRow(physicalLine, excerpt(line), res.error()));
                }

                if (rows.size() + errors.size() >= batchSize) {
                    repo.commitBatch(new BatchCheckpoint(jobId, workerId, deckId, rows, errors, offset));
                    rows = new ArrayList<>(batchSize);
                    errors = new ArrayList<>();
                }
            }
            if (!rows.isEmpty() || !errors.isEmpty()) {
                repo.commitBatch(new BatchCheckpoint(jobId, workerId, deckId, rows, errors, offset));
            }
        }
    }

    private static String excerpt(String line) {
        return line.length() > EXCERPT_MAX ? line.substring(0, EXCERPT_MAX) : line;
    }
}
