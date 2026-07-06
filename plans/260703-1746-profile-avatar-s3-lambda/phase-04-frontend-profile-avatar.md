# Phase 04 — Frontend profile page + avatar upload

**Priority:** P1 · **Status:** ✅ DONE · **Depends on:** Phase 3

## Overview

Extend the existing read-only `/profile` page into an editable page: display-name reactive form +
direct-to-S3 avatar upload with progress. Add avatar fields to the identity model and header.

## Files created / modified

- **`core/auth.service.ts`** — `UserInfo` += `avatarUrl`, `avatarThumbUrl`; add `setUser(u)`
  (replace signal after edits) + `refreshMe()` (force fresh `/api/me`).
- **Create `features/profile/data/profile.model.ts`** — `AvatarUploadUrl { uploadUrl, objectKey, expiresAt }`.
- **Create `features/profile/data/profile-api.service.ts`** — `updateProfile`, `requestAvatarUploadUrl`,
  `confirmAvatar` (relative `/api/profile/**`, `withCredentials`), and `uploadToS3(url, file)` =
  `http.put(url, file, {headers:{'Content-Type':file.type}, reportProgress:true, observe:'events'})`.
  Absolute cross-origin URL → HttpClient sends no cookies and Angular's XSRF interceptor auto-skips (both required).
- **`features/profile/profile-page.component.ts` + `.html`** — reactive form for displayName (pattern:
  `vocab-word-admin-form-page`); avatar block: preview (avatarUrl or initial placeholder),
  `<input type=file accept="image/*">`, flow upload-url → PUT with progress signal → confirm →
  `auth.setUser(resp)`. Client-side reject non-image early. Copy notes async thumbnail.
- **`features/profile/profile.routes.ts`** — add `canActivate: [authGuard]` (route now mutates data).
- **`app.component.html`** — show 24px round `avatarThumbUrl` img next to user name + `.app-avatar` CSS.

## Tests (done)

- **`profile-api.service.spec.ts`** (`HttpTestingController`): PATCH/POST bodies; S3 PUT to absolute URL
  carries file Content-Type, `withCredentials=false`, no `X-XSRF-TOKEN`.
- **`profile-page.component.spec.ts`** (mocked api): prefill, save happy/blank/error, non-image reject,
  full upload→confirm happy path, S3-failure surface.
- Updated `auth.service.spec.ts` for extended `UserInfo` + `setUser`/`refreshMe`.
- **Result: 146/146 pass**, `ng build` OK (275 kB initial).

## Implementation note

RxJS 7 multi-arg `of(...)` needs a tuple generic — used a typed `HttpEvent<unknown>[]` array +
`of(...events)` in the component spec to emit progress + response events.
