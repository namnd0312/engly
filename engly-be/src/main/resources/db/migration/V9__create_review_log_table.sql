-- V9: Append-only per-review history for the study progress dashboard.
--
-- review_log records ONE immutable row per graded review (POST /api/study/review).
-- Unlike user_card_state (which holds only the current snapshot and is overwritten each
-- review), this table retains full history and is the source for time-series charts
-- (reviews/day, study streak, accuracy trend). History accrues going forward — it cannot
-- be backfilled, since per-review events were never recorded before this table existed.
--
-- Conventions mirror user_card_state: user_sub is the OIDC/app subject string (NO FK to a
-- user table). deck_id is DENORMALIZED (user_card_state has no deck column) so per-deck
-- time-series need no join back through vocab_word.

CREATE TABLE IF NOT EXISTS review_log (
    id            bigint           GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_sub      varchar(100)     NOT NULL,
    vocab_word_id bigint           NOT NULL REFERENCES vocab_word(id) ON DELETE CASCADE,
    deck_id       bigint           NOT NULL REFERENCES vocab_deck(id) ON DELETE CASCADE,
    rating        varchar(8)       NOT NULL,   -- AGAIN | HARD | GOOD | EASY
    prev_state    varchar(16)      NOT NULL,   -- card state BEFORE this review
    new_state     varchar(16)      NOT NULL,   -- card state AFTER this review
    interval_days int              NOT NULL,   -- resulting SM-2 interval
    ease_factor   double precision NOT NULL,   -- resulting SM-2 ease factor
    reviewed_at   timestamptz      NOT NULL DEFAULT now()
);

-- Per-user time-series aggregation (reviews/day, streak, accuracy).
CREATE INDEX idx_review_log_user_time ON review_log(user_sub, reviewed_at);

-- Per-deck time-series aggregation.
CREATE INDEX idx_review_log_user_deck_time ON review_log(user_sub, deck_id, reviewed_at);
