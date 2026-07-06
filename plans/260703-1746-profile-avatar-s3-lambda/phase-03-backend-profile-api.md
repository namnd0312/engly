# Phase 03 — Backend profile API

**Priority:** P0 · **Status:** ✅ DONE · **Depends on:** Phase 2

## Overview

Domain method + service + controller + DTOs + security for display-name edit and the avatar
upload/confirm lifecycle. `/api/me` switches to a DB read so it can serve avatar URLs.

## Files created / modified

- **`identity/domain/UserAccount.java`** — field `avatarKey` (`@Column length=512`) + getter; intent
  methods `updateDisplayName(String)`, `changeAvatar(String)` (mirror existing `applySsoProfile`).
- **Create `identity/domain/InvalidAvatarException.java`** → mapped to 400 in `GlobalExceptionHandler`.
- **Create `identity/application/ProfileService.java`** (`@Service @Transactional`):
  - `updateDisplayName(sub, name)`
  - `createAvatarUploadUrl(sub, contentType)` — allowlist `image/jpeg|png|webp|gif`; key
    `avatars/{user.id}/{UUID}/original` (backend-generated, client never chooses).
  - `confirmAvatar(sub, objectKey)` — ownership prefix + shape regex
    (`^avatars/[0-9a-f-]{36}/[0-9a-f-]{36}/original$`) + S3 existence + stored content-type still image/*.
  - `toUserInfo(user, isAdmin)` — HeadObject `256.webp` → presign 256/64, else presign original for both.
- **`identity/api/dto/UserInfoResponse.java`** — add `avatarUrl`, `avatarThumbUrl`; keep `from(UserAccount)` (null URLs) so AuthController compiles unchanged.
- **`identity/api/SessionController.java`** — `/api/me` DB read (`findBySub` → `toUserInfo`), fallback to claims-built response if no row; isAdmin from authorities.
- **Create `identity/api/ProfileController.java`** `@RequestMapping("/api/profile")`: `PATCH /`,
  `POST /avatar/upload-url`, `POST /avatar/confirm`. DTO records: `UpdateProfileRequest`,
  `AvatarUploadUrlRequest`, `AvatarUploadUrlResponse`, `AvatarConfirmRequest` (Jakarta validation).
- **`common/SecurityConfig.java`** — add `.requestMatchers("/api/profile/**").authenticated()`.

## Security checklist

- Ownership: backend generates key at presign; confirm re-validates prefix + shape + existence.
- Content-type: allowlist at presign (signed into PUT → S3 rejects mismatch), re-checked from S3 metadata at confirm.
- TTLs: PUT 15m, GET 1h.
- Accepted residual: non-image bytes with image/* header → Pillow fails, original served with image/* type → broken img, no XSS.

## Tests (done)

- **`ProfileServiceTest`** (unit, mocked `S3AvatarStorage`): allowlist, key shape, ownership rejection
  (foreign userId + path traversal), missing-object, non-image, `toUserInfo` resized-vs-fallback branching.
- **`ProfileFlowIntegrationTest`** `@Tag("integration")` Testcontainers + `@MockitoBean S3AvatarStorage`:
  register → rename → upload-url → confirm → `/api/me` serves webp; foreign key → 400; pdf → 400.
- Extended `SecurityRulesTest`: anonymous `/api/profile` → 401.
- Also added the new `ProfileService`/`UserAccountRepository` mocks to the 4 `@WebMvcTest` slices that
  component-scan all controllers.
- **Result: 269/269 pass**, `./mvnw verify` JaCoCo gate green.

## Note (CSRF)

"Authenticated but no X-XSRF-TOKEN → 403" is NOT asserted: the resource-server configurer auto-ignores
CSRF for bearer requests and this app resolves the bearer JWT from the ACCESS_TOKEN cookie → cookie-auth'd
mutations skip CSRF app-wide (all `/api/**`). Mitigated by `SameSite=Lax`. Documented via test + comment.
