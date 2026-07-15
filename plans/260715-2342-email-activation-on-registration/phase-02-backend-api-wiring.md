---
phase: 2
title: "Backend API wiring"
status: pending
effort: "M"
---

# Phase 2: Backend API wiring

## Overview

Wire the Phase 1 service into the auth API: registration sends the email and
issues no session; login rejects unverified accounts; add verify + resend
endpoints; map new exceptions; open the CSRF ignore-list for the two new
cookie-setting endpoints.

## Requirements

- Functional: `register` → account + email, no cookies; `login` on unverified →
  403; `verify-email` → activate + session; `resend-verification` → generic 202.
- Non-functional: preserve existing 401 generic message for bad credentials;
  keep unverified-vs-bad-credential responses distinct (403 vs 401).

## Architecture

- `AuthController` gains two endpoints and changes the register/login return
  behavior. `UserAccountService.register` returns the created user without a
  session; `AuthController.register` calls `emailVerificationService.startVerification`.
- Login gate lives in `UserAccountService.login` (or the controller) throwing
  `EmailNotVerifiedException` after a successful password match.

### SSO linking interaction (intended behavior — was falsely claimed "unaffected")

Activating a password account flips `email_verified` false→true. `SocialLoginService`
links a social identity into an existing account only when that account is **verified**
(`SocialLoginService.java:58-73`), and `UserAccount` documents that password accounts
stay unverified precisely to block that link. **Decision (user, 2026-07-16): allow the
link** — a user who registered with email+password, activated, then signs in with the
same verified Google email lands in **one merged account** (the intended "verified-both-
sides" UX). This must be an explicit, tested behavior, not an accident:
- Update the `UserAccount` / `SocialLoginService` doc comments to state that an
  **activated** password account is a valid link target (remove the stale "always
  unverified" absolute).
- Add the covering integration test in Phase 4.
- Residual risk (documented, accepted): email-recycling takeover of a reassigned address
  — niche for this app; not mitigated now.

## Related Code Files

- Create: `engly-be/src/main/java/com/engly/identity/domain/EmailNotVerifiedException.java`
- Create: `engly-be/src/main/java/com/engly/identity/domain/InvalidVerificationTokenException.java`
- Create: `engly-be/src/main/java/com/engly/identity/api/dto/VerifyEmailRequest.java` (`token`)
- Create: `engly-be/src/main/java/com/engly/identity/api/dto/ResendVerificationRequest.java` (`email`)
- Create: `engly-be/src/main/java/com/engly/identity/api/dto/RegisterResponse.java` (`email`)
- Modify: `engly-be/src/main/java/com/engly/identity/api/AuthController.java`
- Modify: `engly-be/src/main/java/com/engly/identity/application/UserAccountService.java`
- Modify: `engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java`
- Modify: `engly-be/src/main/java/com/engly/common/SecurityConfig.java`
- Modify: `engly-be/src/main/java/com/engly/identity/domain/UserAccount.java` (doc comment: an activated password account IS a valid SSO link target — remove the "always unverified" absolute)
- Modify: `engly-be/src/main/java/com/engly/identity/application/SocialLoginService.java` (doc comment only — behavior already correct; note password accounts can now be verified)

## Implementation Steps

1. **Exceptions**:
   - `EmailNotVerifiedException extends RuntimeException` — thrown after a valid
     password match on an unverified account.
   - `InvalidVerificationTokenException extends RuntimeException` — invalid/expired/
     used token.

2. **`UserAccountService.login`**: after the password check succeeds, if
   `!user.isEmailVerified()` throw `EmailNotVerifiedException`. Keep the generic
   `InvalidLoginException` for wrong email/password (do not reveal verification
   state for non-existent/bad-password accounts — only accounts that pass the
   password check learn they are unverified).

3. **`AuthController.register`**: keep `accountService.register(...)`; **remove** the
   unconditional `tokenService.issueSession(...)`. Then branch on the kill-switch:
   - **gate enabled (default)**: `emailVerificationService.startVerification(user)`;
     return `ResponseEntity.status(202).body(new RegisterResponse(user.getEmail()))`
     (no cookies).
   - **gate disabled** (`app.email-verification.enabled=false`, rollback path): mark the
     user verified + `tokenService.issueSession(user, response)` and return the identity
     — i.e. today's behavior, so disabling the flag fully reverts the flow.
   (Inject `EmailVerificationService`. The account is committed before the email is
   dispatched post-commit — a failed send never fails this request; see Phase 1 step 9.)

4. **`AuthController.verifyEmail`** — `POST /api/auth/verify-email`:
   `UserAccount user = emailVerificationService.verify(req.token());`
   `tokenService.issueSession(user, response);`
   `return ResponseEntity.ok(UserInfoResponse.from(user));`
   **Login-CSRF guard**: this endpoint is CSRF-exempt and sets a session from a
   caller-supplied token, so it must not silently replace an *already-authenticated*
   user's session (a forged verify would log the victim into the attacker's account).
   If the request already carries a valid access cookie for a different subject, do not
   overwrite it — reject (409/no-op). Cheapest form: the SPA only calls verify when
   anonymous (Phase 3 short-circuits if `isAuthenticated()`), and the endpoint checks the
   incoming principal before issuing.

5. **`AuthController.resendVerification`** — `POST /api/auth/resend-verification`:
   `emailVerificationService.resend(req.email());`
   `return ResponseEntity.accepted().build();` (always 202, no body).

6. **`GlobalExceptionHandler`**:
   - `EmailNotVerifiedException` → **403** with a stable machine-readable field so
     the SPA can branch, e.g. add `"code": "EMAIL_NOT_VERIFIED"` to the body (extend
     `buildResponse` or add a small overload). Message: user-facing Vietnamese-safe
     generic ("Email chưa được kích hoạt").
   - `InvalidVerificationTokenException` → **400** ("Liên kết kích hoạt không hợp lệ
     hoặc đã hết hạn").

7. **`SecurityConfig`**: add `/api/auth/verify-email` and
   `/api/auth/resend-verification` to the CSRF `ignoringRequestMatchers(...)` list
   (they set/clear session cookies and run before an XSRF cookie exists — same
   rationale as `/api/auth/register`). `/api/auth/**` is already `permitAll`, so no
   authorization change needed.

## Success Criteria

- [ ] `POST /api/auth/register` returns 202 with `{email}` and **no `Set-Cookie`**.
- [ ] `POST /api/auth/login` for an unverified account returns 403 with
      `code = EMAIL_NOT_VERIFIED`; bad credentials still return generic 401.
- [ ] `POST /api/auth/verify-email` with a valid token returns 200 + `Set-Cookie`
      (access + refresh) and flips `email_verified` to true.
- [ ] `POST /api/auth/verify-email` with a bad/expired token returns 400.
- [ ] `POST /api/auth/resend-verification` always returns 202 regardless of input.
- [ ] With `app.email-verification.enabled=false`, register returns identity + cookies
      (today's behavior) and login is not gated — verifies the rollback switch.
- [ ] `verify-email` while already authenticated does not silently overwrite the session.
- [ ] `./mvnw compile` passes.

## Risk Assessment

- **Adding `code` to error body**: `buildResponse` currently returns a fixed 4-key
  `Map.of`. Use a mutable map or an overload for the one case that needs `code`;
  don't change the shape for existing handlers (FE parses `message`).
- **Login response-status contract change**: FE login must now handle 403. Covered
  in Phase 3; note the coupling.
