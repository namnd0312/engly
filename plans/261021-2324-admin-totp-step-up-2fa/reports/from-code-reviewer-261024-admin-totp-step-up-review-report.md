# Code Review: admin TOTP step-up 2FA (uncommitted working tree)

## Scope
- BE: MfaStepUpFilter, MfaService, TotpCodec, MfaSecretCipher, MfaClaims, MfaController, AuthTokenService, SecurityConfig, Session/ProfileController, GlobalExceptionHandler, UserMfaTotp, RefreshToken(+repo), V12 migration, k8s/.env/docs
- FE: auth-refresh.interceptor, admin.guard, auth.service, mfa-api.service, mfa-verify-page, mfa-settings, profile page, routes
- Verification: two throwaway probe tests (copied MfaStepUpFlowIntegrationTest setup, Testcontainers). Both were deleted after the run, and no repo code was changed.

## Overall
The crypto/codec, claim plumbing, rotation copy and FE routing are mostly sound. One **critical gate bypass** was confirmed by running it. The lockout loses updates under concurrency (also confirmed by a probe). There are a few medium correctness and ops issues.

## Critical

### C1. Admin step-up bypass via percent-encoded path. CONFIRMED
`MfaStepUpFilter.java:31,47`: `shouldNotFilter` returns `!request.getRequestURI().startsWith("/api/admin/")`. `getRequestURI()` is raw and undecoded. Spring MVC handler mapping and the `requestMatchers("/api/admin/**").hasRole("ADMIN")` rule both match against the *decoded* path. StrictHttpFirewall lets `%61` through (it only blocks encoded `/`, `.`, `%`, `\`, `;`).

Probe result, admin with a password-only session (mfa=false, not enrolled):
- `GET /api/admin/decks` gave 403 `MFA_ENROLLMENT_REQUIRED`
- `GET /api/%61dmin/decks` gave **200 with the deck list**
- `/api/admin%2Fdecks` gave 400 (firewall). `/api/admin/decks/` gave 403 (gated).

Scenario: an attacker with a stolen admin password (exactly the 2FA threat model) logs in and calls `/api/%61dmin/...` (or `/api/a%64min/...`). Every admin read and write works without TOTP, because CSRF/XSRF can be obtained from their own session. The second factor is fully defeated.

Fix (preferred): enforce mfa inside authorization so path matching is identical to the role rule:
```java
.requestMatchers("/api/admin/**").access(AuthorizationManagers.allOf(
        AuthorityAuthorizationManager.hasRole("ADMIN"), mfaVerifiedAuthorizationManager))
```
Put the coded 403 body in an `AccessDeniedHandler` that branches on enrolled / not enrolled. Minimal alternative: in the filter, match with the same `PathPatternRequestMatcher`/`MvcRequestMatcher` instance (or at least `request.getServletPath()`, which the container decodes and normalizes) instead of `getRequestURI()`. Add regression tests for `/api/%61dmin/decks` and `/api/admin%2f...`.

## High

### H1. Failure counter has lost updates. Lockout is amplified under parallel requests. CONFIRMED
`MfaService.java:108-121`, `UserMfaTotp.java:76-82`: read-modify-write of `failed_attempts` with no `@Version`, no `PESSIMISTIC_WRITE`, and no atomic SQL increment. Under Postgres READ COMMITTED, parallel transactions all read the same count.

Probe: 40 parallel wrong codes on one session returned **29 x 400 (evaluated) and 11 x 429**. Serialized, it would be 5 x 400 and 35 x 429. So about 6x more guesses per 15-minute window, bounded only by the DB pool and thread count (Hikari default 10). This is the sole brute-force control (10^6 space, 3 valid codes per window).

The same race also makes replay possible: two concurrent submissions of one valid code both pass `isReplay` (`MfaService.java:115`) and both succeed. Low impact on its own.

Fix: add `@Lock(PESSIMISTIC_WRITE)` `findForUpdate(userId)` on `UserMfaTotpRepository` and use it in `confirmEnrollment`, `verify` and `disable`. Alternatively use `@Version` with a retry. Optionally use an atomic `update ... set failed_attempts = failed_attempts + 1 ... returning`.

## Medium

### M1. `upgradeToMfa` can un-revoke a refresh token. It races with rotate/logout.
`AuthTokenService.java:73-85` vs `:94-105`, `clearSession`. `RefreshToken` has no `@Version`, and Hibernate writes all columns by default.

Scenario: tab A posts `/api/auth/mfa/verify` while tab B rotates or logs out the same refresh cookie. Both load the row with `revoked=false`. B commits `revoked=true`, then A flushes `revoked=false, mfa_verified=true`. The revoked token is **resurrected with mfa=true**, so it can be rotated again into a second mfa chain, or the logout is undone. Reverse order: rotation copies mfa=false into the new token, the old token gets the flag, and the step-up is lost after 15 minutes. That second outcome is only a UX annoyance.

Fix: either a targeted `@Modifying update RefreshToken set mfaVerified=true where tokenHash=:h and revoked=false and expiresAt>:now` (check rowcount = 1, else 401), or `@Version` on `RefreshToken`.

### M2. With the ephemeral key, a restart hard-locks enrolled admins. Docs promise "re-enroll".
`MfaSecretCipher.java:57-68,75-80`; docs/deployment-guide.md:35; k8s/20-engly-be-backend.yaml (`optional: true`); .env.example. After a restart with a new ephemeral key, rows stay `confirmed_at NOT NULL`:
- the filter says `MFA_REQUIRED`
- `verify` and `disable` throw `IllegalStateException` (decrypt), which becomes a **500**
- `enroll` returns 409 `MFA_ALREADY_ENROLLED`

The admin cannot re-enroll. Recovery is only the SQL reset, not "admins must re-enroll" as the docs/k8s comment state. The ephemeral fallback is an accepted decision, but the documented consequence is wrong, and `optional: true` means prod can silently run this way. Fix: correct the docs and comments. Optionally map the decrypt failure to a coded error, or fail startup when rows exist and no key is set.

### M3. Backend lets non-admins enroll and step up.
`MfaController.java:43-76`, `MfaService.beginEnrollment:51`; the profile template renders `<app-mfa-settings />` for every user. The scope decision is admin-only. Today regular users can create enrollments, which then gate their own `disable` and lockout. This is harmless for security, but it goes beyond the accepted scope, and those users can only recover via the SQL reset. Fix: restrict the enroll/confirm endpoints to admins (`hasRole("ADMIN")` on `/api/profile/mfa/**` or a service check) and render the settings only when `isAdmin()`. Alternatively, confirm with the user that this is intended.

## Low
- **L1** `SecurityConfig.java:73-78`: `MfaStepUpFilter` is a `@Bean` Filter, so Spring Boot also auto-registers it as a servlet filter on `/*`. It is a no-op today: the OncePerRequestFilter already-filtered attribute shares a name, and security runs at order -100. It is still fragile. Add a `FilterRegistrationBean<MfaStepUpFilter>` with `setEnabled(false)` (standard pattern), or drop the bean and `new` it in the chain.
- **L2** `MfaController.java:74-75` + `mfa-verify-page.component.ts:115`: the verify response has `avatarUrl/avatarThumbUrl = null` and `setUser(user)` overwrites the hydrated identity, so the header avatar disappears until reload. Merge into the existing user, or call `refreshMe()`.
- **L3** `MfaController.java:72-73`: `verify` (consumes the step) and `upgradeToMfa` are separate transactions. If the refresh cookie is missing or revoked, the code is burned and the user gets a 401, so they must wait for the next step. Consider checking the refresh token before consuming the code.
- **L4** `mfa-settings.component.ts:124`: `state` is read once from `auth.user()`. When the interceptor sends to `/profile?mfa=required` on `MFA_ENROLLMENT_REQUIRED` while the cached identity says enrolled (e.g. after the SQL reset), the UI shows "on" and disable then fails `MFA_NOT_ENROLLED`. The user is stuck until reload. Use a `computed`/effect, or `refreshMe()` on that redirect.
- **L5** `MfaSecretCipher.encrypt`: no AAD binds the ciphertext to `user_id`, so someone with DB write access can copy a known secret_enc onto the admin row. That attacker could already grant roles, so this is informational.
- **L6** Pre-existing, not new: `/api/ADMIN/decks` returns a 500 (unhandled no-handler exception) instead of 404.

## Verified OK (risk calibration)
- Login, register and SSO all go through `issueSession`, which hardcodes mfa=false. Rotation copies `token.isMfaVerified()`. `disable` clears the flags via a bulk update.
- `/api/auth/mfa/**` is placed before the `/api/auth/**` permitAll, so verify needs auth (401 test). It is not in the CSRF ignore list.
- Filter ordering: after BearerTokenAuthenticationFilter and before AuthorizationFilter. Non-admins pass through to the `hasRole` 403. Anonymous users pass through to the 401 entry point. The oauth2 chain does not cover `/api/**`.
- `noRollbackFor`: failures are persisted and committed. The locked path writes nothing. Exceptions map to the documented codes.
- TotpCodec: RFC 6238 truncation is correct, comparison is constant-time per candidate, and the format precheck covers public data only.
- FE: no redirect loop. The guard sends to verify, a successful verify sets mfaVerified=true and navigates to returnUrl, and the guard then passes. Login and SSO paths re-fetch `/api/me` before the guard decides (`refreshMe` resets loadPromise synchronously). `navigateByUrl(returnUrl)` stays in-app.
- Regular users: only one extra `isEnrolled` query on `/api/me` and profile responses. `UserInfoResponse` fields are additive.

## Recommended actions
1. Fix C1 (authorization-manager-based gate) and add encoded-path regression tests. **Blocker.**
2. Fix H1 with a pessimistic row lock on the MFA row for confirm/verify/disable.
3. Fix M1 with a conditional update or `@Version` on RefreshToken.
4. Correct the M2 docs/comments. Decide whether to fail fast or return a coded error.
5. Confirm or fix M3 scope.

## Unresolved questions
- M3: should regular users be able to enroll at all (the UI exposes it), or is it strictly admin-only?
- M2: is a silent ephemeral key acceptable in prod given the k8s secret is `optional: true`, or should startup fail when confirmed enrollments exist?
