# Red-Team Plan Review — Failure Mode Analyst (Flow Tracer)

Plan: `260715-2342-email-activation-on-registration`
Reviewer lens: Murphy's Law — race conditions, data loss, cascading failures, partial-failure states, deploy/rollback gaps.
Verdict: **DONE_WITH_CONCERNS** — two Critical deploy-time defects (mass lockout, orphaned accounts on SMTP failure) must be resolved before this plan is safe to execute.

---

## Finding 1: Deploy-time mass lockout — no email_verified backfill for existing accounts

- **Severity:** Critical
- **Location:** Phase 1, "Implementation Steps" step 2 (migration V11) + Phase 2, step 2 (login gate)
- **Flaw:** The login gate throws `EmailNotVerifiedException` (403) for any account with `email_verified=false`. Every pre-existing password account already has `email_verified=false`, and V11 only creates the token table — there is no `UPDATE app_user SET email_verified=true` backfill for accounts created before this deploy.
- **Failure scenario:** Plan ships. On next login, every existing user (including any seeded/admin/test accounts) gets 403 "email not verified". None of them have a pending token, so their only recovery is resend → email → verify. If SMTP is not yet wired in prod (see Finding 4), they are permanently locked out. This is a self-inflicted, whole-user-base outage at the moment of deploy.
- **Evidence:** `UserAccount.java:86-88` (`localUser(...)` passes `emailVerified=false`); `V6__create_users_and_auth.sql:19` (`email_verified boolean NOT NULL DEFAULT false`); V11 SQL in `phase-01-backend-data-mail-infra.md:50-61` contains no backfill; `grep "INSERT INTO app_user"` in V6 returns no seed rows, so all app_user rows are runtime-created and unverified.
- **Suggested fix:** Add to V11 a one-time `UPDATE app_user SET email_verified = true WHERE password_hash IS NOT NULL AND email_verified = false;` (grandfather existing password accounts). Explicitly decide and document this in the plan; do not leave it implicit.

## Finding 2: Orphaned account on SMTP failure — account committed, no email, cannot re-register

- **Severity:** Critical
- **Location:** Phase 2, step 3 (`register` calls `startVerification`) + Phase 1, step 9 (`startVerification` sends inside its own tx)
- **Flaw:** `AuthController.register` is not transactional. `UserAccountService.register` is `@Transactional` and commits the account when it returns. Only afterward does the controller call `emailVerificationService.startVerification(user)`, which sends the email. If SMTP is down/slow/throws, the account is already persisted but the email never sends and the request returns 500 (routed by the generic `Exception` handler).
- **Failure scenario:** User registers → account row committed → SMTP throws → user sees a 500, not the "check your inbox" confirmation screen. They retry register → `EmailAlreadyExistsException` → 409. They now own an unverified account, never received an email, and the FE never showed them the resend path. Stuck. At scale (SMTP outage) this silently manufactures dead accounts.
- **Evidence:** `UserAccountService.register` is `@Transactional` and returns after `users.save` (`UserAccountService.java:29-38`); `AuthController.register` calls services sequentially with no surrounding tx (`AuthController.java:36-42`); `GlobalExceptionHandler` maps unmapped exceptions to a generic 500 (`GlobalExceptionHandler.java:117-122`).
- **Suggested fix:** Make email-send failure non-fatal to registration: catch send failures in `startVerification`, log, and still return 202 so the FE shows the confirmation + resend path (resend re-attempts send). The account already exists and is recoverable via resend; a 500 is the worst outcome. Document the "registered but email delayed" state in Phase 3.

## Finding 3: SMTP call inside @Transactional — connection held across network I/O, commit-after-send dead links

