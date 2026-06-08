package com.engly.vocab.imports.processing;

import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import me.desair.tus.server.TusFileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Hourly disk reclamation. Deletes finalized import files for COMPLETED jobs older than a cutoff,
 * and asks tus to clean up abandoned/expired in-progress uploads. NEVER touches files of
 * RUNNING/PENDING/FAILED jobs (still needed for active processing, resume, or retry).
 */
@Component
public class UploadCleanupScheduledTask {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupScheduledTask.class);

    private final VocabImportRepository repo;
    private final TusFileUploadService tusService;
    private final long completedFileTtlHours;

    public UploadCleanupScheduledTask(VocabImportRepository repo,
                                      TusFileUploadService tusService,
                                      @Value("${import.completed-file-ttl-hours:24}") long completedFileTtlHours) {
        this.repo = repo;
        this.tusService = tusService;
        this.completedFileTtlHours = completedFileTtlHours;
    }

    @Scheduled(fixedRate = 3_600_000, initialDelay = 600_000)
    public void cleanup() {
        List<String> paths = repo.findCleanableFilePaths(completedFileTtlHours);
        int deleted = 0;
        for (String path : paths) {
            try {
                if (Files.deleteIfExists(Path.of(path))) {
                    deleted++;
                }
            } catch (Exception e) {
                log.warn("Failed to delete completed import file {}", path, e);
            }
        }
        try {
            tusService.cleanup();
        } catch (Exception e) {
            log.warn("tus cleanup failed", e);
        }
        if (deleted > 0) {
            log.info("Import cleanup removed {} completed file(s)", deleted);
        }
    }
}
