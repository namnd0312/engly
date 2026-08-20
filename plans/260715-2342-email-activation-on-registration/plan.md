---
title: "Email activation on registration"
description: "Send an activation email on sign-up; account is unusable until the emailed link is clicked (hard gate)."
status: completed
priority: P2
branch: "main"
tags: [auth, email, security]
blockedBy: []
blocks: []
created: "2026-07-15T16:49:01.298Z"
createdBy: "ck:plan"
source: skill
---

# Email activation on registration

## Overview

New password sign-ups must confirm email ownership before the account works.
Registration creates the account but **issues no session**; the backend emails a
one-click activation link. Login is rejected for unverified accounts (403) with a
"resend" path. Clicking the link marks `email_verified=true`, and the verify
endpoint issues the session so the user lands logged in.

Confirmed decisions (user, 2026-07-15):
- **Hard gate** — no session until verified; login blocked for unverified.
- **Generic SMTP** via `spring-boot-starter-mail` (JavaMailSender), provider-agnostic.
- **Tokenized link** — opaque token, SHA-256 hashed in DB, single-use, time-limited.

Social login (Google/Facebook) does not pass through this password flow, but it is
**not fully independent**: activating a password account sets `email_verified=true`,
which makes it a valid target for SSO account-linking (`SocialLoginService` links only
into verified accounts). Decision: allow that merge (one account for a matching verified
email); it is documented and tested — see Phase 2 "SSO linking interaction".

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Backend data & mail infra](./phase-01-backend-data-mail-infra.md) | Completed |
| 2 | [Backend API wiring](./phase-02-backend-api-wiring.md) | Completed |
| 3 | [Frontend activation flow](./phase-03-frontend-activation-flow.md) | Completed |
| 4 | [Tests](./phase-04-tests.md) | Completed (318 BE + 181 FE tests pass) |
| 5 | [Docs & config](./phase-05-docs-config.md) | Completed |

## Implementation Notes (post-plan)

- **Kill-switch FE gap (fixed):** initial FE implementation ignored the backend's kill-switch
  response shape — `EMAIL_VERIFICATION_ENABLED=false` issues a real session (200 + identity +
  cookies) but the register page unconditionally showed the "check your inbox" confirmation and
  never logged the user in. Fixed: `AuthService.register()` now distinguishes the two response
  shapes (`isAdmin` present ⇒ session issued ⇒ set user + hydrate, same as login) and
  `RegisterPageComponent` navigates home instead of showing confirmation when a session was issued.
- **`UserAccount.updatedAt` fix:** `markVerifiedImmediately` (kill-switch path) originally called
  `.save()` on an entity that had crossed a request/transaction boundary from
  `UserAccountService.register()`. Combined with `spring.jpa.open-in-view` (one EntityManager per
  HTTP request), the entity's never-refreshed `updatedAt` (insertable=false, no `@Generated`) got
  merged back as NULL on the next flush → NOT NULL violation on `POST /api/auth/register` with the
  kill-switch off. Fixed by making `updatedAt` `updatable=false` (matches `createdAt`'s mapping —
  the app layer never writes this column) and re-fetching by id inside `markVerifiedImmediately`
  instead of saving the caller's entity.
- **V11 backfill test added:** `EmailVerificationBackfillMigrationTest` runs Flyway directly
  (no Spring context) to pause at V10, seed pre-feature fixture rows, apply V11, and assert the
  backfill — closing the coverage gap phase-04 flagged as hard to test post-hoc.
- **Non-fatal-SMTP proof added:** `ActivationEmailSenderTest` unit-tests the actual try/catch in
  `ActivationEmailSender.send()` against a throwing `JavaMailSender` mock (the integration test
  mocks `ActivationEmailSender` itself to capture tokens, which bypasses that safety net).

## Dependencies

- Phase order is sequential: 2 depends on 1 (service + entity), 3 depends on 2
  (endpoints/contracts), 4 depends on 1–3, 5 is independent but finalize last.
- No cross-plan blockers. Related in-flight plan
  `260712-2311-add-google-facebook-sso-login` touches the same auth area but a
  disjoint code path (oauth2Login vs password register/login); no shared files
  except `SecurityConfig` (only the CSRF ignore-list is edited here — additive).

## Acceptance criteria

- Registering a new email sends an activation email and returns **no auth cookies**.
- Logging in before activation returns **403** with a machine-readable "email not
  verified" signal; the SPA offers "resend activation email".
