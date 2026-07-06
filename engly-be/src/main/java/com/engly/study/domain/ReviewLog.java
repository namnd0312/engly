package com.engly.study.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity mapping the review_log table — an append-only record of a single graded review.
 *
 * <p>One row is written per POST /api/study/review. Rows are never updated or deleted by the
 * app; the table is the historical source for the progress dashboard's time-series charts
 * (reviews/day, streak, accuracy). This is distinct from {@link UserCardState}, which stores
 * only the latest snapshot per card.
 *
 * <p>{@code userSub} is the subject string (no FK to a user table); {@code deckId} is
 * denormalized so per-deck aggregation needs no join. Rating and card states are stored as
 * their enum names ({@code String}) to keep the domain entity free of an api-layer dependency.
 */
@Entity
@Table(name = "review_log")
public class ReviewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Subject string identifying the reviewer — matches user_card_state.user_sub. */
    @Column(name = "user_sub", nullable = false, length = 100)
    private String userSub;

    /** FK to vocab_word.id — the word that was reviewed. */
    @Column(name = "vocab_word_id", nullable = false)
    private Long vocabWordId;

    /** Denormalized FK to vocab_deck.id — avoids a join for per-deck time-series. */
    @Column(name = "deck_id", nullable = false)
    private Long deckId;

    /** Self-rating chosen by the user: AGAIN | HARD | GOOD | EASY (enum name). */
    @Column(name = "rating", nullable = false, length = 8)
    private String rating;

    /** Card learning state BEFORE this review (enum name). */
    @Column(name = "prev_state", nullable = false, length = 16)
    private String prevState;

    /** Card learning state AFTER this review (enum name). */
    @Column(name = "new_state", nullable = false, length = 16)
    private String newState;

    /** Resulting SM-2 interval (days) after this review. */
    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    /** Resulting SM-2 ease factor after this review. */
    @Column(name = "ease_factor", nullable = false)
    private double easeFactor;

    /** When the review happened. Set explicitly from StudyClock (Saigon) for testable bucketing. */
    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    /** Required by JPA. */
    public ReviewLog() {}

    /**
     * Static factory: build one review-log row from the outcome of a review.
     * Enum values are converted to their names by the caller-facing overload; this overload
     * takes the pre-resolved strings so the domain entity stays independent of the api layer.
     */
    public static ReviewLog of(String userSub, Long vocabWordId, Long deckId,
                               String rating, CardLearningState prevState,
                               CardLearningState newState, int intervalDays,
                               double easeFactor, Instant reviewedAt) {
        ReviewLog log = new ReviewLog();
        log.userSub = userSub;
        log.vocabWordId = vocabWordId;
        log.deckId = deckId;
        log.rating = rating;
        log.prevState = prevState.name();
        log.newState = newState.name();
        log.intervalDays = intervalDays;
        log.easeFactor = easeFactor;
        log.reviewedAt = reviewedAt;
        return log;
    }

    // Getters
    public Long getId()            { return id; }
    public String getUserSub()     { return userSub; }
    public Long getVocabWordId()   { return vocabWordId; }
    public Long getDeckId()        { return deckId; }
    public String getRating()      { return rating; }
    public String getPrevState()   { return prevState; }
    public String getNewState()    { return newState; }
    public int getIntervalDays()   { return intervalDays; }
    public double getEaseFactor()  { return easeFactor; }
    public Instant getReviewedAt() { return reviewedAt; }
}
