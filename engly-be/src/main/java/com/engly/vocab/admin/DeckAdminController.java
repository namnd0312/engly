package com.engly.vocab.admin;

import com.engly.vocab.api.dto.DeckPageResponse;
import com.engly.vocab.api.dto.DeckRequest;
import com.engly.vocab.api.dto.DeckResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin CRUD endpoints for vocabulary decks.
 * All routes sit under /api/admin/decks, locked to ROLE_ADMIN by the security chain —
 * no extra method annotations needed here.
 */
@RestController
@RequestMapping("/api/admin/decks")
public class DeckAdminController {

    private final DeckAdminService service;

    public DeckAdminController(DeckAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<DeckPageResponse> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.search(q, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeckResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    public ResponseEntity<DeckResponse> create(@Valid @RequestBody DeckRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeckResponse> update(
            @PathVariable Long id, @Valid @RequestBody DeckRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
