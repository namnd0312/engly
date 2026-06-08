package com.engly.study.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity mapping the user_study_settings table.
 * PK is user_sub (OIDC subject) — no surrogate id needed (one row per user).
 * Controls daily card limits for the study session queue builder (P3).
 */
@Entity
@Table(name = "user_study_settings")
public class UserStudySettings {

    /** OIDC subject claim used directly as primary key — no surrogate needed. */
    @Id
    @Column(name = "user_sub", length = 100)
    private String userSub;

    /** Maximum new cards introduced per day (default 20). */
    @Column(name = "new_cards_per_day", nullable = false)
    private int newCardsPerDay;

    /** Maximum review cards per day; null means unlimited. */
    @Column(name = "max_reviews_per_day")
    private Integer maxReviewsPerDay;

    /**
     * Tracks last settings change. insertable=false: DB default now() writes on INSERT.
     * updatable=true: service calls bumpUpdatedAt() before save on UPDATE.
     */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = true)
    private Instant updatedAt;

    /** Required by JPA. */
    public UserStudySettings() {}

    /**
     * Static factory: creates default settings for a new user.
     * Defaults: newCardsPerDay=20, maxReviewsPerDay=null (unlimited).
     */
    public static UserStudySettings defaults(String userSub) {
        UserStudySettings s = new UserStudySettings();
        s.userSub = userSub;
        s.newCardsPerDay = 20;
        s.maxReviewsPerDay = null;
        return s;
    }

    /**
     * Mutator for the settings update endpoint.
     * Called on a managed entity inside a @Transactional write.
     */
    public void update(int newCardsPerDay, Integer maxReviewsPerDay) {
        this.newCardsPerDay = newCardsPerDay;
        this.maxReviewsPerDay = maxReviewsPerDay;
    }

    /**
     * Bumps updatedAt app-side. Call before save on every update.
     * Required because insertable=false prevents DB default from re-firing on UPDATE.
     */
    public void bumpUpdatedAt(Instant now) {
        this.updatedAt = now;
    }

    // Getters
    public String getUserSub()          { return userSub; }
    public int getNewCardsPerDay()      { return newCardsPerDay; }
    public Integer getMaxReviewsPerDay() { return maxReviewsPerDay; }
    public Instant getUpdatedAt()       { return updatedAt; }
}
