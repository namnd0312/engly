package com.engly.verb.admin;

import com.engly.verb.api.dto.VerbAdminPageResponse;
import com.engly.verb.api.dto.VerbAdminResponse;
import com.engly.verb.api.dto.VerbRequest;
import com.engly.verb.api.dto.VerbVerifiedRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin CRUD endpoints for irregular verbs. All routes sit under {@code /api/admin/verbs}, which the
 * security layer locks to ROLE_ADMIN — no extra method annotations needed here. CSRF (cookie) protects
 * the mutating verbs; the SPA echoes X-XSRF-TOKEN.
 */
@RestController
@RequestMapping("/api/admin/verbs")
public class VerbAdminController {

    private final VerbAdminService service;

    public VerbAdminController(VerbAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<VerbAdminPageResponse> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.search(q, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<VerbAdminResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    public ResponseEntity<VerbAdminResponse> create(@Valid @RequestBody VerbRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VerbAdminResponse> update(
            @PathVariable Long id, @Valid @RequestBody VerbRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/verified")
    public ResponseEntity<VerbAdminResponse> toggleVerified(
            @PathVariable Long id, @RequestBody VerbVerifiedRequest req) {
        return ResponseEntity.ok(service.toggleVerified(id, req.verified()));
    }
}
