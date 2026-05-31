# Phase 02 — Database Schema + Verb Seed Data

## Context Links
- [plan.md](plan.md)
- [Phase 01](phase-01-setup-monorepo-and-skeleton.md)
- [Brainstorm summary](../reports/brainstorm-summary-260530-2323-engly-irregular-verbs-ddd-sso.md)

## Overview
- **Date:** 2026-05-30
- **Priority:** P1 (blocks API)
- **Effort:** 8h (mostly data curation + human verification)
- **Implementation status:** complete (verified=false pending human sign-off)
- **Review status:** not reviewed
- **Description:** Flyway schema for `irregular_verb` + seed ~180 common irregular verbs. IPA fields + Vietnamese meaning AI-GENERATED (ai-multimodal/LLM) then HUMAN-VERIFIED before insertion. Alternate forms handled (learnt/learned).

## Key Insights
- Data correctness is the main effort/risk, not the SQL. AI generates draft → human verifies → only then seed migration written.
- Alternate forms: keep simple — comma-separated alternates within form columns OR dedicated `*_alt` columns. Decision below: comma-separated within the primary column is over-loaded for search; use nullable `past_simple_alt` / `past_participle_alt` columns (KISS, explicit, searchable). No separate table (YAGNI for MVP).
- Search needs case-insensitive match on base_form + meaning_vi → add functional/lower index later (phase 03 covers query); add basic indexes here.

## Requirements
**Functional**
- Schema stores: id, base_form, past_simple, past_participle, ipa_base, ipa_past, ipa_pp, meaning_vi, optional alternates, verified flag, timestamps.
- ~180 rows seeded, each human-verified (verified=true).
**Non-functional**
- Idempotent, versioned Flyway migrations. UTF-8 (IPA + Vietnamese diacritics). Unique base_form.

## Architecture
**Tables / columns** (`irregular_verb`):
| col | type | notes |
|-----|------|-------|
| id | bigint GENERATED ALWAYS AS IDENTITY PK | |
| base_form | varchar(64) NOT NULL UNIQUE | e.g. "go" |
| past_simple | varchar(64) NOT NULL | "went" |
| past_participle | varchar(64) NOT NULL | "gone" |
| past_simple_alt | varchar(64) NULL | "learned" |
| past_participle_alt | varchar(64) NULL | "learned" |
| ipa_base | varchar(96) NULL | "/ɡoʊ/" |
| ipa_past | varchar(96) NULL | |
| ipa_pp | varchar(96) NULL | |
| meaning_vi | varchar(255) NOT NULL | "đi" |
| verified | boolean NOT NULL DEFAULT false | true only after human check |
| created_at | timestamptz NOT NULL DEFAULT now() | |

**Indexes:** unique(base_form); index lower(base_form); index lower(meaning_vi) (or trigram later if needed — YAGNI now, btree on lower() suffices for prefix/equality).

**Migrations (Flyway, `backend/src/main/resources/db/migration/`):**
- `V1__create_irregular_verb_table.sql`
- `V2__seed_irregular_verbs.sql` (only after verification)

## Related Code Files
**Create**
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/resources/db/migration/V1__create_irregular_verb_table.sql`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/resources/db/migration/V2__seed_irregular_verbs.sql`
- `/Users/admin/Desktop/DEV/AI/Engly/plans/260530-2323-engly-irregular-verbs-mvp-sso/reports/verb-data-verification-checklist.md` (verification record — created during this phase)
**Modify / Delete:** none

## Implementation Steps
1. Write `V1` DDL per table spec above (IDENTITY PK, unique base_form, indexes on lower(base_form)/lower(meaning_vi)).
2. Compile list of ~180 common irregular verbs (base/past/pp) from public knowledge — this part is reliable.
3. Use `ai-multimodal` skill / LLM to generate `ipa_base, ipa_past, ipa_pp` (IPA) + `meaning_vi` + alternates for each verb. Output to a draft CSV/markdown table.
4. HUMAN VERIFICATION: reviewer checks IPA accuracy + Vietnamese meaning + alternate forms. Mark each row verified. Record pass in verification checklist report.
5. Generate `V2__seed_irregular_verbs.sql` INSERTs ONLY from verified rows; set verified=true. Escape apostrophes; ensure UTF-8.
6. Run `./gradlew flywayMigrate` (or bootRun) against Postgres :5433; confirm 180 rows, no migration errors.
7. Spot-check: `SELECT * FROM irregular_verb WHERE base_form='go';` → go/went/gone + IPA + "đi".

## Todo List
- [x] V1 DDL migration (table + indexes + unique)
- [x] Compile ~180 base/past/pp list
- [x] AI-generate IPA + meaning_vi + alternates (draft)
- [ ] Human-verify all rows; record in checklist report  ← PENDING human sign-off
- [x] V2 seed migration (verified=false per user decision override)
- [x] flywayMigrate succeeds; row count = 179 (draft had 179 actual data rows)
- [x] Spot-check sample verbs (go, learn, read, eat) — IPA + VI diacritics confirmed

## Success Criteria
- Flyway V1+V2 apply cleanly on fresh DB.
- ~180 verified rows; UTF-8 intact (IPA symbols + VI diacritics render).
- Alternate forms present where applicable (learn→learnt/learned).
- Every seeded row verified=true.

## Risk Assessment
- AI hallucinated IPA / wrong VI meaning → MUST human-verify; gate seed on verification. *Medium.*
- UTF-8 encoding loss in SQL file → ensure file saved UTF-8, DB encoding UTF8. *Low.*
- Alternate-form modeling regret → chose explicit `_alt` columns (reversible via later migration). *Low.*

## Security Considerations
- Seed data is non-sensitive public reference; no PII.
- SQL injection N/A (static seed) but escape quotes to keep migration valid.

## Next Steps
- Phase 03 queries this schema (search base_form/meaning_vi, detail by base_form).
- Future: trigram/GIN index if fuzzy search needed (deferred, YAGNI).
