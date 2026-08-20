-- V11: Email activation on registration.
-- email_verification_token — hashed, single-use, time-limited tokens (mirrors refresh_token).
-- Only the SHA-256 hash is stored; the raw token lives solely in the emailed link.

CREATE TABLE email_verification_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_evt_token_hash ON email_verification_token(token_hash);
CREATE INDEX ix_evt_user_id ON email_verification_token(user_id);

-- Grandfather existing password accounts so the new login gate does not lock out the entire
-- current user base (they were all created email_verified=false and there was no verification
-- step before this feature).
UPDATE app_user SET email_verified = true
WHERE password_hash IS NOT NULL AND email_verified = false;
