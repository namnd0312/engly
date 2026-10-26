---
phase: 1
title: TOTP codec and secret cipher
status: completed
priority: P1
dependencies: []
effort: S
---

# Phase 1: TOTP codec and secret cipher

## Overview
Pure, dependency-free building blocks: an RFC 6238 TOTP codec (with Base32) and an AES-256-GCM cipher for the stored secret, plus their config binding. No DB, no web. Fully unit-tested before anything else depends on them.

## Requirements
- Functional: generate/verify 6-digit TOTP (SHA-1, 30s step, window ±1); Base32 encode (RFC 4648, uppercase, no padding) for the otpauth URI; AES-GCM encrypt/decrypt of raw secret bytes.
- Non-functional: constant-time comparison; no new Maven dependency; secret key derived from env exactly like `JwtKeyConfig` derives the JWT key.

## Architecture
- `TotpCodec` is stateless. `verify` returns the matched time-step (`OptionalLong`) so the caller can enforce replay protection (`last_used_step`).
- `MfaSecretCipher` holds one `SecretKey`; ciphertext layout = `base64url(iv[12] || ciphertext+tag[16])`.
- `MfaProperties` record bound to `app.security.mfa.*` with defaults, mirroring `AuthProperties`.

## Related Code Files
- Create: `engly-be/src/main/java/com/engly/identity/application/TotpCodec.java`
- Create: `engly-be/src/main/java/com/engly/identity/application/MfaSecretCipher.java`
- Create: `engly-be/src/main/java/com/engly/identity/config/MfaProperties.java`
- Create: `engly-be/src/test/java/com/engly/identity/application/TotpCodecTest.java`
- Create: `engly-be/src/test/java/com/engly/identity/application/MfaSecretCipherTest.java`
- Modify: `engly-be/src/main/resources/application.yml` (add `app.security.mfa` block)
- Modify: `engly-be/src/main/java/com/engly/identity/config/JwtKeyConfig.java` — `@EnableConfigurationProperties(AuthProperties.class)` becomes `({AuthProperties.class, MfaProperties.class})` (that is the only place `AuthProperties` is registered).

## Implementation Steps
1. `MfaProperties(String secretKey, String issuer, int digits, int periodSeconds, int window)` with compact-constructor defaults: issuer `"Engly"`, digits 6, period 30, window 1. Bind to `app.security.mfa`. In `application.yml`:
   ```yaml
   app:
     security:
       mfa:
         # AES-256-GCM key (any string, SHA-256-derived) protecting stored TOTP secrets. Blank = ephemeral
         # key at startup (dev only): every restart invalidates all enrollments.
         secret-key: ${MFA_SECRET_KEY:}
         issuer: Engly
   ```
2. `TotpCodec` (plain class, `@Component`, constructor takes `MfaProperties`):
   - `byte[] newSecret()` → 20 random bytes from `SecureRandom`.
   - `String toBase32(byte[])` — RFC 4648 alphabet `A-Z2-7`, no padding.
   - `String generate(byte[] secret, long step, int digits)` — HMAC-SHA1 (`javax.crypto.Mac`, key = raw secret) over big-endian 8-byte step, dynamic truncation (RFC 4226 §5.4), `% 10^digits`, zero-padded.
   - `long currentStep(Instant now)` = `now.getEpochSecond() / periodSeconds`.
   - `OptionalLong verify(byte[] secret, String code, Instant now)` — iterate `step-window..step+window`, compare with `MessageDigest.isEqual(code.getBytes(US_ASCII), candidate.getBytes(US_ASCII))`; return first matching step; **do not short-circuit on length mismatch before the loop in a way that leaks timing** (reject non-6-digit input up front with a normal check is fine, since the format is public).
   - `String otpauthUri(String accountLabel, byte[] secret)` → `otpauth://totp/{issuer}:{label}?secret={b32}&issuer={issuer}&algorithm=SHA1&digits=6&period=30`, URL-encode label and issuer.
3. `MfaSecretCipher` (`@Component`, constructor takes `MfaProperties`):
   - Key: if `secretKey` has text → `new SecretKeySpec(sha256(secretKey), "AES")`; else random 32 bytes + `log.warn("No app.security.mfa.secret-key configured — generating an EPHEMERAL key; all TOTP enrollments are invalidated on restart.")`. Copy the `sha256` helper shape from `JwtKeyConfig`.
   - `String encrypt(byte[] plain)` — `Cipher.getInstance("AES/GCM/NoPadding")`, 12-byte random IV, `GCMParameterSpec(128, iv)`, output `Base64.getUrlEncoder().withoutPadding().encodeToString(iv || ct)`.
   - `byte[] decrypt(String stored)` — inverse; wrap `AEADBadTagException`/`GeneralSecurityException` in `IllegalStateException("Cannot decrypt TOTP secret — MFA_SECRET_KEY changed?")`.
4. Tests:
   - `TotpCodecTest`: RFC 6238 Appendix B SHA-1 vectors with secret `"12345678901234567890".getBytes(US_ASCII)` and `digits=8` → `T=59→94287082`, `1111111109→07081804`, `1111111111→14050471`, `1234567890→89005924`, `2000000000→69279037`, `20000000000→65353130` (step = T/30). Also: 6-digit output is the last 6 digits of the 8-digit one; `verify` accepts step±1 and rejects step±2; rejects `"12345"`, `"abcdef"`, null; Base32 of `"foobar"` is `MZXW6YTBOI`; otpauth URI contains encoded label and issuer.
   - `MfaSecretCipherTest`: round-trip; two encryptions of same plaintext differ (random IV); tampered ciphertext throws `IllegalStateException`; cipher built with a different key cannot decrypt.
5. Run `cd engly-be && ./mvnw -q test -Dtest='TotpCodecTest,MfaSecretCipherTest'`, then full `./mvnw test`.

## Success Criteria
- [ ] All RFC 6238 vectors pass with `digits=8`
- [ ] No new entry in `pom.xml`
- [ ] App boots with blank `MFA_SECRET_KEY` and logs the WARN once
- [ ] Full backend suite green

## Risk Assessment
- Off-by-one in step arithmetic → caught by vectors (`T=59` is step 1, not 0).
- Timing leak in comparison → `MessageDigest.isEqual` only; never `String.equals`.
- Ephemeral key silently used in k8s → phase 5 wires the env var and documents it next to `JWT_SECRET`.
