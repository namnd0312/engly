package com.engly.vocab.admin;

import com.engly.vocab.api.dto.VocabWordPageResponse;
import com.engly.vocab.api.dto.VocabWordRequest;
import com.engly.vocab.api.dto.VocabWordResponse;
import com.engly.vocab.api.dto.VocabWordVerifiedRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin CRUD endpoints for vocabulary words.
 * All routes sit under /api/admin/vocab-words, locked to ROLE_ADMIN by the security chain.
 * Optional deckId query param filters list to words belonging to a specific deck.
 */
@RestController
@RequestMapping("/api/admin/vocab-words")
public class VocabWordAdminController {

    private final VocabWordAdminService service;

    public VocabWordAdminController(VocabWordAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<VocabWordPageResponse> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Long deckId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.search(q, deckId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VocabWordResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    public ResponseEntity<VocabWordResponse> create(@Valid @RequestBody VocabWordRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VocabWordResponse> update(
            @PathVariable Long id, @Valid @RequestBody VocabWordRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/verified")
    public ResponseEntity<VocabWordResponse> toggleVerified(
            @PathVariable Long id, @RequestBody VocabWordVerifiedRequest req) {
        return ResponseEntity.ok(service.toggleVerified(id, req.verified()));
    }
}
