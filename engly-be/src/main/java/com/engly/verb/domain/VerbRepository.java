package com.engly.verb.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

/**
 * Port interface for verb persistence operations.
 * Infrastructure impl: VerbJpaRepository (Spring Data JPA).
 */
public interface VerbRepository {

    /**
     * Case-insensitive contains search on base_form OR meaning_vi.
     * Only called when q is non-blank; blank/null handled by findAll.
     */
    Page<IrregularVerb> search(String q, Pageable pageable);

    /** M2: efficient full-table page — used when q is null/blank (bypasses LIKE). */
    Page<IrregularVerb> findAll(Pageable pageable);

    /** Lookup by exact base_form, case-insensitive. */
    Optional<IrregularVerb> findByBaseFormIgnoreCase(String baseForm);
}
