---
phase: 1
title: "Backend data & mail infra"
status: pending
effort: "M"
---

# Phase 1: Backend data & mail infra

## Overview

Add the persistence + email plumbing the flow needs: a mail dependency, a hashed
single-use verification-token table/entity/repository, config properties, an email
sender, and the `EmailVerificationService`. No API/controller changes yet.

## Requirements

- Functional: generate a single-use, time-limited token per account; send an
  activation email with a link to the SPA; verify a raw token; resend with cooldown.
- Non-functional: store only the SHA-256 hash (mirror `RefreshToken`); no account
  enumeration; provider-agnostic SMTP; boots without SMTP configured (dev).

## Architecture

- `email_verification_token` mirrors `refresh_token`: hash + expiry + single-use
  marker (`consumed_at` nullable). Raw token lives only in the emailed link.
- `EmailVerificationService` owns start/verify/resend; `ActivationEmailSender`
  wraps `JavaMailSender` and builds the link from `app.frontend-url` + verify path.
- Token hashing is shared with `AuthTokenService` via a new package util (DRY).

## Related Code Files

- Create: `engly-be/src/main/resources/db/migration/V11__create_email_verification_token.sql`
- Create: `engly-be/src/main/java/com/engly/identity/domain/EmailVerificationToken.java`
- Create: `engly-be/src/main/java/com/engly/identity/infrastructure/EmailVerificationTokenRepository.java`
- Create: `engly-be/src/main/java/com/engly/identity/application/TokenHashing.java` (shared `sha256`)
- Create: `engly-be/src/main/java/com/engly/identity/config/EmailVerificationProperties.java`
- Create: `engly-be/src/main/java/com/engly/identity/config/MailConfig.java` (registers the properties + owns any mail beans — do NOT overload `JwtKeyConfig`)
- Create: `engly-be/src/main/java/com/engly/identity/application/ActivationEmailSender.java`
- Create: `engly-be/src/main/java/com/engly/identity/application/EmailVerificationService.java`
- Modify: `engly-be/pom.xml` (add `spring-boot-starter-mail`)
- Modify: `engly-be/src/main/java/com/engly/identity/application/AuthTokenService.java` (use `TokenHashing.sha256`)
- Modify: `engly-be/src/main/java/com/engly/identity/domain/UserAccount.java` (add `markEmailVerified()`)
- Modify: `engly-be/src/main/resources/application.yml` (`spring.mail.*`, `app.email-verification.*`)

## Implementation Steps

1. **pom.xml**: add `org.springframework.boot:spring-boot-starter-mail`.

2. **Migration `V11`** (next in sequence after V10):
   ```sql
   CREATE TABLE email_verification_token (
       id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
       token_hash  VARCHAR(255) NOT NULL,
       expires_at  TIMESTAMPTZ NOT NULL,
       consumed_at TIMESTAMPTZ,
       created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
   );
   CREATE UNIQUE INDEX ux_evt_token_hash ON email_verification_token(token_hash);
   CREATE INDEX ix_evt_user_id ON email_verification_token(user_id);

   -- Grandfather existing password accounts so the new login gate does not lock
   -- out the entire current user base (they were all created email_verified=false
   -- and there was no verification step before this feature).
   UPDATE app_user SET email_verified = true
   WHERE password_hash IS NOT NULL AND email_verified = false;
   ```
   (Match V10's UUID default style — verify `gen_random_uuid()`/pgcrypto is already
   used by prior migrations; `refresh_token` in V6 is the reference. **The backfill is
   mandatory** — without it, adding the Phase 2 login gate 403s every existing account.)

3. **`TokenHashing`**: move the private `sha256(String)` body out of
   `AuthTokenService` into a small package-private final util class; update
   `AuthTokenService` to call it (behavior identical — same Base64(SHA-256)).

4. **`EmailVerificationToken` entity**: mirror `RefreshToken` — `userId`,
   `tokenHash`, `expiresAt`, `consumedAt` (nullable), `createdAt` (db-managed).
   Methods: `isUsable(Instant now)` = `consumedAt == null && now.isBefore(expiresAt)`
   (used for the expiry check; single-use is enforced atomically in the repo, not by a
   read-then-write). Ctor `(userId, tokenHash, expiresAt)`.

5. **`EmailVerificationTokenRepository`** (`JpaRepository<…, UUID>`):
   - `Optional<EmailVerificationToken> findByTokenHash(String hash)` (to read
     `expiresAt`/`userId` for the response).
   - **Atomic single-use claim** (fixes the read-then-write TOCTOU — two concurrent
     verifies must NOT both mint a session):
     ```java
     @Modifying @Query("update EmailVerificationToken t set t.consumedAt = :now " +
       "where t.tokenHash = :hash and t.consumedAt is null and t.expiresAt > :now")
     int consumeIfUsable(@Param("hash") String hash, @Param("now") Instant now);
     ```
     `verify` treats **1 row affected = success, 0 = invalid/expired/already-used**.
   - `Optional<EmailVerificationToken> findFirstByUserIdOrderByCreatedAtDesc(UUID userId)`
     (cooldown check on resend).
   - `int deleteByUserId(UUID userId)` — used to **invalidate prior tokens by DELETE**
     (not update) so the table stays bounded to ~1 unconsumed row per user. No separate
     sweeper needed for the common path (see Risk).

6. **`UserAccount.markEmailVerified()`**: sets `emailVerified = true`. (Field exists.)

