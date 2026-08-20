---
phase: 4
title: "Tests"
status: completed
effort: "M"
---

# Phase 4: Tests

## Overview

Cover the new behavior on both stacks. Backend: verification service logic +
auth API contract (register no-session, login 403, verify issues session, resend
generic/cooldown). Frontend: auth.service methods, verify page states, login 403
handling, register confirmation.

## Requirements

- Real behavior, no fake shortcuts. Follow existing patterns: BE uses Testcontainers
  Postgres (`@SpringBootTest @Testcontainers @Tag("integration")`, see
  `SocialLoginServiceTest`); FE uses the existing Karma/Jasmine specs.
- Mock only the outbound email (`JavaMailSender` / `ActivationEmailSender`) — never
  send real mail in tests.

## Related Code Files

- Create: `engly-be/src/test/java/com/engly/identity/application/EmailVerificationServiceTest.java`
- Create: `engly-be/src/test/java/com/engly/identity/api/AuthActivationFlowTest.java` (or extend an existing AuthController test if present)
- Create: `engly-fe/src/app/features/auth/verify-email-page.component.spec.ts`
- Modify: `engly-fe/src/app/core/auth.service.spec.ts` (new methods)
- Modify: `engly-fe/src/app/features/auth/register-page.component.spec.ts` (confirmation, no redirect)
- Modify: `engly-fe/src/app/features/auth/login-page.component.spec.ts` (403 → resend)

## Implementation Steps

1. **`EmailVerificationServiceTest`** (integration, mock the sender bean via
   `@MockBean ActivationEmailSender` / `@MockBean JavaMailSender`):
   - `startVerification` persists a hashed token (raw not stored) and dispatches via the
     sender after commit.
   - `verify` with the raw token marks the user verified and consumes the token.
   - `verify` a second time with the same token → `InvalidVerificationTokenException`
     (single-use — asserts the atomic `consumeIfUsable` returns 0 on the 2nd call).
   - **Concurrent verify** of the same token from two threads → exactly one success, one
     `InvalidVerificationTokenException` (mirror the concurrency test style in
     `SocialLoginServiceTest.concurrentFirstLogin_*`). Proves no double-session.
   - `verify` an expired token → exception (inject a short TTL or backdate `expiresAt`).
   - `resend` unknown/already-verified email → no token created, no throw (generic).
   - `resend` within cooldown → no new token; after cooldown → new token + send.
   - Prior tokens DELETED when a new one is issued (old raw token no longer verifies).
   - **Send failure is non-fatal**: sender stubbed to throw → `startVerification`
     completes, token row still present, no exception surfaces.

1b. **`V11` backfill** (assert in an integration test or a dedicated migration test):
   an `app_user` row that existed with `password_hash NOT NULL` ends up
   `email_verified = true` after Flyway runs V11; a passwordless (SSO) row is untouched.

1c. **SSO linking after activation** (extend/mirror `SocialLoginServiceTest`): seed a
   password account, activate it (`email_verified=true`), then `resolveOrLink("google",
   sameVerifiedEmail)` → links into the SAME account (documents the accepted merge).

2. **`AuthActivationFlowTest`** (web-layer / integration; **`@MockBean
   ActivationEmailSender`** so the flow never touches real SMTP — assert it was invoked):
   - `register` → 202, body has email, response has no `Set-Cookie`.
   - `login` unverified → 403 with `code = EMAIL_NOT_VERIFIED`.
   - `login` bad password → 401 generic (unchanged).
   - `verify-email` valid token → 200 + access/refresh cookies; user now verified,
     and a subsequent `login` succeeds (200 + cookies).
   - `verify-email` bad token → 400.
   - `resend-verification` → 202 for any input.
   - **Register succeeds (202, token row created) even when the mocked sender throws** —
     proves SMTP failure is non-fatal.
   - **Kill-switch**: with `app.email-verification.enabled=false` (property override),
     `register` returns identity + `Set-Cookie` and `login` on an unverified account is NOT
     gated — proves the rollback path.

3. **FE specs**:
   - `auth.service.spec.ts`: `register` returns `{email}` and does NOT set `user`;
     `verifyEmail` sets `user`; `resendVerification` posts to the right URL.
     (`HttpTestingController` per existing style.)
   - `verify-email-page.component.spec.ts`: success path sets user + navigates;
     missing token → error; 400 → error + resend visible; **already-authenticated →
     short-circuits to success without calling verifyEmail**; **token stripped from URL
     after load**.
   - `register-page.component.spec.ts`: success shows confirmation, no `router.navigate(['/'])`.
   - `login-page.component.spec.ts`: 403 shows activation message + resend button;
     401 shows generic error.

4. **Run**:
   - BE: `cd engly-be && ./mvnw test`
   - FE: `cd engly-fe && npx ng test --watch=false --browsers=ChromeHeadless`

## Success Criteria

- [ ] All new BE tests pass under Testcontainers; existing 24 BE tests still pass.
- [ ] All new/updated FE specs pass; existing specs still pass.
- [ ] No test sends real email (sender mocked/asserted).

## Risk Assessment

- **Testcontainers needs Docker** (per README). If unavailable in the run env,
  report rather than skipping/weakening assertions.
- **Cookie assertions**: assert on `Set-Cookie` header presence/absence via
  `MockMvc`/`MvcResult`; mirror how existing auth tests inspect cookies if any exist.
