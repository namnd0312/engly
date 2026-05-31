package com.engly.verb.infrastructure;

import com.engly.verb.domain.IrregularVerb;
import com.engly.verb.domain.VerbRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA implementation of the VerbRepository port.
 * Uses parameterized JPQL — no string concatenation, no injection risk.
 */
@Repository
public interface VerbJpaRepository extends JpaRepository<IrregularVerb, Long>, VerbRepository {

    /**
     * Case-insensitive contains search across base_form and meaning_vi.
     * Called only when q is non-blank; caller must pre-escape % _ ! via escapeLike().
     * ESCAPE '!' ensures pre-escaped sequences are interpreted literally by the DB
     * ('!' chosen over '\' which is ambiguous in JPQL string literals).
     */
    @Query("""
            SELECT v FROM IrregularVerb v
            WHERE lower(v.baseForm) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
               OR lower(v.meaningVi) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
            """)
    @Override
    Page<IrregularVerb> search(@Param("q") String q, Pageable pageable);

    /** Derived query — Spring Data generates the case-insensitive equality check. */
    @Override
    Optional<IrregularVerb> findByBaseFormIgnoreCase(String baseForm);
}
