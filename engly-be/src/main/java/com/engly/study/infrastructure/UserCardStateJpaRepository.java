package com.engly.study.infrastructure;

import com.engly.study.domain.CardLearningState;
import com.engly.study.domain.UserCardState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
     * Count of new-card-state rows introduced today for this user WITHIN a single deck.
     * The daily new-card allowance is per-deck: finishing today's new cards in one deck
     * must not stop other decks from introducing new cards.
     * "Introduced today" = created_at (DB default now()) cast to Saigon date = today.
     * Uses AT TIME ZONE to convert UTC created_at to Asia/Saigon local date.
     */
    @Query(value = """
            SELECT COUNT(*) FROM user_card_state cs
            WHERE cs.user_sub = :userSub
              AND cs.vocab_word_id IN (
                  SELECT w.id FROM vocab_word w WHERE w.deck_id = :deckId
              )
              AND (cs.created_at AT TIME ZONE 'Asia/Saigon')::date = :today
            """, nativeQuery = true)
    long countIntroducedTodayForDeck(
            @Param("userSub") String userSub,
            @Param("deckId") Long deckId,
            @Param("today") LocalDate today);

    /**
     * Count of cards the user has reviewed TODAY across all decks (for the max_reviews_per_day cap).
     * "Reviewed today" = last_reviewed_at cast to Saigon date = today. Global (deck-independent),
     * since the review limit is a per-user daily budget shared across decks.
     */
    @Query(value = """
            SELECT COUNT(*) FROM user_card_state
            WHERE user_sub = :userSub
              AND last_reviewed_at IS NOT NULL
              AND (last_reviewed_at AT TIME ZONE 'Asia/Saigon')::date = :today
            """, nativeQuery = true)
    long countReviewedTodayForUser(
            @Param("userSub") String userSub,
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

    // --- Progress dashboard aggregates (global, deck-independent) ---

    /** Total card-state rows for the user across all decks (totalCards KPI). */
    long countByUserSub(String userSub);

    /** Global count of cards in a given state (mastery breakdown). */
    long countByUserSubAndState(String userSub, CardLearningState state);

    /** Global count of due cards (states + due_on <= today) across all decks (dueToday KPI). */
    long countByUserSubAndStateInAndDueOnLessThanEqual(
            String userSub, List<CardLearningState> states, LocalDate today);

    /**
     * New cards introduced grouped by Saigon-local day (created_at), from :from onward.
     * created_at is immutable, so this has real history predating review_log.
     * Each row = [ 'YYYY-MM-DD' (String), count (Number) ].
     */
    @Query(value = """
            SELECT to_char((created_at AT TIME ZONE 'Asia/Saigon')::date, 'YYYY-MM-DD') AS day,
                   COUNT(*) AS total
            FROM user_card_state
            WHERE user_sub = :userSub AND created_at >= :from
            GROUP BY day
            """, nativeQuery = true)
    List<Object[]> newCardsPerDay(@Param("userSub") String userSub, @Param("from") Instant from);

    /**
     * Cards due per day up to :to for the given states (forecast). :from is "today".
     * Overdue cards (due_on &lt; today) are collapsed into the today bucket via GREATEST, so the
     * forecast's first bar matches the dueToday KPI (both count due_on &lt;= today). due_on is a
     * DATE column; states passed as enum-name strings for the native IN clause.
     * Each row = [ 'YYYY-MM-DD' (String), count (Number) ].
     */
    @Query(value = """
            SELECT to_char(GREATEST(due_on, :from), 'YYYY-MM-DD') AS day, COUNT(*) AS total
            FROM user_card_state
            WHERE user_sub = :userSub
              AND state IN (:states)
              AND due_on IS NOT NULL
              AND due_on <= :to
            GROUP BY day
            """, nativeQuery = true)
    List<Object[]> duePerDay(
            @Param("userSub") String userSub,
            @Param("states") List<String> states,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
