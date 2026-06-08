package com.engly.vocab.imports.processing;

import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Automatic crash recovery for import jobs. Two triggers:
 *  - on startup (ApplicationReadyEvent, after Flyway/DataSource are ready): re-enqueue jobs
 *    orphaned by a crash before this boot (PENDING, or RUNNING with a stale heartbeat).
 *  - periodic sweeper (@Scheduled): reclaim jobs whose worker died mid-run while the app stayed up.
 *
 * Re-enqueue is safe to call optimistically: the processor claims the lease first, so a job still
 * RUNNING under a LIVE worker (fresh heartbeat) is never in the recoverable set, and even a racing
 * double-enqueue is absorbed by the conditional-UPDATE lease. Single executor serializes the work.
 */
@Service
public class VocabImportRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(VocabImportRecoveryService.class);

    private final VocabImportRepository repo;
    private final VocabImportProcessor processor;
    private final long staleSeconds;

    public VocabImportRecoveryService(VocabImportRepository repo,
                                      VocabImportProcessor processor,
                                      @Value("${import.stale-heartbeat-seconds:120}") long staleSeconds) {
        this.repo = repo;
        this.processor = processor;
        this.staleSeconds = staleSeconds;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        enqueueRecoverable("startup");
    }

    @Scheduled(fixedDelayString = "${import.sweeper-delay-ms:90000}", initialDelay = 90000)
    public void sweep() {
        enqueueRecoverable("sweeper");
    }

    /** Find PENDING/stale-RUNNING jobs and (re)enqueue each; per-id try/catch keeps the loop alive. */
    public void enqueueRecoverable(String trigger) {
        List<Long> ids = repo.findRecoverableJobIds(staleSeconds);
        if (ids.isEmpty()) {
            return;
        }
        log.info("Recovery ({}) re-enqueuing {} import job(s): {}", trigger, ids.size(), ids);
        for (Long id : ids) {
            try {
                // Stale-RUNNING jobs must be flipped back to PENDING first: the processor's lease
                // (claim) only transitions PENDING→RUNNING, so without this reset a dead worker's
                // job could never be reclaimed. No-op for already-PENDING jobs.
                // N-instance safe despite reset+claim being two statements: claim's conditional
                // UPDATE refreshes heartbeat_at=now(), which invalidates any concurrent reset's
                // staleness predicate, and only one claim can win the PENDING→RUNNING CAS.
                repo.resetStaleRunningToPending(id, staleSeconds);
                processor.process(id);
            } catch (Exception e) {
                log.error("Failed to re-enqueue import job {}", id, e);
            }
        }
    }
}
