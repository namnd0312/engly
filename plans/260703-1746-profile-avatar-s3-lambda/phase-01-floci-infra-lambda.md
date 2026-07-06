# Phase 01 — Floci infra + Lambda resize

**Priority:** P0 (foundation) · **Status:** ✅ DONE

## Overview

Stand up the local AWS emulator (Floci) with an S3 bucket and a Python resize Lambda triggered by
S3 upload events. Floci runs via docker-compose (Lambda needs the Docker socket).

## Files created / modified

- **Modify `docker-compose.yml`** — add `floci` service (opt-in profile `["floci"]` so it won't
  clash with a standalone Floci already on :4566), image `floci/floci:latest`, port 4566,
  `/var/run/docker.sock` mount, `engly-floci-data` volume.
- **Create `infra/lambda/avatar-resize/handler.py`** — Pillow handler: for each S3 record with key
  ending `/original`: GetObject → `ImageOps.exif_transpose` → `ImageOps.fit((size,size))` for 256/64
  → save WEBP q85 → PutObject `{prefix}/{size}.webp` (immutable cache-control). URL-decodes event keys.
  `boto3.client("s3", endpoint_url=os.environ.get("AWS_ENDPOINT_URL"))`.
- **Create `infra/lambda/avatar-resize/requirements.txt`** — `Pillow==10.4.0`.
- **Create `infra/lambda/avatar-resize/build.sh`** — builds zip inside `public.ecr.aws/sam/build-python3.12`
  for `LAMBDA_ARCH` (default arm64) + import smoke test (Pillow is a C extension → host wheels won't run).
- **Create `infra/floci/setup-floci.sh`** — idempotent: create bucket, CORS (`:30090`, `:4200`),
  build+deploy Lambda, `add-permission` for s3, `put-bucket-notification-configuration`
  (`s3:ObjectCreated:*`, prefix `avatars/`, suffix `original`).

## Key decisions

- **WebP** single output format (universal support, ~30% smaller, Pillow native, no source-format branching).
- **Square cover-crop** (`ImageOps.fit`) — avatars are always square.
- **docker-compose over k8s** for Floci — docker.sock mount is guaranteed; k8s hostPath variant is
  a documented fallback only.

## Implementation notes / fixes

- aws CLI v2.0.30 lacks `--architectures` on `create-function` → removed the flag (emulator runs
  containers on host-native arch).
- **Lambda endpoint bug:** inside the Lambda container `localhost:4566` = itself → set
  `AWS_ENDPOINT_URL=http://floci:4566` (compose service DNS on `engly_default` network). Fixed in setup script.

## Validation (done)

```bash
docker compose --profile floci up -d floci
./infra/floci/setup-floci.sh
aws --endpoint-url http://localhost:4566 s3 cp test.png s3://engly-avatars/avatars/u1/x1/original --content-type image/png
aws --endpoint-url http://localhost:4566 s3 ls s3://engly-avatars/avatars/u1/x1/   # → 256.webp + 64.webp
```
Verified: `256.webp` downloaded = `RIFF … Web/P … 256x256`. ✅

## Risks

- Floci S3→Lambda trigger fidelity (docs confirm SQS/Kinesis/DynamoDB ESM, not S3 explicitly) —
  **verified working**; design also degrades gracefully (Phase 3 serves original as fallback).
- Floci may not persist resources across restarts → re-run `setup-floci.sh` (idempotent).
