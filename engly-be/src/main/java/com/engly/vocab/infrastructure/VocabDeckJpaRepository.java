package com.engly.vocab.infrastructure;

import com.engly.vocab.domain.VocabDeck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for VocabDeck.
 * Extends JpaRepository for standard CRUD + pagination.
 * findBySlug supports stable URL-based lookups used by deck API and seed resolution.
 */
@Repository
public interface VocabDeckJpaRepository extends JpaRepository<VocabDeck, Long> {

    /** Derived query: finds a deck by its unique slug. Used by admin and study API. */
    Optional<VocabDeck> findBySlug(String slug);

    /**
     * Case-insensitive LIKE search across name and topic.
     * Caller must pre-escape %, _, ! via escapeLike() before passing q.
     * ESCAPE '!' ensures pre-escaped sequences are interpreted literally by the DB.
     */
    @Query("""
            SELECT d FROM VocabDeck d
            WHERE lower(d.name) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
               OR lower(d.topic) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
            """)
    Page<VocabDeck> search(@Param("q") String q, Pageable pageable);
}
