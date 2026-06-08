package com.engly.study.infrastructure;

import com.engly.study.domain.CardLearningState;
import com.engly.study.domain.UserCardState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for UserCardState.
 * Provides lookup, count, and queue-building queries for the study module (P3).
 */
@Repository
public interface UserCardStateJpaRepository extends JpaRepository<UserCardState, Long> {

    /**
     * Derived query: finds a single card state for a specific user + word pair.
     * Matches the DB UNIQUE constraint (user_sub, vocab_word_id).
     */
    Optional<UserCardState> findByUserSubAndVocabWordId(String userSub, Long vocabWordId);

    /**
     * Due cards for today's session: user + deck, state in LEARNING/REVIEW, due_on <= today.
     * Ordered by due_on ascending so oldest-due cards surface first.
     */
    @Query("""
            SELECT s FROM UserCardState s
            WHERE s.userSub = :userSub
              AND s.vocabWordId IN (
                  SELECT w.id FROM VocabWord w WHERE w.deck.id = :deckId
              )
              AND s.state IN :states
              AND s.dueOn <= :today
            ORDER BY s.dueOn ASC
            """)
    List<UserCardState> findDueCards(
            @Param("userSub") String userSub,
            @Param("deckId") Long deckId,
            @Param("states") List<CardLearningState> states,
            @Param("today") LocalDate today);

    /**
     * Word IDs in this deck for which the user already has a card-state row.
     * Used to compute the complement (words without state = new words).
     */
    @Query("""
            SELECT s.vocabWordId FROM UserCardState s
            WHERE s.userSub = :userSub
              AND s.vocabWordId IN (
                  SELECT w.id FROM VocabWord w WHERE w.deck.id = :deckId
              )
            """)
    List<Long> findWordIdsWithStateForDeck(
            @Param("userSub") String userSub,
            @Param("deckId") Long deckId);

    /**
     * COUNT of state rows in this deck for the user — avoids loading the full ID list (N+1 fix).
     * Used to compute newCount = totalWords - wordsWithState.
     */
    @Query("""
            SELECT COUNT(s) FROM UserCardState s
            WHERE s.userSub = :userSub
              AND s.vocabWordId IN (
                  SELECT w.id FROM VocabWord w WHERE w.deck.id = :deckId
              )
            """)
    long countByUserSubAndDeckId(
            @Param("userSub") String userSub,
            @Param("deckId") Long deckId);

    /**
     * Count of new-card-state rows introduced today for this user (across all decks).
     * "Introduced today" = created_at (DB default now()) cast to Saigon date = today.
     * Uses AT TIME ZONE to convert UTC created_at to Asia/Saigon local date.
     */
    @Query(value = """
            SELECT COUNT(*) FROM user_card_state
            WHERE user_sub = :userSub
              AND (created_at AT TIME ZONE 'Asia/Saigon')::date = :today
            """, nativeQuery = true)
    long countIntroducedToday(
            @Param("userSub") String userSub,
            @Param("today") LocalDate today);

    /**
     * Count of due review cards today for a user across all decks (for max_reviews_per_day cap).
     */
    @Query("""
            SELECT COUNT(s) FROM UserCardState s
            WHERE s.userSub = :userSub
              AND s.state IN :states
              AND s.dueOn <= :today
            """)
    long countDueCards(
            @Param("userSub") String userSub,
            @Param("states") List<CardLearningState> states,
            @Param("today") LocalDate today);

    /** Count of state rows with state=REVIEW for user+deck (learnedCount). */
    @Query("""
            SELECT COUNT(s) FROM UserCardState s
            WHERE s.userSub = :userSub
              AND s.vocabWordId IN (
                  SELECT w.id FROM VocabWord w WHERE w.deck.id = :deckId
              )
              AND s.state = :state
            """)
    long countByUserSubAndDeckIdAndState(
            @Param("userSub") String userSub,
            @Param("deckId") Long deckId,
            @Param("state") CardLearningState state);

    /** Count of due cards (LEARNING + REVIEW, due_on <= today) for user+deck. */
    @Query("""
            SELECT COUNT(s) FROM UserCardState s
            WHERE s.userSub = :userSub
              AND s.vocabWordId IN (
                  SELECT w.id FROM VocabWord w WHERE w.deck.id = :deckId
              )
              AND s.state IN :states
              AND s.dueOn <= :today
            """)
    long countDueByUserSubAndDeckId(
            @Param("userSub") String userSub,
            @Param("deckId") Long deckId,
            @Param("states") List<CardLearningState> states,
            @Param("today") LocalDate today);
}