- **Severity:** High
- **Location:** Phase 1, step 9 (`EmailVerificationService` is `@Transactional`, `send(...)` is the last statement)
- **Flaw:** `EmailVerificationService` is annotated `@Transactional`, and `startVerification` saves the token then calls `ActivationEmailSender.send(...)` — a blocking SMTP round-trip — while still inside the transaction. Two problems: (a) the DB connection is pinned for the full SMTP latency (pool exhaustion under a registration spike or a slow relay); (b) if the SMTP send succeeds but the transaction then fails to commit (connection drop at commit), the email goes out with a token that was rolled back → the link is permanently dead (`findByTokenHash` empty → 400).
- **Failure scenario:** Slow SES relay at 3s/send under a signup burst → HikariCP pool drains → unrelated requests time out. Separately, a commit failure after a successful send emails users a link that always returns "invalid or expired".
- **Evidence:** Plan makes the service `@Transactional` and sends inside it (`phase-01-backend-data-mail-infra.md:94-99`); the codebase pattern for send-after-DB does not exist yet, so this is net-new risk; connection-per-tx model confirmed by `AuthTokenService` `@Transactional` methods (`AuthTokenService.java:57`).
- **Suggested fix:** Send the email AFTER commit — either register a `TransactionSynchronization.afterCommit` callback, or split: persist token in a tx, send outside the tx. Never block a DB transaction on SMTP.

## Finding 4: Prod SMTP not wired — registration breaks on deploy, no feature flag / rollback path

- **Severity:** High
- **Location:** Phase 5 (docs/config) + plan "Key design choices" (no flag)
- **Flaw:** `application.yml` defaults `MAIL_HOST=localhost`/`1025`. The k8s backend manifest injects no `MAIL_*` env. Phase 5 only edits `.env.example` and docs — it does NOT add `MAIL_*` to the k8s Deployment. So in prod `JavaMailSender` targets `localhost:1025`, connection is refused, and every registration hits Finding 2's 500 + orphaned-account path. There is no feature flag to disable the hard gate, so rollback means redeploying the old jar AND backfilling `email_verified` for anyone who registered in the meantime.
- **Failure scenario:** Merge + deploy without also editing the k8s manifest and creating an SMTP secret → 100% of registrations fail in prod and existing users are locked out (Finding 1) with no email escape hatch.
- **Evidence:** `application.yml:56` frontend-url exists but SMTP defaults to localhost in `phase-01-backend-data-mail-infra.md:110-113`; k8s manifest env block has DB/JWT/S3/FRONTEND_URL but no `MAIL_*` (`k8s/20-engly-be-backend.yaml:26-83`); Phase 5 scope lists only `.env.example` + docs (`phase-05-docs-config.md:22-26`).
- **Suggested fix:** Add `MAIL_*` (+ `EMAIL_VERIFICATION_*`) to the k8s Deployment and an SMTP secret as an explicit Phase-5 task with an ordering constraint (config must land before/with code). Consider a `app.email-verification.enabled` flag so the gate can be disabled for a fast rollback without a redeploy.

## Finding 5: Concurrent / re-fired verify — no row lock, double session, error-after-success flash

- **Severity:** Medium
- **Location:** Phase 1, step 9 (`verify`) + Phase 3, step 3 (verify page auto-fires on init)
- **Flaw:** `verify(rawToken)` does `findByTokenHash` (plain SELECT, no `FOR UPDATE`), checks `isUsable`, then `consume`. Under READ_COMMITTED two concurrent requests both read `consumedAt=null`, both pass `isUsable`, both consume and both call `issueSession` — "single-use" is not enforced under concurrency (same weakness as `RefreshToken.rotate`, which also revokes without a pessimistic lock). The Angular verify page auto-calls `verifyEmail(token)` on init; a back-button/bfcache re-mount re-fires the now-consumed token → 400 error state shown AFTER a successful activation.
- **Failure scenario:** Email-client link prefetch + user click fire near-simultaneously → two sessions minted. Or: user activates, hits Back → component re-inits → 400 "invalid or expired" flashes on an account that is actually verified.
- **Evidence:** `verify` logic in `phase-01-backend-data-mail-infra.md:100-102` (no locking); reference pattern `AuthTokenService.rotate` reads-then-revokes without a lock (`AuthTokenService.java:77-83`); verify page auto-fires on init per `phase-03-frontend-activation-flow.md:56-60`; `verify-email` is NOT in the interceptor SKIP list but returns 200/400 (not 401) so the interceptor won't retry — the re-fire risk is client re-mount, not the interceptor (`auth-refresh.interceptor.ts:7-12`).
- **Suggested fix:** Enforce single-use atomically — a conditional `UPDATE ... SET consumed_at=now() WHERE token_hash=? AND consumed_at IS NULL` and treat 0 rows updated as already-consumed. On the FE, guard against re-fire (disable on submit; treat a consumed-token 400 after a known success as a benign "already activated → go to app").

## Finding 6: Resend invalidates prior tokens — old emailed link dies, undocumented FE state, interleave race

