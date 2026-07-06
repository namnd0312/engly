# Phase 05 — k8s wiring + E2E verify

**Priority:** P1 · **Status:** ✅ DONE · **Depends on:** Phase 4

## Overview

Wire the S3 env into the backend Deployment (browser-vs-server endpoint split), update docs, redeploy,
and verify the full flow end-to-end on the k8s NodePort.

## Files modified

- **`k8s/20-engly-be-backend.yaml`** — add env (comment mirrors the OIDC split rationale):
  - `S3_ENDPOINT=http://host.orb.internal:4566` (pod → host-published Floci, for HeadObject)
  - `S3_PUBLIC_ENDPOINT=http://localhost:4566` (browser-facing, baked into presigned URLs)
  - `AVATAR_BUCKET=engly-avatars`, `AWS_REGION=us-east-1`, `AWS_ACCESS_KEY_ID/SECRET=test` (emulator dummy creds).
- **`docs/deployment-guide.md`** — added avatar env-var rows + a "Start Floci + avatar resources"
  section (`docker compose --profile floci up -d` + `./infra/floci/setup-floci.sh`, smoke test, persistence note).

## E2E verification (done, on :30090)

1. register → `/api/me` (nulls) → PATCH displayName → `/api/me` reflects "E2E Renamed". ✅
2. `POST /avatar/upload-url` → **S3 PUT 200** (direct to `localhost:4566`, presigned) → `POST /avatar/confirm`
   returns avatar URLs (original). ✅
3. Poll `/api/me` → auto-upgrades to `256.webp`/`64.webp` within seconds. ✅
4. Presigned GET download = `RIFF … Web/P … 256x256`. ✅
5. Negatives: `application/pdf` → **400**, foreign key → **400**, anonymous PATCH → **403/401**. ✅

Health after full rebuild + `rollout restart` (both images `:local`, forced pull):
SPA `/` 200, `/api/verbs?q=go` 200, `/api/me` anon 401, SSO authorize 302. ✅

## Deploy commands

```bash
docker compose --profile floci up -d floci && ./infra/floci/setup-floci.sh   # once (idempotent)
sh k8s/deploy-to-orbstack.sh                                                  # build + apply
kubectl -n engly rollout restart deploy/engly-be deploy/engly-fe             # force pull :local
```

## Risk / rollback

- `:local` tag unchanged → `kubectl apply` reports "unchanged" → must `rollout restart` to pick up rebuilt images.
- `host.orb.internal` unresolvable on some OrbStack versions → fallback host LAN IP or k8s Floci deployment (documented only).
