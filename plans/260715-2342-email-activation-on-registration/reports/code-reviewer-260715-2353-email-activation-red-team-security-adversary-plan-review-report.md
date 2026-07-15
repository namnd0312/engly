# Red-Team Plan Review — Email Activation on Registration (Security Adversary lens)

Reviewer role: hostile Fact Checker + Security Adversary. Every finding is backed by
codebase evidence. Plan reviewed, not code.

## Finding 1: "Social login is unaffected" is false — activation flips the exact trust anchor SSO linking depends on
- **Severity:** High
- **Location:** plan.md "Overview" ("Social login … never pass through this password flow"); phase-01 step 9 (`markEmailVerified`); phase-02 step 4 (verify → `markEmailVerified`)
- **Flaw:** The whole SSO account-linking safety model is built on the invariant *"password accounts are always `emailVerified=false`"*. This is stated verbatim in the domain model and the linking service. This plan makes password accounts become `emailVerified=true` on activation, silently changing how `SocialLoginService.resolveOrLink` treats them. The plan asserts SSO is unaffected and adds **no** analysis and **no** test for this interaction.
- **Failure scenario:** Email-recycling takeover. Attacker registers a password account for an address they currently control (e.g. `old-name@company.com`) and activates it → `email_verified=true`. The address is later reassigned to a new person who signs in with Google (`email_verified=true` from Workspace). `resolveOrLink` step 2 now finds a **verified** match and *links* the new person's Google identity into the attacker's pre-existing password account (attacker knows the password) instead of creating a separate account as it does today. Result: cross-account access. Even outside recycling, the change silently *merges* what were previously two separate accounts, an unreviewed behavior change to a security-critical path.
- **Evidence:** `engly-be/src/main/java/com/engly/identity/application/SocialLoginService.java:58-73` (`if (match.get().isEmailVerified()) { … identities.save(UserIdentity.link(...)) }`); `engly-be/src/main/java/com/engly/identity/domain/UserAccount.java:82-88` ("emailVerified is false — this is what keeps a password account from being silently linked into by a social login on the same email"); phase-01 line 80, phase-02 line 64-67; no test for this in phase-04.
- **Suggested fix:** Add an explicit design decision + integration test to the plan: after a password account is activated, assert the desired `resolveOrLink` behavior for a same-email Google login (link vs. keep separate). If merge is intended, document it and cover it; if not, gate linking on a signal stronger than `emailVerified` (e.g. distinguish password-verified from provider-verified). Correct the plan.md claim that SSO is unaffected.

## Finding 2: Resend endpoint leaks account existence via timing side-channel despite the 202
- **Severity:** Medium
- **Location:** phase-01 step 8-9 (`ActivationEmailSender.send`, `EmailVerificationService.resend`); plan.md acceptance "Resend is generic (no account enumeration)"
- **Flaw:** The plan makes `resend` return 202 for all inputs (good), but `send` is a synchronous `JavaMailSender.send(SimpleMailMessage)` call. For an existing+unverified address the request performs an SMTP round-trip; for an unknown/already-verified/in-cooldown address it returns immediately. The response-time delta is a reliable enumeration oracle, defeating the stated "no enumeration" goal.
- **Failure scenario:** Attacker scripts `resend-verification` across a candidate email list and buckets by latency: slow (~SMTP RTT) = registered-and-unverified, fast = everything else. Enumerates the unverified user base without ever reading the response body.
- **Evidence:** phase-01 lines 87-92 (synchronous send, no async/queue); phase-01 line 106 (cooldown branch returns immediately); no `@Async`/executor mentioned anywhere in the plan.
- **Suggested fix:** Send mail asynchronously (dispatch to an executor/queue and return before the SMTP call), or normalize response time. State this explicitly in phase-01.

