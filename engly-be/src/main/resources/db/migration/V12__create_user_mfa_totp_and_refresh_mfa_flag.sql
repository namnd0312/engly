-- V12: Admin two-factor auth (TOTP, step-up).
-- user_mfa_totp — at most one TOTP enrollment per user. The secret is stored AES-GCM encrypted
-- (key from MFA_SECRET_KEY); the raw secret exists only in the enrollment QR / authenticator app.
-- A row with confirmed_at IS NULL is an enrollment that was started but never confirmed with a
-- valid code; it is overwritten by the next enrollment attempt and never grants access.
--
-- refresh_token.mfa_verified — carries the step-up across refresh rotation so an admin who has
-- entered a code keeps `mfa=true` in each re-minted access JWT for the life of the refresh chain.
-- Login/SSO always start a chain with false; only POST /api/auth/mfa/verify flips it to true.

CREATE TABLE user_mfa_totp (
    user_id         uuid        PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    secret_enc      varchar(255) NOT NULL,     -- base64url(iv || AES-GCM ciphertext); raw secret never stored
    confirmed_at    timestamptz NULL,          -- NULL = enrollment started but code not yet confirmed
    last_used_step  bigint      NULL,          -- highest accepted TOTP step; rejects replay inside the window
    failed_attempts int         NOT NULL DEFAULT 0,
    locked_until    timestamptz NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE refresh_token ADD COLUMN mfa_verified boolean NOT NULL DEFAULT false;