- Clicking the emailed link activates the account and logs the user in.
- Tokens are single-use (atomic claim), expire (default 24h), and only the hash is stored.
- Resend is generic (no account enumeration) and rate-limited (default 60s).
- **Existing password accounts are grandfathered** by the V11 backfill — no lockout.
- **SMTP failure never fails registration** (email dispatched post-commit, non-fatal).
- **`EMAIL_VERIFICATION_ENABLED=false`** fully reverts to the pre-feature flow (rollback).
- Backend + frontend test suites pass; docs, k8s env, and `.env.example` updated.

## Key design choices (settle once, applied across phases)

- **Verify issues the session** (auto-login on activation) — best UX, avoids a
  second login step. Verify + resend endpoints are `permitAll` and CSRF-ignored,
  same as register/login (they run before an XSRF cookie exists / set cookies).
- **Reuse token hashing**: extract the private `sha256(...)` in `AuthTokenService`
  into a shared package util so both services hash identically (DRY).
- **Plain-text email** (`SimpleMailMessage`) with the link — no HTML templating
  dependency (YAGNI). Can upgrade to MIME/HTML later without contract change.
- **No enumeration on resend**: always return 202 regardless of whether the email
  exists / is already verified; rate-limit silently.

## Red Team Review

### Session — 2026-07-16
3 hostile reviewers (Security Adversary, Assumption Destroyer, Failure Mode Analyst),
all findings grep-verified against the codebase.
**Findings:** 13 accepted (deduped from ~20), 2 deferred-with-documentation.
**Severity breakdown:** 2 Critical, 2 High, 8 Medium, 1 Low.

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Existing password accounts all `email_verified=false` → mass 403 lockout, no backfill | Critical | Accept (grandfather) | Phase 1 (V11 backfill) |
| 2 | Account committed then orphaned when SMTP send fails (500 → 409 dead-end) | Critical | Accept | Phase 1 (post-commit, non-fatal), Phase 2 |
| 3 | Plan falsely claimed "SSO unaffected"; activation flips the SSO-linking trust anchor | High | Accept (allow merge + test) | plan.md, Phase 2, Phase 4 |
| 4 | Prod SMTP not wired in k8s; no rollback switch | High | Accept | Phase 1 (`enabled` flag), Phase 5 (k8s env) |
| 5 | Single-use read-then-write TOCTOU → concurrent verify mints two sessions | Medium | Accept | Phase 1 (atomic `consumeIfUsable`) |
| 6 | `EmailVerificationProperties` had no registration home (would misfile in JwtKeyConfig) | Medium | Accept | Phase 1 (new `MailConfig`) |
| 7 | New route used eager `component:`, breaking the lazy `loadComponent` convention | Medium | Accept | Phase 3 |
| 8 | Web-layer activation test would hit real SMTP | Medium | Accept | Phase 4 (`@MockBean` sender) |
| 9 | Raw token in URL → Referer/history leakage | Medium | Accept | Phase 3 (strip + `no-referrer`) |
| 10 | verify-email is CSRF-exempt + session-creating → login-CSRF / false-expired on refresh | Medium | Accept | Phase 2 (no-clobber), Phase 3 (auth short-circuit) |
| 11 | Adding `code` must not change the shared 4-key error-body shape | Medium | Accept | Phase 2 |
| 12 | Resend invalidates prior tokens → old link dies; unbounded token growth | Medium | Accept | Phase 1 (DELETE-invalidate), Phase 3 (copy) |
| 13 | New auth paths missing from interceptor `SKIP_REFRESH_PATHS` | Low | Accept | Phase 3 |
| — | Global IP rate-limiter for email-bombing | Medium | Defer (documented) | Phase 1 Risk (no limiter infra; per-address cooldown is MVP) |
| — | Scheduled token sweeper | Medium | Defer (documented) | Phase 1 Risk (DELETE-invalidate bounds common path) |

### Whole-Plan Consistency Sweep
Re-read `plan.md` + all 5 phase files after applying findings. Reconciled:
- "SSO unaffected" claim corrected in plan.md overview and Phase 2 (now "allow merge, tested").
- Single-use model changed from entity `consume()` (read-then-write) to repo
  `consumeIfUsable` atomic UPDATE — Phase 1 entity, service, and Phase 4 tests all updated;
  no stale `token.consume(now)` reference remains.
- Prior-token invalidation unified as DELETE across Phase 1 repo/service and Phase 4 test.
- Kill-switch (`enabled`) threaded consistently: Phase 1 props/service, Phase 2 register,
  Phase 4 test, Phase 5 docs.
- Backfill referenced in Phase 1 SQL, plan acceptance, and Phase 5 docs — consistent.
No unresolved contradictions.
