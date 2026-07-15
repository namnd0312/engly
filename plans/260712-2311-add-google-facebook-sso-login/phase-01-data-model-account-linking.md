---
phase: 1
title: Data model & account linking
status: completed
effort: M
priority: P1
dependencies: []
---

# Phase 1: Data model & account linking

## Overview

Persist the link between a provider account (Google/Facebook) and a local `app_user`, and teach
the domain to create/link SSO users. This is the data foundation Phase 2's success handler drives.

## Requirements

- Functional:
  - Store `(provider, provider_user_id) → app_user` so a returning social user resolves to the same account.
  - Allow email-less accounts (Facebook may return no email).
  - Support one `app_user` linked to multiple providers (password + Google + Facebook).
- Non-functional: no breaking change to existing password/study data; `sub` stays the canonical key.

## Architecture

New table `app_user_identity` (one row per linked provider account):

```sql
CREATE TABLE app_user_identity (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider         varchar(30)  NOT NULL,          -- 'google' | 'facebook'
    provider_user_id varchar(255) NOT NULL,          -- OAuth2 subject / FB id, stable per provider
    email            varchar(255) NULL,              -- email seen from THIS provider (audit/debug)
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_identity_provider_subject UNIQUE (provider, provider_user_id)
);
CREATE INDEX idx_identity_user ON app_user_identity(user_id);
```

Make `app_user.email` nullable (currently `NOT NULL`) to support the locked "create account without
email" decision. The existing `uq_app_user_email UNIQUE(email)` stays — Postgres permits multiple
NULLs under a UNIQUE constraint, so email-less accounts don't collide.

Resolution order the domain must support (implemented in Phase 2's service, but the domain methods
live here). **All email comparisons use the normalized (trim + lowercase) email** — reuse
`UserAccountService.normalizeEmail` (extract to a shared util) so SSO matches the case-folded values
password accounts are stored under (red-team Finding #7):

1. Match by `(provider, provider_user_id)` → existing linked user → return it.
2. Else, if the provider is **Google** AND its email is present AND `email_verified == true` →
   look up `app_user` by normalized email:
   - found **AND that account's `email_verified == true`** → **link** (insert `app_user_identity`
     row, return existing user). Both sides are verified, so this is the same human.
   - found but the account is **not** verified (e.g. a password account — password signup never
     verifies email) → do **NOT** merge (pre-account-hijacking risk, red-team Finding #1). Create a
     new SSO account keyed on provider id instead.
   - not found → create new SSO user (`email_verified = true`) + identity row.
3. Else — **Facebook always**, or Google without a verified email → **never email-link** (Finding #2:
   Facebook has no trustworthy `email_verified`). Create a new SSO user keyed on
   `(provider, provider_user_id)`; store the email if present (unverified), else null.

Note: a brand-new social user gets a **fresh** `sub` by design (see step 4); linking into an existing
account keeps that account's existing `sub` so study progress follows it. This reconciles the V6/entity
comment ("SSO keeps the provider sub") — that comment described the old OPEN-GATE model; update it so the
invariant is not self-contradicting (red-team Finding on sub strategy).

## Related Code Files

- Create: `engly-be/src/main/resources/db/migration/V10__create_user_identity_and_nullable_email.sql`
- Create: `engly-be/src/main/java/com/engly/identity/domain/UserIdentity.java` (JPA entity for `app_user_identity`)
- Create: `engly-be/src/main/java/com/engly/identity/infrastructure/UserIdentityRepository.java`
- Modify: `engly-be/src/main/java/com/engly/identity/domain/UserAccount.java`
  - **flip `@Column(name = "email", ..., nullable = false)` → `nullable = true`** (red-team Finding #9):
    the entity annotation currently lies about the schema after V10 drops the DB `NOT NULL`.
  - **extend the private constructor to set `emailVerified`** — it currently omits it, so both
    `localUser` and any new `ssoUser` would persist `email_verified = false` regardless (Finding #9,
    which breaks the verified-both-sides linking rule).
  - add `ssoUser(sub, email, displayName, emailVerified)` factory (null-safe email, default `ROLE_USER`,
    `passwordHash=null`, sets `emailVerified`). Google path passes `true`; Facebook/unverified passes `false`.
  - `localUser(...)` stays `email_verified = false` (unchanged behavior; password signup is unverified).
- Modify: `engly-be/src/main/java/com/engly/identity/infrastructure/UserAccountRepository.java`
  - ensure a `findByEmail` exists (already used by `UserAccountService`); add nothing else unless needed.

## Implementation Steps

1. Write `V10` migration: `CREATE TABLE app_user_identity (...)` + `ALTER TABLE app_user ALTER COLUMN
   email DROP NOT NULL;`. Add the header comment explaining the invariant (email nullable for SSO-only
   accounts; identity table is the provider link). Do NOT put phase/plan ids in the migration.
2. Add `UserIdentity` JPA entity mapping the new table; expose `provider`, `providerUserId`, `email`,
   `userId`. Constructor/factory `link(UUID userId, String provider, String providerUserId, String email)`.
3. Add `UserIdentityRepository` with `Optional<UserIdentity> findByProviderAndProviderUserId(String, String)`.
4. In `UserAccount`: flip `email` `@Column` to `nullable = true`; extend the constructor to assign
   `emailVerified`; add `ssoUser(...)` factory. Use a fresh UUID sub (`"sso-" + UUID.randomUUID()`) for
   new social users to avoid leaking provider ids into the JWT subject; linking reuses the existing
   account's sub. Update the now-stale V6/entity "SSO keeps provider sub" comment.
5. Verify JPA `ddl-auto: validate` still passes against V10 (entity columns match table exactly),
   including the nullable-email flip.

## Success Criteria

- [ ] `V10` migration applies cleanly on a fresh DB and on top of V9 (existing data intact).
- [ ] `app_user.email` accepts NULL; two email-less accounts can coexist.
- [ ] `(provider, provider_user_id)` uniqueness enforced by DB.
- [ ] `UserAccount.ssoUser(...)` produces a valid `passwordHash=null`, `ROLE_USER` account and
      persists `email_verified` per the provider (Google `true`, Facebook/unverified `false`).
- [ ] `email` `@Column` is `nullable = true`; a null-email account persists without a Hibernate not-null error.
- [ ] App boots with `ddl-auto: validate` (schema/entity match, including nullable email).

## Risk Assessment

- **Dropping NOT NULL on email** is one-way in effect but low risk — no code assumes email non-null at
  the DB layer; `UserInfoResponse`/`/api/me` already model email as nullable on the FE (`email: string | null`).
- **Unique-email + linking race**: two concurrent first-time logins could both try to create. The
  constraint that fires (`uq_identity_provider_subject` or `uq_app_user_email`) marks the surrounding
  transaction rollback-only — so a catch-and-retry **inside the same `@Transactional`** dies at commit
  with `UnexpectedRollbackException` (red-team Finding #5). The create MUST run in an inner
  `REQUIRES_NEW` transaction (or the insert-catch must sit outside the resolve transaction), so only the
  inner tx rolls back and the outer re-resolve runs on a clean tx. Specify this in Phase 2 and test it
  with real transactions (not a mocked repo, which hides rollback-only behavior).
- Rollback: drop `app_user_identity`; re-adding NOT NULL requires backfilling emails — acceptable since
  feature is new and no email-less rows exist pre-launch.