7. **`EmailVerificationProperties`** (`@ConfigurationProperties("app.email-verification")`):
   `boolean enabled` (default true — **kill-switch**: when false, register auto-verifies +
   issues a session, i.e. today's behavior, so the hard gate can be rolled back without a
   redeploy of old code), `Duration tokenTtl` (24h), `Duration resendCooldown` (60s),
   `String fromAddress`, `String verifyPath` (default `/verify-email`).
   Register it with `@EnableConfigurationProperties(EmailVerificationProperties.class)` on
   the **new `MailConfig`** `@Configuration` class — there is no central
   `@ConfigurationPropertiesScan`; each config self-registers (`JwtKeyConfig`,
   `S3ClientConfig`, `SocialLoginClientsConfig` are the precedent). Do **not** attach it to
   `JwtKeyConfig` (wrong semantic home).

8. **`ActivationEmailSender`**: constructor-inject `JavaMailSender`,
   `EmailVerificationProperties`, and `app.frontend-url`. Method
   `send(String toEmail, String rawToken)` builds
   `frontendUrl + verifyPath + "?token=" + urlEncode(rawToken)` and sends a
   `SimpleMailMessage` (plain text, Vietnamese subject/body to match the SPA).
   Log the link at INFO (dev visibility when using Mailpit/log-only SMTP).
   **Swallow send failures** (try/catch → log at WARN, do not rethrow) so a down/slow
   SMTP server cannot fail the register/resend request. (Token-in-URL leakage is hardened
   on the SPA side in Phase 3 — `Referrer-Policy` + strip on load.)

9. **`EmailVerificationService`** (`@Service`; methods `@Transactional`):
   - `startVerification(UserAccount user)`: `deleteByUserId(user.getId())` (invalidate
     prior tokens via DELETE → table bounded); generate 32 random bytes → URL-safe Base64
     raw token; save `EmailVerificationToken(user.getId(), TokenHashing.sha256(raw), now +
     tokenTtl)`. **Dispatch the email AFTER the surrounding transaction commits and OUTSIDE
     it** — never block a DB connection on SMTP and never email a link for a row that then
     rolls back. Use `TransactionSynchronizationManager.registerSynchronization(afterCommit
     -> sender.send(email, raw))`. The **send is non-fatal**: `ActivationEmailSender`
     catches/logs failures so a dead SMTP server never turns registration into a 500 (the
     account exists; the user can use "resend"). (No-op cleanly if `user.getEmail()` is
     null — password accounts always have email.)
   - `verify(String rawToken) -> UserAccount`: `int n = consumeIfUsable(sha256(raw), now)`;
     if `n == 0` throw `InvalidVerificationTokenException` (atomic single-use — no
     read-then-write race); else `findByTokenHash` → load user → `markEmailVerified()`;
     return the user (caller issues session).
   - `resend(String email)`: normalize email (reuse `EmailNormalizer`); find user;
     if absent OR already verified → return silently (generic, no enumeration). Else check
     the most recent token's `createdAt` against `resendCooldown`; if within cooldown,
     return silently; otherwise `startVerification(user)`.
   - When `properties.enabled() == false`, `startVerification` short-circuits (caller
     auto-verifies + issues a session instead — see Phase 2 register step).

10. **`application.yml`**: add
    ```yaml
    spring:
      mail:
        host: ${MAIL_HOST:localhost}
        port: ${MAIL_PORT:1025}          # Mailpit default for dev
        username: ${MAIL_USERNAME:}
        password: ${MAIL_PASSWORD:}
        properties:
          mail.smtp.auth: ${MAIL_SMTP_AUTH:false}
          mail.smtp.starttls.enable: ${MAIL_SMTP_STARTTLS:false}
    app:
      email-verification:
        enabled: ${EMAIL_VERIFICATION_ENABLED:true}
        token-ttl: ${EMAIL_VERIFICATION_TTL:24h}
        resend-cooldown: ${EMAIL_VERIFICATION_RESEND_COOLDOWN:60s}
        from-address: ${MAIL_FROM:no-reply@engly.local}
        verify-path: /verify-email
    ```

## Success Criteria

- [ ] `./mvnw compile` passes; app boots with default (Mailpit/localhost) mail config.
- [ ] `AuthTokenService` refresh-token hashing behavior unchanged after extract.
- [ ] `V11` applies cleanly on a fresh DB (Testcontainers), validates under
      `ddl-auto: validate`, and its backfill sets existing password accounts to
      `email_verified=true`.
- [ ] `consumeIfUsable` returns 1 on first call, 0 on a second call with the same token.
- [ ] Register does NOT fail (no 500) when SMTP is unreachable; the token row still exists.
- [ ] `EmailVerificationService` unit-testable with a mocked sender + repository.

## Risk Assessment

- **DDL default mismatch**: confirm the UUID default + `TIMESTAMPTZ` style match V6/V10
  so `ddl-auto: validate` doesn't fail. Mitigation: copy `refresh_token`'s exact idiom.
- **Boot failure when SMTP absent**: `spring-boot-starter-mail` autoconfigures a sender
  even with no server; sends fail only at send-time, and the sender swallows them — boot
  and registration are unaffected. Dev uses Mailpit/log.
- **Token table growth (residual)**: DELETE-on-invalidate bounds the common path to ~1
  unconsumed row per user, but consumed/expired rows still linger. Accepted for now (same
  debt as `refresh_token`, which also has no sweeper); a `@Scheduled` purge is a cheap
  follow-up if it ever matters — deliberately NOT built now (YAGNI).
- **Post-commit send never retries**: if SMTP is down at register time the email is simply
  lost (logged); recovery is the user-driven "resend". Acceptable for activation email.
