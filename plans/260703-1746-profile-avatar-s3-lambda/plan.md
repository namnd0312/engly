# Plan: User Profile Update + Avatar Upload (S3 presigned PUT + Python Lambda resize, Floci)

**Status:** ✅ DONE (all phases implemented + verified — BE 269/269 tests, FE 146/146, E2E on k8s :30090)
**Created:** 2026-07-03

## Context

Engly (Spring Boot 3.4.5 + Angular 19, OrbStack k8s) needs a profile feature: authenticated users
edit **displayName** and upload/replace an **avatar**.

- Avatar → **S3**, **any file size** → presigned PUT URL, browser uploads directly to S3 (never through Spring/nginx).
- **Python AWS Lambda** (Pillow) resizes on the S3 upload event → 256px + 64px **WebP** (square cover-crop); original preserved.
- AWS = **Floci** (`floci.io/aws`, local emulator, LocalStack replacement; image `floci/floci:latest`, port 4566, creds `test/test`, Lambda runs in real Docker containers). Code uses AWS SDK v2 + endpoint override → portable to real AWS.
- Store S3 object **key** on `app_user`; `/api/me` returns short-lived presigned GET URLs.

**Key architecture:**
- Deterministic keys `avatars/{userId}/{uploadId}/original` → Lambda writes siblings `256.webp`, `64.webp` (suffix filter `original` prevents recursion).
- `/api/me` does one `HeadObject` on `256.webp`: present → presign 256/64, absent → presign original for both (graceful pre-Lambda window).
- Endpoint split (mirrors OpenGate OIDC precedent): `S3Client` (server HeadObject) uses in-cluster-reachable endpoint; `S3Presigner` (browser URLs) uses browser-reachable endpoint. Both path-style.
- Floci via docker-compose (needs docker.sock); pods reach it via `host.orb.internal:4566`, browser via `localhost:4566`.

## Phases

| # | Phase | Status | File |
|---|-------|--------|------|
| 1 | Floci infra + Lambda resize | ✅ DONE | [phase-01-floci-infra-lambda.md](phase-01-floci-infra-lambda.md) |
| 2 | Backend foundation (SDK, V7, S3 config) | ✅ DONE | [phase-02-backend-foundation.md](phase-02-backend-foundation.md) |
| 3 | Backend profile API | ✅ DONE | [phase-03-backend-profile-api.md](phase-03-backend-profile-api.md) |
| 4 | Frontend profile page + avatar upload | ✅ DONE | [phase-04-frontend-profile-avatar.md](phase-04-frontend-profile-avatar.md) |
| 5 | k8s wiring + E2E verify | ✅ DONE | [phase-05-k8s-wiring-e2e.md](phase-05-k8s-wiring-e2e.md) |

**Luồng chi tiết:** [sequence-diagram.md](sequence-diagram.md) — Mermaid sequence cho cả PATCH name + avatar upload/resize/serve.

## Dependencies

Phase 1 → 2 → 3 → 4 → 5 (sequential). Phase 3 depends on the S3 config + storage adapter from Phase 2;
Phase 4 depends on the API contract from Phase 3; Phase 5 wires k8s env + full E2E.

## Acceptance criteria (all met)

- PATCH display name persists; `/api/me` reflects it without re-login.
- Presigned PUT direct-to-S3 (any size); content-type allowlist enforced at presign + confirm.
- Lambda produces 256/64 WebP; `/api/me` upgrades from original → resized automatically.
- Ownership: confirm re-validates key prefix = caller userId + shape regex + S3 existence.
- Security: `/api/profile/**` authenticated; anonymous → 401, foreign key → 400, non-image → 400.
- Backend tests mock at `S3AvatarStorage` seam → CI never needs Floci.

## Post-implementation notes

1. **CSRF (pre-existing, app-wide):** resource-server auto-ignores CSRF for bearer requests, and the app
   resolves the JWT from the ACCESS_TOKEN cookie → cookie-auth'd mutations skip the CSRF check
   (all `/api/**`, not just profile). Mitigated by `SameSite=Lax`. Documented via test + comment, not changed.
2. **Lambda endpoint:** inside a Lambda container `localhost:4566` is itself — must use
   `AWS_ENDPOINT_URL=http://floci:4566` (fixed in `setup-floci.sh`).
3. Floci may not persist across restarts — re-run `./infra/floci/setup-floci.sh` (idempotent).
