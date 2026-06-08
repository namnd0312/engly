-- V5: CSV bulk-import job tracking + per-deck term uniqueness
-- vocab_import_job    — one row per upload→import run; holds progress + lease + crash-resume offset
-- vocab_import_error  — bad-row report rows (skipped lines) for a job
-- Plus: dedupe vocab_word (defensive) then UNIQUE (deck_id, term) → drives ON CONFLICT DO NOTHING idempotency.

CREATE TABLE IF NOT EXISTS vocab_import_job (
    id               bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deck_id          bigint        NOT NULL REFERENCES vocab_deck(id),
    file_path        varchar(500)  NOT NULL,
    file_size        bigint        NOT NULL,
    status           varchar(20)   NOT NULL
        CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    -- Crash-resume checkpoint: exact file byte offset at last committed batch boundary.
    processed_offset bigint        NOT NULL DEFAULT 0,
    processed_count  bigint        NOT NULL DEFAULT 0,
    inserted_count   bigint        NOT NULL DEFAULT 0,
    skipped_count    bigint        NOT NULL DEFAULT 0,
    error_count      bigint        NOT NULL DEFAULT 0,
    -- Lease/heartbeat: which worker owns the job + last liveness write (for stale-reclaim sweep).
    worker_id        varchar(100)  NULL,
    heartbeat_at     timestamptz   NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now()
);

-- Recovery/sweeper scans by status (PENDING or stale RUNNING).
CREATE INDEX idx_vocab_import_job_status ON vocab_import_job(status);

CREATE TABLE IF NOT EXISTS vocab_import_error (
    id          bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id      bigint        NOT NULL REFERENCES vocab_import_job(id) ON DELETE CASCADE,
    line_no     bigint        NOT NULL,
    raw_excerpt varchar(500)  NULL,
    reason      varchar(255)  NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_vocab_import_error_job ON vocab_import_error(job_id);

-- Defensive dedupe BEFORE the unique index (keep lowest id per (deck_id, term)).
-- V4 seed has no dupes today → deletes 0 rows; required so the unique index builds in any env.
DELETE FROM vocab_word w
USING vocab_word d
WHERE w.deck_id = d.deck_id
  AND w.term = d.term
  AND w.id > d.id;

-- Enables INSERT ... ON CONFLICT (deck_id, term) DO NOTHING in the batch upserter.
CREATE UNIQUE INDEX uq_vocab_word_deck_term ON vocab_word(deck_id, term);
