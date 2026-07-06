# Phase 02 — Backend foundation (SDK, migration, S3 config)

**Priority:** P0 · **Status:** ✅ DONE · **Depends on:** Phase 1

## Overview

Add the AWS SDK, the `avatar_key` column, S3 config beans, and the single S3-touching adapter
(`S3AvatarStorage`) that everything above it mocks in tests.

## Files created / modified

- **`engly-be/pom.xml`** — `software.amazon.awssdk:bom:2.25.70` (dependencyManagement, import) +
  `software.amazon.awssdk:s3`. Added `**/S3ClientConfig*` + `**/AwsProperties*` to both JaCoCo exclude lists.
- **`engly-be/src/main/resources/db/migration/V7__add_app_user_avatar_key.sql`** —
  `ALTER TABLE app_user ADD COLUMN avatar_key varchar(512) NULL;` (+ comment on key semantics).
- **`application.yml`** — new `aws:` block: `region`, `access-key`/`secret-key` (`${AWS_ACCESS_KEY_ID:test}`),
  `s3.bucket`, `s3.endpoint` (server-side), `s3.public-endpoint` (browser/presigner), `upload-url-ttl 15m`,
  `download-url-ttl 1h`. Blank endpoints = real AWS defaults.
- **Create `identity/config/AwsProperties.java`** — `@ConfigurationProperties(prefix="aws")` record
  (nested `S3` record with compact-constructor defaults), style of `AuthProperties`.
- **Create `identity/config/S3ClientConfig.java`** — `@Configuration @EnableConfigurationProperties(AwsProperties.class)`.
  Beans: `S3Client` (endpointOverride = `endpoint`), `S3Presigner` (endpointOverride = `publicEndpoint`).
  Both `pathStyleAccessEnabled(true)`; static creds when keys set, else `DefaultCredentialsProvider` (real-AWS escape hatch).
- **Create `identity/infrastructure/S3AvatarStorage.java`** — the ONLY SDK-touching class (mock seam):
  `presignUpload(key, contentType)`, `presignDownload(key)`, `exists(key)` (HeadObject → NoSuchKey=false),
  `contentTypeOf(key)`. Inner `PresignedUpload(url, objectKey, expiresAt)` record.

## Key decisions

- **Endpoint split** is safe because the presigner makes no network calls → its `endpointOverride`
  can differ from `S3Client`'s. Path-style is mandatory (virtual-host would produce
  `engly-avatars.localhost` and break presign host stability).
- **`S3AvatarStorage` seam** = the single mock point → integration tests never need Floci/Docker-in-Docker.

## Validation (done)

- `./mvnw compile` clean.
- `./mvnw test` — Testcontainers exercises Flyway V7 + `ddl-auto: validate` (schema matches entity).
