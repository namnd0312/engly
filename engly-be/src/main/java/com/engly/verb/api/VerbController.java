package com.engly.verb.api;

import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.api.dto.VerbSearchPageResponse;
import com.engly.verb.application.VerbQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for public verb-lookup endpoints.
 * GET /api/verbs        — paginated search (q optional)
 * GET /api/verbs/{baseForm} — single verb detail
 */
@RestController
@RequestMapping("/api/verbs")
public class VerbController {

    private final VerbQueryService verbQueryService;

    public VerbController(VerbQueryService verbQueryService) {
        this.verbQueryService = verbQueryService;
    }

    /**
     * Search verbs with optional keyword.
     *
     * @param q    search term (base_form or meaning_vi contains, case-insensitive); empty = return all
     * @param page zero-based page index (default 0, min 0)
     * @param size page size (default 20, range [1,100])
     */
    @GetMapping
    public ResponseEntity<VerbSearchPageResponse> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(verbQueryService.search(q, page, size));
    }

    /**
     * Fetch a single verb by its base form (case-insensitive path lookup).
     *
     * @param baseForm e.g. "go", "Go", "GO" — all resolve to the same verb
     */
    @GetMapping("/{baseForm}")
    public ResponseEntity<VerbResponse> getByBaseForm(@PathVariable String baseForm) {
        return ResponseEntity.ok(verbQueryService.getByBaseForm(baseForm));
    }
}
