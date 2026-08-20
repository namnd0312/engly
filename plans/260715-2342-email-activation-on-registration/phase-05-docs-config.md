---
phase: 5
title: "Docs & config"
status: completed
effort: "S"
---

# Phase 5: Docs & config

## Overview

Document the new activation flow and SMTP configuration, and surface the new env
vars for local dev and deployment. Finalize after code + tests are green.

## Requirements

- Update only docs affected by the change (security posture, setup/env, auth flow).
- `.env.example` lists every new env var with dev-safe defaults and no secrets.

## Related Code Files

- Modify: `engly-be/.env.example` (MAIL_*, EMAIL_VERIFICATION_* vars)
- Modify: `k8s/20-engly-be-backend.yaml` (inject `MAIL_*` + `EMAIL_VERIFICATION_*` env;
  source SMTP creds from a Secret, not inline) — **required for prod, without it every
  registration silently loses its email**
- Modify: `docs/system-architecture.md` (registration/activation flow, hard gate)
- Modify: `docs/deployment-guide.md` (SMTP env vars, prod sender/domain notes, kill-switch)
- Modify: `README.md` (dev: run Mailpit for local email; note the new flow) — only
  if the local-run section needs it; keep minimal.

## Implementation Steps

1. **`.env.example`**: add
   ```
   # SMTP for account-activation emails (dev: Mailpit on localhost:1025)
   MAIL_HOST=localhost
   MAIL_PORT=1025
   MAIL_USERNAME=
   MAIL_PASSWORD=
   MAIL_SMTP_AUTH=false
   MAIL_SMTP_STARTTLS=false
   MAIL_FROM=no-reply@engly.local
   EMAIL_VERIFICATION_ENABLED=true
   EMAIL_VERIFICATION_TTL=24h
   EMAIL_VERIFICATION_RESEND_COOLDOWN=60s
   ```
   (Read the existing `.env.example` first; match its comment/section style.)

1b. **`k8s/20-engly-be-backend.yaml`**: add the `MAIL_*` and `EMAIL_VERIFICATION_*` env
   entries alongside the existing DB/JWT/S3/FRONTEND_URL vars. Put `MAIL_USERNAME`/
   `MAIL_PASSWORD` in a `Secret` (referenced via `secretKeyRef`), never inline. Confirm
   `app.frontend-url` (`FRONTEND_URL`) is the real SPA origin so activation links resolve.

2. **`docs/system-architecture.md`**: in the auth section, document the hard-gate
   activation flow: register → `email_verification_token` (hashed, single-use, TTL)
   → email link → `verify-email` issues session; login rejects unverified (403).
   Note SSO accounts bypass this (provider sets `emailVerified`).

3. **`docs/deployment-guide.md`**: add an SMTP configuration subsection — the env
   vars, that prod needs a real SMTP relay (e.g. SES-SMTP) and a verified
   `MAIL_FROM` sender/domain, and that `app.frontend-url` must be the real SPA
   origin so activation links resolve. **Document the rollback switch**: setting
   `EMAIL_VERIFICATION_ENABLED=false` reverts to immediate-session-on-register and
   disables the login gate (no redeploy of old code needed) — the escape hatch if SMTP
   breaks in prod. Note the migration backfill grandfathered pre-existing password
   accounts, so only new sign-ups are gated.

4. **README** (optional/minimal): mention running a local SMTP catcher (Mailpit)
   for activation emails during dev, or that links are logged at INFO.

5. Verify dates/links/claims after editing (per documentation-management rule).

## Success Criteria

- [ ] `.env.example` has all new vars with safe defaults; no real secrets committed.
- [ ] `k8s/20-engly-be-backend.yaml` injects `MAIL_*`/`EMAIL_VERIFICATION_*` (creds via Secret).
- [ ] `docs/system-architecture.md` describes the activation flow + backfill accurately.
- [ ] `docs/deployment-guide.md` explains prod SMTP setup, sender domain, and the
      `EMAIL_VERIFICATION_ENABLED` rollback switch.
- [ ] Cross-references/dates verified.

## Risk Assessment

- **Secret leakage**: never put real SMTP creds in `.env.example` or docs — placeholders only.
- **Doc drift**: confirm the documented env-var names exactly match `application.yml`.
