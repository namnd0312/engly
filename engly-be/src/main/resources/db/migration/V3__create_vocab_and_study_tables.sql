-- V3: Create vocabulary and spaced-repetition study tables
-- vocab_deck   — grouping container for vocabulary word sets
-- vocab_word   — individual word entries belonging to a deck
-- user_card_state  — per-user SM-2 SRS state for each vocab word
-- user_study_settings — per-user daily limits configuration

CREATE TABLE IF NOT EXISTS vocab_deck (
    id          bigint          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        varchar(120)    NOT NULL,
    slug        varchar(140)    NOT NULL,
    description varchar(500)    NULL,
    topic       varchar(80)     NULL,
    created_at  timestamptz     NOT NULL DEFAULT now(),
    CONSTRAINT uq_vocab_deck_slug UNIQUE (slug)
);

CREATE TABLE IF NOT EXISTS vocab_word (
    id              bigint          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deck_id         bigint          NOT NULL REFERENCES vocab_deck(id) ON DELETE CASCADE,
    term            varchar(120)    NOT NULL,
    meaning_vi      varchar(255)    NOT NULL,
    ipa             varchar(120)    NULL,
    example_en      varchar(500)    NULL,
    example_vi      varchar(500)    NULL,
    part_of_speech  varchar(40)     NULL,
    verified        boolean         NOT NULL DEFAULT false,
    created_at      timestamptz     NOT NULL DEFAULT now()
);

-- Index for fast deck-scoped word queries
CREATE INDEX idx_vocab_word_deck ON vocab_word(deck_id);

CREATE TABLE IF NOT EXISTS user_card_state (
    id              bigint          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_sub        varchar(100)    NOT NULL,
    vocab_word_id   bigint          NOT NULL REFERENCES vocab_word(id) ON DELETE CASCADE,
    state           varchar(16)     NOT NULL,   -- NEW | LEARNING | REVIEW
    ease_factor     double precision NOT NULL DEFAULT 2.5,
    interval_days   int             NOT NULL DEFAULT 0,
    repetitions     int             NOT NULL DEFAULT 0,
    lapses          int             NOT NULL DEFAULT 0,
    due_on          date            NULL,
    last_reviewed_at timestamptz    NULL,
    created_at      timestamptz     NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_card UNIQUE (user_sub, vocab_word_id)
);

-- Index for efficient due-card queue queries per user
CREATE INDEX idx_user_card_due ON user_card_state(user_sub, due_on);

CREATE TABLE IF NOT EXISTS user_study_settings (
    user_sub            varchar(100)    PRIMARY KEY,
    new_cards_per_day   int             NOT NULL DEFAULT 20,
    max_reviews_per_day int             NULL,   -- NULL = unlimited
    updated_at          timestamptz     NOT NULL DEFAULT now()
);
