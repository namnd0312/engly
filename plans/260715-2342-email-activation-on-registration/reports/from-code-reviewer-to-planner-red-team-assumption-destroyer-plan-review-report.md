# Red-Team Plan Review — Email Activation on Registration
Reviewer lens: Assumption Destroyer / Scope Auditor. Hostile. Evidence-backed.
Plan: `plans/260715-2342-email-activation-on-registration/`

## Verdict
The plan's factual claims about existing code are mostly accurate (V11 next, gen_random_uuid, sha256 in AuthTokenService, buildResponse Map.of, Testcontainers/Karma styles all verified true). But it hides one **Critical** rollout regression and several unstated assumptions that will bite at runtime, not at compile time.

---

## Finding 1: Every existing password account is instantly locked out (no backfill)
- **Severity:** Critical
- **Location:** Phase 2, step 2 ("login gate") + Phase 1 migration (no data step)
- **Flaw:** `UserAccount.localUser(...)` hard-codes `emailVerified = false` (UserAccount.java:87) and today's login has **no verification gate** — it only checks the password (UserAccountService.java:41-45). So all current password users sit in the DB with `email_verified = false` and log in fine. The moment Phase 2 adds `if (!user.isEmailVerified()) throw EmailNotVerifiedException` after the password match, **every pre-existing password account starts returning 403** on next login. Migration V11 (Phase 1) creates a new table but runs **no `UPDATE app_user SET email_verified = true`** backfill for accounts that predate the feature.
- **Failure scenario:** Deploy to prod → 100% of existing password users are locked out on their next login, forced through "resend activation" with zero prior communication. Support fire, churn.
- **Evidence:** `engly-be/src/main/java/com/engly/identity/domain/UserAccount.java:87` (`localUser` → false); `engly-be/src/main/java/com/engly/identity/application/UserAccountService.java:41-45` (login has no emailVerified check today); `engly-be/src/main/resources/db/migration/V6__create_users_and_auth.sql` (`email_verified boolean NOT NULL DEFAULT false`). No backfill in `phase-01` migration SQL.
- **Suggested fix:** Add an explicit decision + migration step: either V11 backfills `UPDATE app_user SET email_verified = true WHERE password_hash IS NOT NULL` (grandfather existing users — recommended, they've been using the account), or the plan documents an intentional forced re-verification with a user comms plan. This is a user-facing decision; surface it, don't leave it implicit.

## Finding 2: `EmailVerificationProperties` has no defined registration home
- **Severity:** Medium
- **Location:** Phase 1, step 7 ("register via @EnableConfigurationProperties where AuthProperties is registered")
- **Flaw:** This repo has **no central `@ConfigurationPropertiesScan`**. Each config class self-registers its own props: `JwtKeyConfig` registers `AuthProperties`, `S3ClientConfig` registers `AwsProperties`, `SocialLoginClientsConfig` registers `SocialLoginProperties`. "Where AuthProperties is registered" is `JwtKeyConfig` — a JWT-key-derivation class, a semantically wrong home for email props. The plan creates **no config class** for email verification (`ActivationEmailSender` is an `@Service`, not `@Configuration`), so there is no place the `@EnableConfigurationProperties(EmailVerificationProperties.class)` actually lands.
- **Failure scenario:** Implementer either dumps it into `JwtKeyConfig` (misfiled, confusing) or forgets it → `NoSuchBeanDefinitionException: EmailVerificationProperties` at startup, or silent null injection.
- **Evidence:** `engly-be/src/main/java/com/engly/identity/config/JwtKeyConfig.java:32`; `S3ClientConfig.java:35`; `SocialLoginClientsConfig.java:32`. No `@ConfigurationPropertiesScan` anywhere (grep empty).
- **Suggested fix:** Name the concrete registration site. Cleanest: annotate the new `ActivationEmailSender`'s owning config, or add `@EnableConfigurationProperties(EmailVerificationProperties.class)` to a small new `MailConfig`/`SecurityConfig`. Do not overload `JwtKeyConfig`.

## Finding 3: New route uses eager `component:` and breaks the lazy `loadComponent` convention
- **Severity:** Medium
- **Location:** Phase 3, step 5 (`{ path: 'verify-email', component: VerifyEmailPageComponent }`)
- **Flaw:** Every route in `app.routes.ts` — including `/login` and `/register` — uses `loadComponent: () => import(...)`. The plan's eager `component:` form forces a top-level static import of `VerifyEmailPageComponent` into `app.routes.ts`, bundling it into the initial chunk and diverging from the established pattern. Phase 3 asks to "confirm the routes file name" (it is `src/app/app.routes.ts`) but overlooks the **loading convention**.
- **Failure scenario:** Compiles fine, ships an inconsistent route + eager import; reviewer churn, larger main bundle.
- **Evidence:** `engly-fe/src/app/app.routes.ts` — all 7 feature routes use `loadComponent`/`loadChildren`; none use eager `component:`.
- **Suggested fix:** `{ path: 'verify-email', loadComponent: () => import('./features/auth/verify-email-page.component').then(m => m.VerifyEmailPageComponent) }`.

## Finding 4: Web-layer activation test will hit real SMTP (register triggers a send)
- **Severity:** Medium
- **Location:** Phase 4, step 2 (`AuthActivationFlowTest`)
- **Flaw:** Phase 4 step 1 mocks the sender for the service test, but step 2's full-stack `AuthActivationFlowTest` exercises `POST /api/auth/register`, which (Phase 2 step 3) calls `emailVerificationService.startVerification` → `ActivationEmailSender.send` → `JavaMailSender`. Step 2 never states the sender is mocked. `spring-boot-starter-mail` autoconfigures a real `JavaMailSender` pointed at `localhost:1025` (the default in Phase 1 yml).
- **Failure scenario:** In CI with no Mailpit, `register` throws `MailConnectException` (connection refused) → the whole activation-flow integration test fails on setup, unrelated to the assertion under test.
- **Evidence:** `phase-04` step 1 mocks sender, step 2 omits it; `phase-01` step 10 sets `MAIL_PORT:1025` default; `phase-02` step 3 wires `startVerification` into register.
- **Suggested fix:** Explicitly `@MockBean ActivationEmailSender` (or `JavaMailSender`) in `AuthActivationFlowTest` too, and assert `send(...)` was invoked. State it in the plan.

## Finding 5: `code` in error body — verify the FE-visible shape is preserved for ALL handlers
- **Severity:** Medium
- **Location:** Phase 2, step 6 + Risk note ("extend buildResponse / small overload")
- **Flaw:** `buildResponse` returns an immutable `Map.of(...)` (GlobalExceptionHandler.java:124-131) shared by ~12 handlers. FE currently reads `err.error?.message` (register-page.component.ts:200) and branches on `err.status` only (login-page.component.ts:193). Adding `code` for one handler via a mutable-map/overload is fine **only if** the base four keys (status/error/message/timestamp) remain unchanged for every existing handler. The plan flags this but doesn't pin the contract, and the new FE branch relies on `err.error?.code === 'EMAIL_NOT_VERIFIED'` which must actually be emitted by the 403 handler (not the generic one).
- **Failure scenario:** A careless refactor of `buildResponse` shape drops/renames `message` → existing 409/400 error copy on register/login silently breaks.
- **Evidence:** `engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java:124-131` (Map.of, 4 keys); `engly-fe/.../register-page.component.ts:200`; `login-page.component.ts:193`.
- **Suggested fix:** Keep the existing 4-key shape untouched; add `code` only on the `EmailNotVerifiedException` response via a dedicated overload. Add a test asserting existing handlers' body shape is unchanged.

## Finding 6: New unauthenticated endpoints not added to interceptor SKIP list (latent refresh/logout loop)
- **Severity:** Low
- **Location:** Phase 3 (auth.service methods) — interceptor untouched
- **Flaw:** `auth-refresh.interceptor.ts` retries any `/api/**` 401 by calling `auth.refresh()`, except `SKIP_REFRESH_PATHS = [login, register, refresh]` and `/api/me`. The new unauthenticated POSTs `/api/auth/verify-email` and `/api/auth/resend-verification` are **not** added. Current status contract (200/400/403/202) never returns 401, so it's safe *today* — but the omission is inconsistent with login/register/refresh being skipped, and any future 401 (e.g., a filter misfire on the unauthenticated POST) would trigger a pointless `refresh()` that itself 401s → `auth.logout()` on a user who was never logged in.
- **Failure scenario:** Low today; a latent trap if verify-email ever returns 401.
- **Evidence:** `engly-fe/src/app/core/auth-refresh.interceptor.ts:8-10` (skip list), `:41` (401 gate).
- **Suggested fix:** Add both new paths to `SKIP_REFRESH_PATHS` for parity with the other pre-session auth endpoints.

## Finding 7: Single-use token + auto-verify-on-page-load makes refresh/re-open show a false "expired" error
- **Severity:** Low
- **Location:** Phase 3, step 3 (verify page calls `verifyEmail(token)` on init) + Phase 1 (single-use consume)
- **Flaw:** The token is single-use (`consume`); the SPA `/verify-email` component POSTs it on init. If the user refreshes the page, navigates back, or the browser re-opens the tab after success, the component re-fires `verifyEmail(sameToken)` → backend returns 400 (already consumed) → the UI shows "link invalid/expired" **even though activation already succeeded and the user is logged in**. (Note: the design correctly avoids the email-prefetch-consumes-token bug since consume is a JS-triggered POST, not the GET link — good.)
- **Failure scenario:** Activated user hits refresh on the verify page and sees a scary "expired link" error; support confusion.
- **Evidence:** `phase-03` step 3 (verifyEmail on init, `success`/`error` states); `phase-01` step 9 (`verify` throws `InvalidVerificationTokenException` on unusable token).
- **Suggested fix:** In the verify component, if a session already exists (`auth.isAuthenticated()`), short-circuit to the success/`redirect to /` state instead of re-POSTing; or treat "already-consumed while authenticated" as success.

---

## Verified-accurate plan claims (no action)
- V10 is latest migration; V11 is correct next. (`db/migration/` listing)
- `gen_random_uuid()` + `timestamptz` idiom used by V6/V10 — matches plan's V11.
- Private `sha256` exists only in `AuthTokenService` (application layer); `JwtKeyConfig` has a separate `byte[] sha256` for HMAC key derivation (different signature, correctly not unified). Extraction is safe. (AuthTokenService.java:167; JwtKeyConfig.java:71)
- `app.frontend-url` property exists (application.yml:56) — Phase 1 step 8 injection is valid.
- `/api/auth/**` is served by the API filter chain whose CSRF `ignoringRequestMatchers` already lists login/register/refresh (SecurityConfig.java:114); the oauth2 chain is scoped to `/oauth2/**` (SecurityConfig.java:89). Plan's CSRF edit target is correct.
- `register` has exactly one FE consumer: `register-page.component.ts:190`. Plan's "only register-page" claim holds.
- FE specs use `HttpTestingController` + `provideHttpClientTesting` (auth.service.spec.ts:24-28) — Phase 4 assumption correct.
- `ddl-auto: validate` (application.yml:93) + db-managed `created_at` via `insertable=false` mirrors RefreshToken (RefreshToken.java:34) — validate will pass if the new entity copies that idiom exactly.

## Unresolved questions
1. Grandfather existing password users, or force-reverify with comms? (Finding 1 — needs a user decision.)
2. Concurrency on `resend`: two rapid requests can both pass the `findFirstByUserIdOrderByCreatedAtDesc` cooldown check and each issue a token. Acceptable, or serialize? (Low, not scored.)
3. Does prod actually run Mailpit-style log-only in staging, or is a real relay required before this ships? (affects Finding 4 CI setup.)

Status: DONE_WITH_CONCERNS
Summary: Plan is factually accurate about the codebase but conceals a Critical rollout regression — existing password accounts (all `email_verified=false`) get locked out with no backfill — plus a missing config-properties registration home, a route-convention break, and a web-test that will hit real SMTP.
