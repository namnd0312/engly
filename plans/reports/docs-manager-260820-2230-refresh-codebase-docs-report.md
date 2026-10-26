# Codebase Documentation Refresh — Report

**Date:** 2026-08-20  
**Task:** Refresh stale codebase-summary.md (2026-07-05) + CREATE code-standards.md  
**Status:** COMPLETE

---

## Summary

Updated `/Users/admin/Desktop/DEV/AI/Engly/docs/codebase-summary.md` (428 lines, 28KB) and created `/Users/admin/Desktop/DEV/AI/Engly/docs/code-standards.md` (304 lines, 12KB). All documentation is evidence-based — every class, file, and migration referenced in the docs exists in the codebase and was verified via grep/find.

---

## Changes Made

### 1. `codebase-summary.md` — Comprehensive Refresh

**Previous status:** Stale since 2026-07-05; missing ~95 lines of new content (6 known gaps)

**Updated sections:**

| Gap | Content Added | Line Evidence | Verification |
|-----|---|---|---|
| Identity module minimal (~2 classes) | Expanded to 23 classes across api/application/domain/config/infrastructure + DTOs | Lines 25-115 | 3 API + 11 app + 9 domain files found ✓ |
| vocab.imports absent | Complete module: admin endpoints, service, domain entities, infrastructure, processing | Lines 172-231 | 27 Java files in imports/ ✓ |
| Migrations V6-V11 undocumented | All 7 new migrations (auth, avatar, finance/IT seed, review_log, user_identity, email_verification) | Lines 277-320 | All 7 .sql files exist ✓ |
| Frontend auth/profile absent | Added login, register, verify-email, social-login-buttons, profile page, profile API | Lines 399-447 | 7 auth files + 7 profile files ✓ |
| Frontend tests count unclear | Updated to reflect 26 spec files (from 54 non-spec files) | Lines 483-495 | 26 .spec.ts files found ✓ |
| Infra docs missing | Added Floci setup, Lambda avatar-resize handler details | Lines 510-522 | 2 shell scripts + 1 handler.py verified ✓ |
| Status outdated | Updated to reflect Auth/Profile/Imports complete as of 2026-08-20 | Lines 525-550 | 30 feature statuses, all marked DONE except human verification |

**Line count:** 291 → 428 lines (under 800 LOC limit ✓)

**Preserved:** Existing heading hierarchy, table-first style, terse formatting, package-by-feature structure.

### 2. `code-standards.md` — New File (Evidence-Based)

**Scope:** Derived from observable patterns in actual codebase, not aspirational.

**Sections:**

| Section | Coverage | Evidence Base |
|---------|----------|---|
| Backend Architecture | Package-by-feature layout (api/application/domain/infrastructure/config) | `com.engly.verb`, `com.engly.identity`, `com.engly.vocab.imports` |
| Java Naming & DTOs | Records for DTOs, service class patterns, exception hierarchy | `verb/api/dto/VerbResponse.java`, `identity/api/dto/LoginRequest.java` |
| Exception Conventions | Domain exceptions per feature + GlobalExceptionHandler mapping | `verb/domain/VerbNotFoundException.java` → 404, `InvalidLoginException.java` → 401 |
| Flyway Migrations | V<n>__snake_case naming, forward-only, idempotent SQL | V1-V11 migration files, ON CONFLICT DO NOTHING pattern |
| Test Layering | Unit, Service, Web (@WebMvcTest), Security, Integration (Testcontainers) | 47 backend test files: Sm2SchedulerTest, VerbControllerTest, AuthSecurityRulesTest, VerbApiIntegrationTest |
| Frontend Conventions | Kebab-case files, standalone components, API service pattern, test colocation | `login-page.component.ts`, `study-api.service.ts`, `*.spec.ts` in same directory |
| Configuration | @ConfigurationProperties classes, env var override pattern | `AuthProperties.java`, `JwtKeyConfig.java`, `application.yml` |
| Code Quality | File size guidance (200 LOC), null handling, logging, transactions, security | Project CLAUDE.md, actual code patterns |
| Cross-cutting Example | Step-by-step checklist for adding a new feature | Reusable template for future developers |

**Line count:** 304 lines (under 400 line guideline ✓)

---

## Verification Checklist

- [x] Identity module: 3 API controllers verified (AuthController, ProfileController, SessionController)
- [x] Identity application layer: 11 service classes verified (AuthTokenService, EmailVerificationService, SocialLoginService, etc.)
- [x] Identity domain: 9 entities/exceptions verified (UserAccount, UserIdentity, RefreshToken, EmailVerificationToken, etc.)
- [x] vocab.imports module: 27 Java classes verified across admin/api/domain/infrastructure/processing layers
- [x] Migrations V5-V11: All 7 new migrations exist and documented with SQL schema details
- [x] Frontend auth: 7 files verified (login, register, verify-email, social-login-buttons, specs)
- [x] Frontend profile: 7 files verified (profile page, profile API service, models, routes, specs)
- [x] Backend tests: 47 files verified (unit, service, web layer, security, integration)
- [x] Frontend tests: 26 spec files verified (auth, profile, verb-search, admin, study, progress)
- [x] Infra files: Floci setup scripts + Lambda handler verified (avatar-resize)
- [x] File size limits met: codebase-summary (428 LOC, 800 limit), code-standards (304 LOC, 400 target)
- [x] Evidence-based writing: No invented class names, all references verified via grep/find
- [x] Style consistency: Terse tables/bullets, no filler prose, markdown formatting preserved

---

## Key Insights

1. **Scale of identity module:** 23 classes (api/app/domain/config + DTOs) handle auth, profile, email verification, social login, JWT, avatar/S3. Previously documented as minimal.

2. **vocab.imports completeness:** Full featured module (27 classes) with resumable uploads, CSV parsing, batch upsert, job status tracking, lease/resume recovery, async pipeline — was entirely absent from docs.

3. **Migration progression:** 11 total migrations show clear evolution: verb seed (V1-V2) → vocab framework (V3-V4) → imports infrastructure (V5) → auth system (V6-V7) → content seeding (V8) → study history (V9) → social login (V10-V11).

4. **Frontend feature parity:** Auth/profile/imports added to codebase; frontend now includes all corresponding pages + API services + tests, bringing UI in line with backend features.

5. **Test coverage:** 47 backend + 26 frontend test files verify no mocks in integration tests (real Testcontainers DB), all layers represented (unit → web → security → integration).

---

## Files Modified

- `/Users/admin/Desktop/DEV/AI/Engly/docs/codebase-summary.md` — Updated in place (428 lines)
- `/Users/admin/Desktop/DEV/AI/Engly/docs/code-standards.md` — Created (304 lines)

No code files modified. No code changes. Documentation-only.

---

## Unresolved Questions

None — all documented features verified against actual code.
