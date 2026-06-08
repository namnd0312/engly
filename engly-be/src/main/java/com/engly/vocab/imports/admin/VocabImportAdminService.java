package com.engly.vocab.imports.admin;

import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.imports.api.dto.CreateVocabImportRequest;
import com.engly.vocab.imports.api.dto.VocabImportCreatedResponse;
import com.engly.vocab.imports.api.dto.VocabImportErrorPageResponse;
import com.engly.vocab.imports.api.dto.VocabImportErrorResponse;
import com.engly.vocab.imports.api.dto.VocabImportJobResponse;
import com.engly.vocab.imports.domain.ImportJobStatus;
import com.engly.vocab.imports.domain.VocabImportConflictException;
import com.engly.vocab.imports.domain.VocabImportJob;
import com.engly.vocab.imports.domain.VocabImportJobNotFoundException;
import com.engly.vocab.imports.infrastructure.VocabImportRepository;
import com.engly.vocab.imports.processing.VocabImportProcessor;
import com.engly.vocab.imports.upload.TusUploadConfig;
import com.engly.vocab.infrastructure.VocabDeckJpaRepository;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Orchestrates the import HTTP surface: validate → finalize upload → create job → enqueue;
 * plus status, paged errors, and FAILED-only retry (resume, not restart).
 *
 * On create the completed tus upload is materialized into an immutable file under the import
 * directory (constant-memory stream copy) so the async processor has a stable, seekable file to
 * resume from. The uploadId is treated as untrusted (path-traversal guard) even though tus
 * generates it. Not @Transactional: the repository owns the atomic per-batch transactions; here
 * each call is a single statement or an idempotent enqueue.
 */
@Service
public class VocabImportAdminService {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final VocabImportRepository repo;
    private final VocabDeckJpaRepository deckRepo;
    private final TusFileUploadService tusService;
    private final VocabImportProcessor processor;
    private final Path importDir;

    public VocabImportAdminService(VocabImportRepository repo,
                                   VocabDeckJpaRepository deckRepo,
                                   TusFileUploadService tusService,
                                   VocabImportProcessor processor,
                                   Path importFileDirectory) {
        this.repo = repo;
        this.deckRepo = deckRepo;
        this.tusService = tusService;
        this.processor = processor;
        this.importDir = importFileDirectory;
    }

    public VocabImportCreatedResponse create(CreateVocabImportRequest req) {
        if (!deckRepo.existsById(req.deckId())) {
            throw new VocabDeckNotFoundException(req.deckId());
        }
        String uploadUri = TusUploadConfig.UPLOAD_URI + "/" + req.uploadId();
        UploadInfo info;
        try {
            info = tusService.getUploadInfo(uploadUri);
        } catch (IOException | TusException e) {
            throw new VocabImportConflictException("Cannot read upload: " + req.uploadId());
        }
        if (info == null || info.isUploadInProgress() || info.getLength() == null) {
            throw new VocabImportConflictException("Upload not complete: " + req.uploadId());
        }
        long totalBytes = info.getLength();

        Path target = importDir.resolve(req.uploadId() + ".csv").normalize();
        if (!target.startsWith(importDir)) {
            throw new IllegalArgumentException("Invalid uploadId");
        }
        try (InputStream is = tusService.getUploadedBytes(uploadUri)) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | TusException e) {
            throw new IllegalStateException("Failed to finalize upload " + req.uploadId(), e);
        }

        long jobId = repo.insertJob(req.deckId(), target.toString(), totalBytes);
        processor.process(jobId);
        return new VocabImportCreatedResponse(jobId, ImportJobStatus.PENDING.name());
    }

    public VocabImportJobResponse getStatus(Long id) {
        return toResponse(findOrThrow(id));
    }

    public VocabImportErrorPageResponse getErrors(Long id, int page, int size) {
        validatePaging(page, size);
        findOrThrow(id); // 404 if the job doesn't exist
        long total = repo.countErrors(id);
        List<VocabImportErrorResponse> content = repo.pageErrors(id, page, size).stream()
                .map(e -> new VocabImportErrorResponse(e.lineNo(), e.rawExcerpt(), e.reason()))
                .toList();
        int totalPages = (int) Math.ceil((double) total / size);
        return new VocabImportErrorPageResponse(content, page, size, total, totalPages);
    }

    public VocabImportCreatedResponse retry(Long id) {
        VocabImportJob job = findOrThrow(id);
        if (job.status() != ImportJobStatus.FAILED) {
            throw new VocabImportConflictException("Job not in FAILED state, cannot retry: " + id);
        }
        repo.resetToPending(id);   // keeps processed_offset + counts → resumes, not restarts
        processor.process(id);
        return new VocabImportCreatedResponse(id, ImportJobStatus.PENDING.name());
    }

    // --- helpers ---

    private VocabImportJob findOrThrow(Long id) {
        return repo.findById(id).orElseThrow(() -> new VocabImportJobNotFoundException(id));
    }

    private VocabImportJobResponse toResponse(VocabImportJob j) {
        int percent = j.fileSize() > 0
                ? (int) Math.min(100, j.processedOffset() * 100 / j.fileSize())
                : 0;
        return new VocabImportJobResponse(
                j.id(), j.deckId(), j.status().name(), j.fileSize(),
                j.processedCount(), j.insertedCount(), j.skippedCount(), j.errorCount(),
                percent, j.createdAt());
    }

    private void validatePaging(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0, got: " + page);
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between " + MIN_SIZE + " and " + MAX_SIZE + ", got: " + size);
        }
    }
}
