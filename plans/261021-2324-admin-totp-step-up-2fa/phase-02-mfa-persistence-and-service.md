---
phase: 2
title: MFA persistence and service
status: completed
priority: P1
dependencies:
  - 1
effort: M
---

# Phase 2: MFA persistence and service

## Overview
Persist one TOTP enrollment per user and implement the enrollment / verification / disable rules (pending-then-confirmed enrollment, replay guard, 5-strikes lockout). Still no HTTP surface; `MfaService` is unit-tested against a real Postgres via the existing Testcontainers setup or with mocked repositories.

## Requirements
- Functional: begin enrollment (overwrites an unconfirmed one, refuses when confirmed), confirm with a valid code, verify with lockout + replay rules, disable with a valid code, query `isEnrolled`.
- Non-functional: `ddl-auto: validate` must still pass (entity mirrors migration exactly); secret only ever stored encrypted; verification code never logged.

## Architecture
```
MfaService
 ├─ UserMfaTotpRepository (JPA)        user_mfa_totp
 ├─ TotpCodec (phase 1)                generate/verify
 ├─ MfaSecretCipher (phase 1)          encrypt/decrypt secret
 └─ Clock                              injectable for tests
```
State machine per user: `none` → (`enroll`) `pending` → (`confirm`) `enrolled` → (`disable`) `none`. `enroll` from `pending` replaces the row; `enroll` from `enrolled` throws `MfaAlreadyEnrolledException`.

## Related Code Files
- Create: `engly-be/src/main/resources/db/migration/V12__create_user_mfa_totp_and_refresh_mfa_flag.sql`
- Create: `engly-be/src/main/java/com/engly/identity/domain/UserMfaTotp.java`
- Create: `engly-be/src/main/java/com/engly/identity/domain/InvalidMfaCodeException.java`
- Create: `engly-be/src/main/java/com/engly/identity/domain/MfaLockedException.java`
- Create: `engly-be/src/main/java/com/engly/identity/domain/MfaNotEnrolledException.java`
- Create: `engly-be/src/main/java/com/engly/identity/domain/MfaAlreadyEnrolledException.java`
- Create: `engly-be/src/main/java/com/engly/identity/infrastructure/UserMfaTotpRepository.java`
- Create: `engly-be/src/main/java/com/engly/identity/application/MfaService.java`
- Create: `engly-be/src/main/java/com/engly/identity/application/MfaEnrollment.java` (small record `{String secretBase32, String otpauthUri}` returned by `beginEnrollment`)
- Create: `engly-be/src/test/java/com/engly/identity/application/MfaServiceTest.java`
- Modify: `engly-be/src/main/java/com/engly/identity/domain/RefreshToken.java` (add `mfaVerified` + `markMfaVerified()`; the `rotate` behavior itself is phase 3)
- Modify: `engly-be/src/main/java/com/engly/identity/infrastructure/RefreshTokenRepository.java` (add `@Modifying @Query("update RefreshToken r set r.mfaVerified = false where r.userId = :userId") int clearMfaVerifiedByUserId(UUID userId)`)
- Modify: `engly-be/src/main/java/com/engly/common/GlobalExceptionHandler.java` (map the four new exceptions)

## Implementation Steps
1. Migration `V12` (same style as V11, comment header explaining the invariant):
   ```sql
   CREATE TABLE user_mfa_totp (
       user_id         uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
       secret_enc      varchar(255) NOT NULL,     -- base64url(iv || AES-GCM ciphertext); raw secret never stored
       confirmed_at    timestamptz NULL,          -- NULL = enrollment started but code not yet confirmed
       last_used_step  bigint NULL,               -- highest accepted TOTP step; rejects replay inside the window
       failed_attempts int NOT NULL DEFAULT 0,
       locked_until    timestamptz NULL,
       created_at      timestamptz NOT NULL DEFAULT now()
   );
   ALTER TABLE refresh_token ADD COLUMN mfa_verified boolean NOT NULL DEFAULT false;
   ```
2. Entity `UserMfaTotp` (`@Entity @Table(name="user_mfa_totp")`, `@Id UUID userId`, fields as above). Domain methods:
   - `boolean isConfirmed()`; `boolean isLocked(Instant now)`;
   - `void recordFailure(Instant now, int maxAttempts, Duration lockFor)` → increment; when `>= maxAttempts` set `lockedUntil = now + lockFor` and reset counter to 0;
   - `void recordSuccess(long step)` → `failedAttempts = 0; lockedUntil = null; lastUsedStep = step`;
   - `boolean isReplay(long step)` → `lastUsedStep != null && step <= lastUsedStep`;
   - `void confirm(Instant now, long step)`.