## Finding 3: Raw activation token placed in URL query string — Referer/log/history leakage
- **Severity:** Medium
- **Location:** phase-01 step 8 (`frontendUrl + verifyPath + "?token=" + urlEncode(rawToken)`)
- **Flaw:** Refresh/access tokens are correctly httpOnly cookies, but the activation secret is put in the URL query. Query strings leak via the `Referer` header when the SPA verify page loads any third-party resource (analytics, fonts, images), via browser history, and via server/proxy access logs. TTL is 24h and single-use, but the token is valid until clicked — a window for a leaked link to be replayed.
- **Failure scenario:** Victim opens the activation link; the SPA verify page loads a third-party script/pixel; the full URL (token included) is sent as `Referer` to that third party, or captured in an intermediary proxy log, and replayed before the victim clicks — auto-login as the victim.
- **Evidence:** phase-01 lines 89-91; contrast with cookie handling in `engly-be/src/main/java/com/engly/identity/application/AuthTokenService.java:133-142` (httpOnly).
- **Suggested fix:** Consume the token immediately on page load and strip it from the URL (`history.replaceState`); set a strict `Referrer-Policy: no-referrer` on the verify route; keep TTL short. Document in phase-01/phase-03.

## Finding 4: Email bombing — cooldown is per-address only, no global/IP rate limit
- **Severity:** Medium
- **Location:** phase-01 step 9 (per-user cooldown); plan.md acceptance "rate-limited (default 60s)"
- **Flaw:** The only throttle is a 60s per-address cooldown on `resend`. There is no IP-level or global cap, and the initial `register` send has no throttle at all. An attacker can bomb any victim's inbox (1 email/60s ≈ 1440/day per address) and, fanned across many addresses, drive arbitrary outbound mail volume — reputation damage to the sending domain and inbox harassment.
- **Failure scenario:** Attacker loops `resend-verification` for `victim@gmail.com` every 60s indefinitely (unauthenticated, permitAll), flooding the victim and burning the SMTP relay's sending reputation/quota.
- **Evidence:** phase-01 line 100-106 (cooldown keyed on the user's most-recent token only); `SecurityConfig.java:120` (`/api/auth/**` permitAll); no IP/global limiter in any phase.
- **Suggested fix:** Add an IP-based and per-address daily send cap in addition to the per-user cooldown; consider a lightweight bucket/limiter on the resend + register send paths. Add to phase-01/phase-02.

## Finding 5: verify-email is CSRF-exempt and issues a session → login-CSRF vector
- **Severity:** Medium
- **Location:** phase-02 step 7 (add `/api/auth/verify-email` to `ignoringRequestMatchers`); phase-02 step 4 (verify issues session)
- **Flaw:** `verify-email` is added to the CSRF ignore-list *and* establishes an authenticated session. That combination is a login-CSRF sink: the token is attacker-suppliable (the attacker uses their **own** valid activation token), so unlike a data-mutation CSRF the missing-token argument does not apply.
- **Failure scenario:** Attacker registers their own account, gets a valid token, then serves a page that auto-POSTs `verify-email` with that token from the victim's browser. The victim is silently logged into the attacker's account; subsequent study writes, avatar uploads, and profile data the victim creates are attributed to the attacker's account (session fixation / login-CSRF).
- **Evidence:** phase-02 lines 81-85; `SecurityConfig.java:114` (existing ignore-list rationale is "no XSRF cookie yet" — valid for register/login but this endpoint also creates a session with an attacker-controlled token); `AuthTokenService.issueSession` writes cookies.
- **Suggested fix:** Document login-CSRF as an accepted risk with mitigation, or bind the verify action to a same-origin form/double-submit where feasible; at minimum add `SameSite` reasoning and note that verify should not blindly overwrite an existing authenticated session.

## Finding 6: Single-use enforcement has a TOCTOU race — token can be consumed twice
- **Severity:** Medium
- **Location:** phase-01 step 4/9 (`isUsable` then `consume`); phase-04 asserts single-use
- **Flaw:** `verify` reads the token, checks `isUsable(now)`, then sets `consumedAt` — read-check-write with no row lock. Under Postgres READ COMMITTED, two concurrent verifies of the same token both pass `isUsable` before either commits `consumedAt`, so both succeed and both mint sessions. The "invalidate prior unconsumed tokens on resend" step has the same TOCTOU with an in-flight verify.
- **Failure scenario:** A double-clicked or replayed activation link (common with email-scanner prefetch, e.g. Outlook SafeLinks fetching the URL before the user clicks) triggers two overlapping verifies; the single-use invariant the plan advertises is violated. Low blast radius (same account) but the stated guarantee is false and email-scanner prefetch can consume the token before the human ever clicks, breaking activation.
- **Evidence:** phase-01 lines 69-72, 94-106; phase-04 line 42-43 (single-use assertion); no locking/`@Modifying` conditional-update specified.
- **Suggested fix:** Consume with a conditional atomic update — `UPDATE email_verification_token SET consumed_at = now() WHERE id = ? AND consumed_at IS NULL` and require 1 affected row, or `SELECT … FOR UPDATE`. Also decide how to survive scanner prefetch (POST-confirm on the SPA rather than GET-on-load).

## Finding 7: register leaves a half-created account when SMTP send fails
- **Severity:** Medium
- **Location:** phase-02 step 3 (register: `accountService.register` then `startVerification`, return 202)
- **Flaw:** `register` commits the account, then calls `startVerification`, which synchronously sends mail. If the SMTP send throws (relay down/misconfigured), the exception propagates → `GlobalExceptionHandler` returns **500**, but the account already exists and is unverified. The user never gets a 202, never gets an email, and cannot re-register (`EmailAlreadyExistsException` → 409). Recovery requires knowing to call `resend`, which the failed UX never surfaces.
- **Failure scenario:** SMTP outage during a signup wave: users see a 500, their accounts silently exist unverified, retrying registration returns 409 "email already exists" — a confusing dead-end that looks like account theft to the user.
- **Evidence:** phase-02 lines 58-62; `UserAccountService.register` is `@Transactional` and commits before the controller calls the sender (`UserAccountService.java:29-38`); `GlobalExceptionHandler` generic handler → 500 (`GlobalExceptionHandler.java:117-122`); `EmailAlreadyExistsException` → 409 (`:67-70`).
- **Suggested fix:** Decouple send from the request path (enqueue async so send failure doesn't fail registration) and always return 202; or catch send failures in the controller and still return 202, relying on resend. Specify the transaction/ error boundary in phase-02.

---

## Verified-correct (not findings, checked because the lens demanded it)
- Login enumeration via 403 is **adequately mitigated**: phase-02 step 2 throws `EmailNotVerifiedException` only *after* a successful password match, and `UserAccountService.login` already returns generic 401 for unknown-user/bad-password (`UserAccountService.java:41-48`). A 403 requires valid credentials, so it is not a bare enumeration oracle. Keep this ordering.
- DDL idiom claim is accurate: `gen_random_uuid()` + `timestamptz` + `UNIQUE(token_hash)` match V6/V10 exactly (`V6__create_users_and_auth.sql:14,32-39`, `V10:16`), so `ddl-auto: validate` risk is low.
- `buildResponse` is an immutable 4-key `Map.of` (`GlobalExceptionHandler.java:124-130`); phase-02's note to add `code` via an overload rather than mutating the shape is correct.
- Token hashing reuse is sound: `AuthTokenService.sha256` is `private static` standard-Base64 SHA-256 (`AuthTokenService.java:167-174`); extracting to `TokenHashing` preserves refresh-token compatibility as the plan claims.

## Unresolved questions
1. Is silent merge of an activated password account with a same-email Google login the intended product behavior (Finding 1)? This must be answered before implementation, not during.
2. Is mail dispatch synchronous or async? Findings 2, 4, and 7 all hinge on this and the plan never states it.
3. Does `verify-email` overwrite an already-authenticated session, or reject when one exists (Finding 5)?
