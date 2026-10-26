---
title: Admin TOTP step-up 2FA
description: >-
  TOTP (RFC 6238) step-up second factor required for ROLE_ADMIN to reach
  /api/admin/**; self-implemented codec, AES-GCM secret at rest, no backup codes
status: completed
priority: P2
branch: main
tags:
  - security
  - auth
  - identity
  - angular
  - spring-security
blockedBy: []
blocks: []
created: '2026-10-23T04:35:48.935Z'
createdBy: 'ck:plan'
source: skill
---

# Admin TOTP step-up 2FA

## Overview

Add a second factor for admins without touching the login flow. Login (password or Google/Facebook) keeps issuing a session, but the access JWT now carries `mfa=false`. `/api/admin/**` additionally requires `mfa=true`; an admin reaches that by enrolling a TOTP app on the profile page and then verifying a 6-digit code, which re-mints the access cookie and flags the refresh token so rotation keeps the step-up for the refresh lifetime. Regular users see no behavioral change.

Source of decisions: `plans/reports/brainstorm-261021-2324-admin-totp-step-up-2fa-report.md` (admin-only mandatory, TOTP self-implemented, step-up gate, AES-GCM secret, DB-reset recovery, no backup codes, SSO covered automatically). Threat model: `plans/reports/security-audit-260820-2222-auth-hardening-and-2fa-necessity-report.md`.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [TOTP codec and secret cipher](./phase-01-totp-codec-and-secret-cipher.md) | Completed |
| 2 | [MFA persistence and service](./phase-02-mfa-persistence-and-service.md) | Completed |
| 3 | [Session claim and admin gate](./phase-03-session-claim-and-admin-gate.md) | Completed |
| 4 | [Frontend enrollment and verify](./phase-04-frontend-enrollment-and-verify.md) | Completed |
| 5 | [Integration tests and docs](./phase-05-integration-tests-and-docs.md) | Completed |

Order is strict: 1 → 2 → 3 → 4 → 5. Phases 1–3 are backend-only and each leaves `./mvnw test` green. Phase 4 depends on the API contract fixed in phase 3.

## Key contracts (fixed here, referenced by every phase)

- JWT claim `mfa` (boolean) in the app access token. Absent == false.
- `refresh_token.mfa_verified` boolean; `rotate()` copies it, `issueSession()` writes false.
- Error codes via `GlobalExceptionHandler` body `{status,message,code}`:
  `MFA_REQUIRED` 403, `MFA_ENROLLMENT_REQUIRED` 403, `INVALID_MFA_CODE` 400, `MFA_LOCKED` 429, `MFA_NOT_ENROLLED` 409, `MFA_ALREADY_ENROLLED` 409.
- Endpoints: `POST /api/profile/mfa/enroll`, `POST /api/profile/mfa/confirm`, `DELETE /api/profile/mfa`, `POST /api/auth/mfa/verify`. Code DTO: `{ "code": "123456" }`.
- `/api/me` and auth responses gain `mfaEnrolled`, `mfaVerified` booleans.
- Env `MFA_SECRET_KEY` → `app.security.mfa.secret-key`; blank = ephemeral key + WARN (dev only).

## Out of scope

Backup codes, trusted-device cookie, `/login` rate limiting, re-auth for destructive admin actions, mandatory 2FA for regular users, audit log. Recovery = SQL reset documented in phase 5.

## Acceptance criteria (whole plan)

- Admin with `mfa=false` → `GET /api/admin/decks` returns 403 `MFA_ENROLLMENT_REQUIRED` (not enrolled) or `MFA_REQUIRED` (enrolled).
- After a correct `POST /api/auth/mfa/verify`, the same request returns 200; after `POST /api/auth/refresh` it still returns 200.
- 5 wrong codes → 429 `MFA_LOCKED` for 15 minutes; a code already accepted in the same 30s step is rejected.
- Google-login admin who is enrolled is blocked from `/admin` in the SPA until verified.
- `TotpCodecTest` passes RFC 6238 Appendix B SHA-1 vectors.
- Existing backend tests (24) and frontend specs (13) still pass; regular-user flows unchanged.

## Dependencies

None cross-plan. Older `pending` plans in `plans/` predate shipped features and do not overlap.
