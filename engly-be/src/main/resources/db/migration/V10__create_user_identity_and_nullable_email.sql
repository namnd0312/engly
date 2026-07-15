-- Social login (Google/Facebook) identity linkage.
--
-- app_user_identity — one row per external provider account linked to a local app_user. A single
-- app_user can have many identities (password + Google + Facebook). The provider subject
-- (provider_user_id: Google `sub`, Facebook `id`) is the stable per-provider key; (provider,
-- provider_user_id) is unique so a returning social login always resolves to the same account.
--
-- Nullable email — Facebook may return no email at all, so an SSO account can exist without one.
-- app_user.email drops its NOT NULL; the existing uq_app_user_email UNIQUE still holds because
-- Postgres allows multiple NULLs under a UNIQUE constraint (email-less accounts don't collide).
-- Account linking trusts app_user.email_verified on BOTH sides: a verified social email links to an
-- existing account only when that account is itself verified (password accounts are never verified,
-- so they are not silently merged into).

CREATE TABLE IF NOT EXISTS app_user_identity (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider         varchar(30)  NOT NULL,            -- 'google' | 'facebook'
    provider_user_id varchar(255) NOT NULL,            -- provider subject / id, stable per provider
    email            varchar(255) NULL,                -- email seen from this provider (audit); may be null
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_identity_provider_subject UNIQUE (provider, provider_user_id)
);

-- Fast lookup of all identities for a user.
CREATE INDEX idx_identity_user ON app_user_identity(user_id);

-- SSO-only accounts (notably Facebook without email) may have no email.
ALTER TABLE app_user ALTER COLUMN email DROP NOT NULL;
