package com.engly.study.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA entity mapping the user_card_state table.
 * Stores per-user SM-2 spaced-repetition state for a single vocabulary word.
 * One row per (user_sub, vocab_word_id) pair — enforced by DB UNIQUE constraint.
 */
@Entity
@Table(name = "user_card_state")
public class UserCardState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OIDC subject claim — opaque string, no FK to a user table. */
    @Column(name = "user_sub", nullable = false, length = 100)
    private String userSub;

    /** FK to vocab_word.id — stored as plain Long (no JPA relationship needed here). */
    @Column(name = "vocab_word_id", nullable = false)
    private Long vocabWordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private CardLearningState state;

    /** SM-2 ease factor (default 2.5). Controls interval growth after correct answers. */
    @Column(name = "ease_factor", nullable = false)
    private double easeFactor;

    /** Days until the next review (0 = same day / not yet scheduled). */
    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    /** Number of successful consecutive repetitions. */
    @Column(name = "repetitions", nullable = false)
    private int repetitions;

    /** Number of times the card was forgotten (answer rated < 3). */
    @Column(name = "lapses", nullable = false)
    private int lapses;

    /** Date the card is next due for review; null for NEW cards. */
    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    /** DB DEFAULT now() owns insertion; insertable=false prevents JPA from sending null. */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    public UserCardState() {}

    /**
     * Static factory: creates a brand-new card state for a user/word pair.
     * Defaults: EF=2.5, state=NEW, reps=0, interval=0, lapses=0, dueOn=null.
     */
    public static UserCardState newCard(String userSub, Long vocabWordId) {
        UserCardState s = new UserCardState();
        s.userSub = userSub;
        s.vocabWordId = vocabWordId;
        s.state = CardLearningState.NEW;
        s.easeFactor = 2.5;
        s.intervalDays = 0;
        s.repetitions = 0;
        s.lapses = 0;
        s.dueOn = null;
        s.lastReviewedAt = null;
        return s;
    }

    /**
     * Full-state mutator applied by the P3 study scheduler after each review.
     * Called on a managed entity inside a @Transactional write.
     */
    public void applyScheduling(CardLearningState state, double easeFactor,
                                int intervalDays, int repetitions, int lapses,
                                LocalDate dueOn, Instant lastReviewedAt) {
        this.state = state;
        this.easeFactor = easeFactor;
        this.intervalDays = intervalDays;
        this.repetitions = repetitions;
        this.lapses = lapses;
        this.dueOn = dueOn;
        this.lastReviewedAt = lastReviewedAt;
    }

    // Getters
    public Long getId()               { return id; }
    public String getUserSub()        { return userSub; }
    public Long getVocabWordId()      { return vocabWordId; }
    public CardLearningState getState() { return state; }
    public double getEaseFactor()     { return easeFactor; }
    public int getIntervalDays()      { return intervalDays; }
    public int getRepetitions()       { return repetitions; }
    public int getLapses()            { return lapses; }
    public LocalDate getDueOn()       { return dueOn; }
    public Instant getLastReviewedAt() { return lastReviewedAt; }
    public Instant getCreatedAt()     { return createdAt; }
}