3. `RefreshToken`: add `@Column(name="mfa_verified") private boolean mfaVerified;`, getter, `markMfaVerified()`. Add a second constructor or a `boolean mfaVerified` parameter so phase 3 can mint a rotated token that inherits the flag.
4. Exceptions (extend `RuntimeException`, in `identity.domain`, same shape as `InvalidLoginException`). Handler mappings in `GlobalExceptionHandler` using the existing `buildResponse(status, message, code)` overload:
   - `InvalidMfaCodeException` → 400 `INVALID_MFA_CODE`
   - `MfaLockedException` → 429 `MFA_LOCKED` (message: "Too many attempts, try again later")
   - `MfaNotEnrolledException` → 409 `MFA_NOT_ENROLLED`
   - `MfaAlreadyEnrolledException` → 409 `MFA_ALREADY_ENROLLED`
5. `MfaService` (`@Service`, constructor: repo, `TotpCodec`, `MfaSecretCipher`, `RefreshTokenRepository`, `Clock`). There is no `java.time.Clock` bean today (`study/application/StudyClock.java` is a study-module wrapper, do not reuse it); add `@Bean Clock clock() { return Clock.systemUTC(); }` in a new `identity/config/MfaConfig.java`. Constants: `MAX_ATTEMPTS = 5`, `LOCK_DURATION = 15 min`.
   - `@Transactional MfaEnrollment beginEnrollment(UserAccount user)`: existing confirmed → throw `MfaAlreadyEnrolledException`; else `secret = codec.newSecret()`, upsert row with `secret_enc = cipher.encrypt(secret)`, `confirmed_at = null`, counters reset; return `(codec.toBase32(secret), codec.otpauthUri(label, secret))` where label = `user.getEmail()` or `user.getSub()` when email is null.
   - `@Transactional void confirmEnrollment(UserAccount user, String code)`: row must exist and be unconfirmed (else `MfaNotEnrolledException` / `MfaAlreadyEnrolledException`); run shared `checkCode(row, code)`; on success `row.confirm(now, step)`.
   - `@Transactional void verify(UserAccount user, String code)`: row must be confirmed (else `MfaNotEnrolledException`); `checkCode`; `recordSuccess`.
   - `@Transactional void disable(UserAccount user, String code)`: `verify` semantics, then `repo.delete(row)` and `refreshTokens.clearMfaVerifiedByUserId(user.getId())`.
   - `@Transactional(readOnly=true) boolean isEnrolled(UUID userId)` → row exists AND confirmed.
   - private `long checkCode(UserMfaTotp row, String code)`: `if (row.isLocked(now)) throw MfaLockedException`; `secret = cipher.decrypt(row.secretEnc)`; `OptionalLong step = codec.verify(secret, code, now)`; if empty OR `row.isReplay(step)` → `row.recordFailure(...)`, save, throw `InvalidMfaCodeException`; else return step. **Save the failure inside the same transaction before throwing** — mark the method so the rollback does not undo the counter: simplest is to catch inside a `@Transactional(propagation = REQUIRES_NEW)` helper, or make `checkCode` return a result object and let the public method persist then throw. Pick the second (no nested transaction): `checkCode` returns `CodeCheck(matchedStep | null)`; caller saves and throws.
6. `MfaServiceTest` (plain JUnit + Mockito, fixed `Clock`): enroll → pending row; enroll twice while pending replaces secret; enroll when confirmed throws; confirm with correct code (compute via `TotpCodec` from the Base32 returned) sets `confirmedAt`; wrong code ×5 → locked, 6th correct code throws `MfaLockedException`; same code twice → second throws `InvalidMfaCodeException`; lock expires after 15 min (advance clock); `disable` deletes row and calls `clearMfaVerifiedByUserId`.
7. `./mvnw test` — Flyway + `ddl-auto: validate` run inside the existing Testcontainers integration tests, so a column/entity mismatch fails here.

## Success Criteria
- [ ] V12 applies on a fresh DB and on a DB already at V11
- [ ] `MfaServiceTest` covers lockout, replay, state transitions
- [ ] Failed-attempt counter persists even though the request ends in an exception
- [ ] No log line contains a submitted code or decrypted secret

## Risk Assessment
- Counter lost on exception rollback → explicit design in step 5 (persist, then throw).
- Entity/migration drift → `ddl-auto: validate` catches at boot in integration tests.
- `secret_enc` length: 20-byte secret → 12+20+16 = 48 bytes → 64 base64 chars; `varchar(255)` is ample.
