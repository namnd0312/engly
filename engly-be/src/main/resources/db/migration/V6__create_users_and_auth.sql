-- V6: Local app-owned authentication.
-- The app now issues its own JWTs (email/password sign-up) and uses OPEN-GATE only as an SSO
-- option. Identity is now persisted locally instead of read entirely from OPEN-GATE claims.
--
-- app_user        — one row per account (password and/or SSO). `sub` is the canonical user key.
-- app_user_role   — Spring-form roles per user (ROLE_USER, ROLE_ADMIN), the single RBAC source.
-- refresh_token   — hashed, rotating refresh tokens for the httpOnly-cookie session.
--
-- Canonical key note: `sub` is what flows into the app JWT subject and is what the existing
-- study tables (user_card_state.user_sub, user_study_settings.user_sub) are keyed by. Password
-- users get a generated sub; SSO users keep OPEN-GATE's sub so prior study progress stays linked.

CREATE TABLE IF NOT EXISTS app_user (
    id            uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    sub           varchar(100)  NOT NULL,
    email         varchar(255)  NOT NULL,
    password_hash varchar(255)  NULL,            -- NULL for SSO-only accounts (no local password)
    display_name  varchar(255)  NULL,
    email_verified boolean      NOT NULL DEFAULT false,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_sub   UNIQUE (sub),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS app_user_role (
    user_id uuid        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role    varchar(50) NOT NULL,
    CONSTRAINT pk_app_user_role PRIMARY KEY (user_id, role)
);

CREATE TABLE IF NOT EXISTS refresh_token (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash varchar(255) NOT NULL,            -- SHA-256 of the opaque token; raw value never stored
    expires_at timestamptz  NOT NULL,
    revoked    boolean      NOT NULL DEFAULT false,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

-- Fast lookup of a user's live refresh tokens (rotation + logout revocation).
CREATE INDEX idx_refresh_token_user ON refresh_token(user_id);
