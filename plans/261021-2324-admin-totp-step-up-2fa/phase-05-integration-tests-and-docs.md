---
phase: 5
title: Integration tests and docs
status: completed
priority: P2
dependencies:
  - 3
  - 4
effort: S
---

# Phase 5: Integration tests and docs

## Overview
Lock the end-to-end contract with one backend integration test, wire the new env var into k8s, and document operation (enable, recovery, clock skew). Also the sweep for anything phases 1–4 left inconsistent.

## Requirements
- Functional: a single MockMvc + Testcontainers test walks the full admin step-up flow; k8s manifest carries `MFA_SECRET_KEY`.
- Non-functional: docs stay under the `docs.maxLoc` limit; no secrets committed; README test counts updated.

## Architecture
Test uses the same scaffolding as `AuthFlowIntegrationTest` (`@SpringBootTest @AutoConfigureMockMvc @Testcontainers`, `@MockitoBean ActivationEmailSender`). Admin is created by registering, verifying, then inserting `ROLE_ADMIN` through `UserAccountRepository` (add role on the entity + save) — no SQL string in the test. Codes are computed in-test with the real `TotpCodec` from the Base32 secret returned by `/enroll`.

## Related Code Files
- Create: `engly-be/src/test/java/com/engly/identity/MfaStepUpFlowIntegrationTest.java`
- Modify: `k8s/20-engly-be-backend.yaml` (env `MFA_SECRET_KEY` from secret `engly-jwt-secret`, key `MFA_SECRET_KEY`, `optional: true`, with the same style of warning comment as `JWT_SECRET`)
- Modify: `docs/deployment-guide.md` (env table row + "Admin 2FA" ops subsection)
- Modify: `docs/system-architecture.md` (auth section: `mfa` claim, `MfaStepUpFilter`, new table)
- Modify: `docs/codebase-summary.md` (new files/endpoints rows)
- Modify: `README.md` (test counts; one line under Tests or Local Run on enabling admin 2FA)
- Modify: `engly-be/.env.example` (add `MFA_SECRET_KEY=` with a one-line comment; file exists, currently has no `JWT_SECRET` line either — add both)

## Implementation Steps
1. `MfaStepUpFlowIntegrationTest` scenarios (one method each, shared helper to obtain an admin session's cookies + CSRF token):
   - `adminWithoutEnrollment_adminApiReturns403EnrollmentRequired`
   - `enrollConfirm_thenAdminApiReturns403MfaRequired`
   - `verify_thenAdminApiReturns200_andSurvivesRefresh` (call `/api/auth/refresh`, re-hit admin API with the new cookies)
   - `verify_withoutSession_returns401`
   - `fiveWrongCodes_lockOut_returns429`
   - `sameCodeTwice_secondIsRejected`
   - `disable_thenAdminApiReturns403Again`
   - `regularUser_meHasFlagsFalse_andStudyApiUnaffected`
   Assert the JSON `code` field on every 4xx.
2. k8s: add under the `JWT_SECRET` block:
   ```yaml
   # AES key protecting stored TOTP secrets. Optional: absent → EPHEMERAL key, every restart wipes
   # all admin 2FA enrollments (admins must re-enroll). Set MFA_SECRET_KEY alongside JWT_SECRET.
   - name: MFA_SECRET_KEY
     valueFrom:
       secretKeyRef: { name: engly-jwt-secret, key: MFA_SECRET_KEY, optional: true }
   ```
   Nothing in `k8s/*.sh`, README or docs currently creates `engly-jwt-secret` (it is `optional: true`, so OrbStack boots on ephemeral keys). Add one documented command to `docs/deployment-guide.md` covering both keys:
   `kubectl -n engly create secret generic engly-jwt-secret --from-literal=JWT_SECRET="$(openssl rand -base64 48)" --from-literal=MFA_SECRET_KEY="$(openssl rand -base64 48)"`.
3. `docs/deployment-guide.md`:
   - Env table row: `MFA_SECRET_KEY | — (ephemeral) | Key protecting stored TOTP secrets (AES-256-GCM, SHA-256-derived). Blank = ephemeral: restart invalidates all admin 2FA enrollments. Set for any persistent deployment.`
   - New subsection "Admin two-factor (TOTP)": what is enforced (`/api/admin/**` needs a verified code), how to enroll (Profile → Bảo mật), recovery when the device is lost:
     ```sql
     DELETE FROM user_mfa_totp WHERE user_id = (SELECT id FROM app_user WHERE email = '<admin email>');
     UPDATE refresh_token SET mfa_verified = false WHERE user_id = (SELECT id FROM app_user WHERE email = '<admin email>');
     ```
     plus a note on clock skew (server and phone must agree within ±30s; check `date -u` in the pod).
   - While editing the table: the `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` rows describe an RSA scheme the code no longer uses (`JwtKeyConfig` is HS256 via `JWT_SECRET`). Replace them with a single `JWT_SECRET` row. Small, adjacent, and otherwise misleading next to the new row.
4. `docs/system-architecture.md`: in the auth/session section add the `mfa` claim, the `refresh_token.mfa_verified` carry-over, `MfaStepUpFilter` position in the chain, the `user_mfa_totp` table, and the error-code list from `plan.md`.
5. `docs/codebase-summary.md`: add rows for `MfaController`, `MfaService`, `TotpCodec`, `MfaSecretCipher`, `MfaStepUpFilter`, `mfa-settings.component.ts`, `mfa-verify-page.component.ts`.
6. README: bump test counts after running both suites; add one sentence under Local Run: "Admin accounts must enable 2FA (Profile → Bảo mật) before `/admin` works; set `MFA_SECRET_KEY` so enrollments survive restarts."
7. Final sweep: grep the repo for `isAdmin, null, null)` leftovers, `TODO mfa`, and run `./mvnw test` + `npx ng test` + `npx ng build` one last time.

## Success Criteria
- [ ] `MfaStepUpFlowIntegrationTest` green under Testcontainers
- [ ] Fresh OrbStack deploy with `MFA_SECRET_KEY` set: admin enrolls, pod restart, admin still verified after refresh
- [ ] Docs: env row, recovery SQL, architecture update all present; dates/links checked
- [ ] README test counts match actual output

## Risk Assessment
- Testcontainers not available on CI → same constraint as today's 24 tests; tag `@Tag("integration")` like the others.
- Doc drift (RSA rows) → fixed in step 3 since it sits in the same table; nothing else in docs is touched beyond the feature.
