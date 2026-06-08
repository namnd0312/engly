package com.engly.vocab.infrastructure;

import com.engly.vocab.domain.VocabWord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for VocabWord.
 * findByDeck_Id traverses the @ManyToOne deck relationship to filter by deck id.
 * Spring Data resolves "Deck_Id" as deck.id via the VocabWord.deck field.
 */
@Repository
public interface VocabWordJpaRepository extends JpaRepository<VocabWord, Long> {

    /**
     * Derived query: returns a paginated list of words belonging to the given deck id.
     * The underscore notation (Deck_Id) explicitly tells Spring Data to traverse
     * the 'deck' association and then access its 'id' field.
     * EntityGraph ensures deck is JOIN-fetched to avoid N+1 when toDto() calls word.getDeck().
     */
    @EntityGraph(attributePaths = {"deck"})
    Page<VocabWord> findByDeck_Id(Long deckId, Pageable pageable);

    /**
     * Non-paginated list of all words in a deck — used by queue builder to find new words.
     * Returns only the IDs to avoid loading full entities before we know which ones are new.
     */
    @Query("SELECT w.id FROM VocabWord w WHERE w.deck.id = :deckId")
    List<Long> findIdsByDeckId(@Param("deckId") Long deckId);

    /** Count of words in a deck — used for newCount calculation on deck summary. */
    long countByDeck_Id(Long deckId);

    /**
     * Override findAll(Pageable) with EntityGraph so deck is JOIN-fetched for all-words listing.
     * Prevents N+1 when toDto() calls word.getDeck().getId().
     */
    @EntityGraph(attributePaths = {"deck"})
    Page<VocabWord> findAll(Pageable pageable);

    /**
     * Case-insensitive LIKE search across term and meaningVi.
     * Caller must pre-escape %, _, ! via escapeLike() before passing q.
     * ESCAPE '!' ensures pre-escaped sequences are interpreted literally by the DB.
     * EntityGraph ensures deck is JOIN-fetched to avoid N+1.
     */
    @EntityGraph(attributePaths = {"deck"})
    @Query("""
            SELECT w FROM VocabWord w
            WHERE lower(w.term) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
               OR lower(w.meaningVi) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
            """)
    Page<VocabWord> search(@Param("q") String q, Pageable pageable);

    /**
     * Combined deck+term search: filters by deckId AND (term OR meaningVi) LIKE q.
     * Fixes M3 bug where deckId filter ignored q when both provided.
     * ESCAPE '!' matches escapeLike() in VocabWordAdminService.
     * EntityGraph ensures deck is JOIN-fetched to avoid N+1.
     */
    @EntityGraph(attributePaths = {"deck"})
    @Query("""
            SELECT w FROM VocabWord w
            WHERE w.deck.id = :deckId
              AND (lower(w.term) LIKE lower(concat('%', :q, '%')) ESCAPE '!'
                OR lower(w.meaningVi) LIKE lower(concat('%', :q, '%')) ESCAPE '!')
            """)
    Page<VocabWord> searchByDeck(@Param("deckId") Long deckId, @Param("q") String q, Pageable pageable);
}