- **Severity:** Medium
- **Location:** Phase 1, step 9 (`startVerification` invalidates prior unconsumed tokens) + Phase 3 (error handling)
- **Flaw:** Each `startVerification` invalidates the user's prior unconsumed tokens before issuing a new one. A user who requests resend (e.g., first email was slow) and then clicks the ORIGINAL link gets 400. Phase 3's verify page only models generic `verifying/success/error` — it does not distinguish "this specific link was superseded". Also, register-email and a fast resend can both run `startVerification` concurrently; the "invalidate prior + insert new" sequence has no lock, so ordering can leave the wrong token active or both briefly valid.
- **Failure scenario:** Slow first email → user clicks resend → first email arrives → user clicks link #1 → 400. From the user's view the app emailed them a broken link.
- **Evidence:** `startVerification` "invalidate the user's prior unconsumed tokens" (`phase-01-backend-data-mail-infra.md:96-98`); resend re-invokes `startVerification` (`phase-01-backend-data-mail-infra.md:104-106`); FE error state is generic (`phase-03-frontend-activation-flow.md:58-60`).
- **Suggested fix:** Either keep prior tokens valid until expiry (accept multiple live tokens, all single-use) OR document the "newest link only" rule in the FE error copy ("this link is no longer valid — request a new one"). Serialize `startVerification` per user or make invalidation idempotent.

## Finding 7: No expiry sweeper for email_verification_token — unbounded growth

- **Severity:** Medium
- **Location:** Phase 1 (schema) — no cleanup task anywhere in the plan
- **Flaw:** Every register and every resend inserts a token row. The plan adds no scheduled cleanup for `email_verification_token`. If prior-token "invalidation" is an `@Modifying` UPDATE (one of the two options in step 5), consumed/expired rows accumulate forever. `refresh_token` already carries this exact debt (no sweeper), so this compounds an existing gap with a net-new table.
- **Failure scenario:** Long-running prod: table grows unbounded; the unique index on `token_hash` and the user-id index bloat; verify lookups slow over time.
- **Evidence:** No `@Scheduled` cleanup for tokens anywhere — only vocab-import sweepers exist (`grep @Scheduled` → `VocabImportRecoveryService.java:46`, `UploadCleanupScheduledTask.java:37`); plan step 5 leaves delete-vs-update open (`phase-01-backend-data-mail-infra.md:76-78`); no sweeper task in any phase.
- **Suggested fix:** Prefer `DELETE` for prior-token invalidation, and add a small `@Scheduled` sweep of expired/consumed rows (reuse the existing `@EnableScheduling` from `AsyncConfig`). Cheap now, avoids a future migration.

---

## Non-issues (checked, acceptable)

- **Login gate throwing inside `@Transactional(readOnly=true)`:** safe — rollback of a read-only tx is a no-op; the specific `@ExceptionHandler` precedes the generic one. (`UserAccountService.java:40-48`, `GlobalExceptionHandler.java:79-82`).
- **`verify` returns detached user to `issueSession`:** `issueSession` only reads already-loaded fields (`sub`, `id`, EAGER `roles`), so detached access is fine (`AuthTokenService.java:101-121`, `UserAccount.java:57-60`).
- **CSRF ignore-list additions:** consistent with existing register/login/refresh entries (`SecurityConfig.java:114`).
- **`app.frontend-url` for link building:** already present and server-side only (`application.yml:56`, `SsoAuthenticationSuccessHandler.java:33`).

## Unresolved questions

1. Does prod actually hold pre-existing password accounts that would be locked out (Finding 1), or is this a greenfield deploy? Backfill is cheap insurance either way.
2. Is a rollback feature flag (`email-verification.enabled`) in scope, or is redeploy-the-old-jar the accepted rollback story (Finding 4)?
3. For prior-token handling (Finding 6): keep old links valid until expiry, or newest-link-only with explicit FE copy?

Status: DONE_WITH_CONCERNS
Summary: Plan is coherent but carries two Critical deploy-time failure modes (whole-user-base 403 lockout with no email_verified backfill; account committed then orphaned when SMTP fails → 500 + 409 dead-end) plus SMTP-in-transaction, unwired-prod-SMTP/no-rollback-flag, verify race, resend link-invalidation UX, and unbounded token growth. Fix Findings 1-4 before executing.
