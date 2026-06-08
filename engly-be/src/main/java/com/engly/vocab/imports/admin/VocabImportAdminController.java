package com.engly.vocab.imports.admin;

import com.engly.vocab.imports.api.dto.CreateVocabImportRequest;
import com.engly.vocab.imports.api.dto.VocabImportCreatedResponse;
import com.engly.vocab.imports.api.dto.VocabImportErrorPageResponse;
import com.engly.vocab.imports.api.dto.VocabImportJobResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin endpoints for CSV bulk imports. All routes sit under /api/admin/vocab-imports, locked to
 * ROLE_ADMIN by Security Chain 1 (stateless Bearer, CSRF off). Thin delegation to the service.
 *
 * Note: the resumable upload endpoint itself ({@code /upload}) is served by VocabImportUploadController.
 */
@RestController
@RequestMapping("/api/admin/vocab-imports")
public class VocabImportAdminController {

    private final VocabImportAdminService service;

    public VocabImportAdminController(VocabImportAdminService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VocabImportCreatedResponse> create(
            @Valid @RequestBody CreateVocabImportRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VocabImportJobResponse> getStatus(@PathVariable Long id) {
        return ResponseEntity.ok(service.getStatus(id));
    }

    @GetMapping("/{id}/errors")
    public ResponseEntity<VocabImportErrorPageResponse> getErrors(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.getErrors(id, page, size));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<VocabImportCreatedResponse> retry(@PathVariable Long id) {
        return ResponseEntity.ok(service.retry(id));
    }
}
