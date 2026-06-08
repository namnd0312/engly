package com.engly.vocab.imports.infrastructure;

import com.engly.vocab.imports.domain.BadRow;
import com.engly.vocab.imports.domain.BatchCheckpoint;
import com.engly.vocab.imports.domain.ImportJobStatus;
import com.engly.vocab.imports.domain.VocabImportError;
import com.engly.vocab.imports.domain.VocabImportJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Plain-JDBC persistence for the CSV import job lifecycle (NO JPA on the write path → no
 * persistence-context bloat → OOM-safe at 512MB heap).
 *
 * Load-bearing guarantees:
 *  - {@link #commitBatch} runs upsert + capped error inserts + counter/offset/heartbeat UPDATE in
 *    ONE transaction. Crash mid-batch rolls back everything incl. processed_offset → resume re-reads
 *    that batch cleanly (no dup via ON CONFLICT, no loss because offset never advanced).
 *  - {@link #claim} is a conditional UPDATE; rows-affected==1 means this worker owns the job
 *    (exactly-one semantics under concurrent callers / recovery sweeps).
 */
@Repository
public class VocabImportRepository {

    /** Cap stored error rows per job to bound the error table; error_count still counts all bad rows. */
    private static final int MAX_STORED_ERRORS = 10_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final VocabWordBatchUpserter upserter;

    public VocabImportRepository(JdbcTemplate jdbc,
                                 PlatformTransactionManager txManager,
                                 VocabWordBatchUpserter upserter) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.upserter = upserter;
    }

    /** Insert a PENDING job; returns generated id. */
    public long insertJob(Long deckId, String filePath, long fileSize) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO vocab_import_job (deck_id, file_path, file_size, status)
                    VALUES (?, ?, ?, 'PENDING')
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, deckId);
            ps.setString(2, filePath);
            ps.setLong(3, fileSize);
            return ps;
        }, kh);
        Number key = kh.getKeyList().size() == 1
                ? (Number) kh.getKeys().get("id")
                : kh.getKey();
        return key.longValue();
    }

    /**
     * Lease claim: take ownership only if still PENDING. Returns true exactly once for a PENDING job.
     * A job already RUNNING under a live worker is NOT claimable here (recovery resets stale ones first).
     */
    public boolean claim(Long jobId, String workerId) {
        int rows = jdbc.update("""
                UPDATE vocab_import_job
                   SET worker_id = ?, status = 'RUNNING', heartbeat_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, workerId, jobId);
        return rows == 1;
    }

    /**
     * Atomic per-batch checkpoint. ONE transaction:
     *  1) multi-row upsert of valid rows (ON CONFLICT DO NOTHING),
     *  2) capped insert of bad-row error records,
     *  3) UPDATE counters + processed_offset + heartbeat (guarded by worker_id ownership).
     * @return rows actually inserted this batch.
     */
    public int commitBatch(BatchCheckpoint cp) {
        Integer inserted = tx.execute(status -> {
            int ins = upserter.upsertBatch(cp.deckId(), cp.rows());
            insertErrorsCapped(cp.jobId(), cp.errors());

            long processedDelta = (long) cp.rows().size() + cp.errors().size();
            long skippedDelta = (long) cp.rows().size() - ins;   // conflicts + in-batch dupes
            long errorDelta = cp.errors().size();

            int updated = jdbc.update("""
                    UPDATE vocab_import_job
                       SET processed_offset = ?,
                           processed_count  = processed_count  + ?,
                           inserted_count   = inserted_count   + ?,
                           skipped_count    = skipped_count    + ?,
                           error_count      = error_count      + ?,
                           heartbeat_at     = now(),
                           updated_at       = now()
                     WHERE id = ? AND worker_id = ?
                    """, cp.newOffset(), processedDelta, ins, skippedDelta, errorDelta,
                    cp.jobId(), cp.workerId());
            if (updated != 1) {
                // Lost the lease (reclaimed by another worker) → abort so we don't double-count.
                throw new IllegalStateException(
                        "Lost lease on job " + cp.jobId() + " (worker " + cp.workerId() + ")");
            }
            return ins;
        });
        return inserted == null ? 0 : inserted;
    }

    /** Insert error rows up to the per-job storage cap (counters track the full delta separately). */
    private void insertErrorsCapped(Long jobId, List<BadRow> errors) {
        if (errors.isEmpty()) {
            return;
        }
        long stored = countErrors(jobId);
        if (stored >= MAX_STORED_ERRORS) {
            return;
        }
        int room = (int) Math.min(errors.size(), MAX_STORED_ERRORS - stored);
        List<BadRow> toStore = errors.subList(0, room);
        jdbc.batchUpdate("""
                INSERT INTO vocab_import_error (job_id, line_no, raw_excerpt, reason)
                VALUES (?, ?, ?, ?)
                """, toStore, toStore.size(), (ps, e) -> {
            ps.setLong(1, jobId);
            ps.setLong(2, e.lineNo());
            ps.setString(3, e.rawExcerpt());
            ps.setString(4, e.reason());
        });
    }

    /** Optional mid-batch keepalive (long batches); commitBatch already bumps heartbeat each batch. */
    public void heartbeat(Long jobId, String workerId) {
        jdbc.update("""
                UPDATE vocab_import_job SET heartbeat_at = now(), updated_at = now()
                 WHERE id = ? AND worker_id = ?
                """, jobId, workerId);
    }

    public void markCompleted(Long jobId) {
        jdbc.update("""
                UPDATE vocab_import_job SET status = 'COMPLETED', updated_at = now() WHERE id = ?
                """, jobId);
    }

    public void markFailed(Long jobId) {
        jdbc.update("""
                UPDATE vocab_import_job SET status = 'FAILED', worker_id = NULL, updated_at = now()
                 WHERE id = ?
                """, jobId);
    }

    /** Retry: FAILED → PENDING, clear lease but KEEP processed_offset + counts so it resumes (not restarts). */
    public void resetToPending(Long jobId) {
        jdbc.update("""
                UPDATE vocab_import_job
                   SET status = 'PENDING', worker_id = NULL, heartbeat_at = NULL, updated_at = now()
                 WHERE id = ?
                """, jobId);
    }

    /**
     * Recovery reset: a stale RUNNING job (its worker died mid-run) → PENDING so the processor's
     * PENDING-only {@link #claim} can take it. Guarded by the same staleness predicate as
     * {@link #findRecoverableJobIds}, so a job that regained a fresh heartbeat in the meantime is
     * left untouched (no-op). KEEPS processed_offset + counts → resumes, not restarts.
     */
    public void resetStaleRunningToPending(Long jobId, long staleSeconds) {
        jdbc.update("""
                UPDATE vocab_import_job
                   SET status = 'PENDING', worker_id = NULL, heartbeat_at = NULL, updated_at = now()
                 WHERE id = ? AND status = 'RUNNING'
                   AND heartbeat_at < now() - (? * interval '1 second')
                """, jobId, staleSeconds);
    }

    public Optional<VocabImportJob> findById(Long jobId) {
        List<VocabImportJob> list = jdbc.query("""
                SELECT id, deck_id, file_path, file_size, status, processed_offset, processed_count,
                       inserted_count, skipped_count, error_count, worker_id, heartbeat_at,
                       created_at, updated_at
                  FROM vocab_import_job WHERE id = ?
                """, JOB_MAPPER, jobId);
        return list.stream().findFirst();
    }

    /**
     * Recoverable = PENDING (queued, possibly orphaned by a crash before processing) OR
     * RUNNING with a stale heartbeat (worker died mid-run). Live jobs keep a fresh heartbeat
     * (bumped every batch) so they are NOT reclaimed.
     */
    public List<Long> findRecoverableJobIds(long staleSeconds) {
        return jdbc.queryForList("""
                SELECT id FROM vocab_import_job
                 WHERE status = 'PENDING'
                    OR (status = 'RUNNING' AND heartbeat_at < now() - (? * interval '1 second'))
                 ORDER BY id
                """, Long.class, staleSeconds);
    }

    /**
     * File paths of COMPLETED jobs finalized more than {@code olderThanHours} ago — eligible for
     * disk cleanup. RUNNING/PENDING/FAILED files are deliberately excluded (still needed for
     * resume/retry).
     */
    public List<String> findCleanableFilePaths(long olderThanHours) {
        return jdbc.queryForList("""
                SELECT file_path FROM vocab_import_job
                 WHERE status = 'COMPLETED' AND updated_at < now() - (? * interval '1 hour')
                """, String.class, olderThanHours);
    }

    public long countErrors(Long jobId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM vocab_import_error WHERE job_id = ?", Long.class, jobId);
        return n == null ? 0L : n;
    }

    public List<VocabImportError> pageErrors(Long jobId, int page, int size) {
        return jdbc.query("""
                SELECT id, job_id, line_no, raw_excerpt, reason, created_at
                  FROM vocab_import_error
                 WHERE job_id = ?
                 ORDER BY id
                 LIMIT ? OFFSET ?
                """, ERROR_MAPPER, jobId, size, (long) page * size);
    }

    // --- RowMappers ---

    private static final RowMapper<VocabImportJob> JOB_MAPPER = (rs, n) -> new VocabImportJob(
            rs.getLong("id"),
            rs.getLong("deck_id"),
            rs.getString("file_path"),
            rs.getLong("file_size"),
            ImportJobStatus.valueOf(rs.getString("status")),
            rs.getLong("processed_offset"),
            rs.getLong("processed_count"),
            rs.getLong("inserted_count"),
            rs.getLong("skipped_count"),
            rs.getLong("error_count"),
            rs.getString("worker_id"),
            toInstant(rs.getTimestamp("heartbeat_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private static final RowMapper<VocabImportError> ERROR_MAPPER = (rs, n) -> new VocabImportError(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getLong("line_no"),
            rs.getString("raw_excerpt"),
            rs.getString("reason"),
            toInstant(rs.getTimestamp("created_at")));

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
