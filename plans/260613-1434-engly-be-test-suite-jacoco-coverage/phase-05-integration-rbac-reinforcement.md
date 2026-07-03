# Phase 05 — Integration / RBAC Reinforcement

## Context Links
- Scout: `scout/scout-01-coverage-gap-map.md` §§3–6 (study e2e gaps), §§8,9 (vocab admin edge), §STEP 4 (RBAC + exception table)
- Test strategy: `research/researcher-02-spring-test-strategy.md` §3 (jwt()/@WithMockUser 401 vs 403), §6 (async/Awaitility)
- Reusable infra: `engly-be/src/test/java/com/engly/vocab/imports/AbstractVocabImportIntegrationTest.java`, `VocabImportCsvFixture.java`
- Existing e2e: `engly-be/src/test/java/com/engly/study/StudyApiIntegrationTest.java`, `vocab/VocabAdminIntegrationTest.java`, `verb/Verb*IntegrationTest.java`

## Overview
- **Date:** 2026-06-13
- **Description:** Fill residual THIN integration spots that unit tests cannot reach (real Flyway schema, real SRS state machine across HTTP, RBAC boundary on `/api/admin/**`). Reuse the singleton-container + `admin()` JWT + `oidcLogin()` patterns. Add only REAL gaps — do not duplicate existing happy-path e2e or verb (already strong).
- **Priority:** P2
- **Implementation status:** pending
- **Review status:** pending

## Key Insights
- **Reuse, do not re-establish, container config.** `AbstractVocabImportIntegrationTest` uses a singleton container started in a static block + `@DynamicPropertySource` (NOT `@ServiceConnection`, NOT `@Container`) — deliberate, to keep the cached `@SpringBootTest` context valid across classes. New study/vocab integration tests SHOULD follow `StudyApiIntegrationTest`'s `@Container`+`@DynamicPropertySource` style OR extend the abstract base — match whichever the target module already uses. Do NOT introduce `@ServiceConnection` (would diverge from working OrbStack setup).
- Study integration uses a `@TestConfiguration FixedClockConfig` `@Primary StudyClock` for deterministic dates — reuse this when asserting concrete `dueOn` values.
- RBAC: `/api/admin/**` = Chain 1 (bearer JWT). 401 = no token (`jwt()` absent), 403 = `jwt()` without `ROLE_ADMIN`, 200 = `jwt().authorities(ROLE_ADMIN)`. `/api/study/**` + `/api/me`,`/api/token` = Chain 2 (session) → `oidcLogin()`; anonymous → 401.
- Async import processor flakiness: existing import ITs poll via `pollUntilTerminal()`. If ANY new async assertion is added, reuse that helper or Awaitility — do NOT `Thread.sleep` a fixed time and assert. Prefer not adding new async coverage (import module already COVERED per scout).
- Many study/vocab e2e edges are ALREADY covered cheaply by phases 02/03 unit tests. Per YAGNI, phase-05 adds integration ONLY where the real schema/HTTP/RBAC layer is the thing under test.

## Requirements
**Functional** — close the specific residual gaps listed below; every test passes against real Postgres.
**Non-functional** — reuse existing base classes/fixtures; files < 200 LOC; no new container-config divergence.

## Architecture
`@SpringBootTest @AutoConfigureMockMvc` (+ `@Testcontainers`/static container per module convention) + MockMvc + `admin()`/`oidcLogin()` postprocessors + `FixedClockConfig` where dates asserted.

## Related Code Files
**Create** (only if a real gap remains after phases 02–04):
| Test file | Target / gap |
|-----------|--------------|
| `src/test/java/com/engly/study/StudyReviewStateMachineIntegrationTest.java` | HARD/EASY rating paths + reps-driven interval across real HTTP+DB (scout §3 gaps not in `StudyApiIntegrationTest`) |
| `src/test/java/com/engly/vocab/VocabAdminRbacIntegrationTest.java` | `/api/admin/decks`,`/api/admin/vocab-words` 401-no-token / 403-non-admin / 200-admin (scout §STEP 4) |

**Modify** (preferred over new files where the class already exists — DRY):
- `engly-be/src/test/java/com/engly/study/StudyApiIntegrationTest.java` — ADD HARD/EASY review cases + queue exhausted-allowance case IF not better placed as unit (phase-02). Prefer modify over new file.
- `engly-be/src/test/java/com/engly/vocab/VocabAdminIntegrationTest.java` — ADD deck-cascade-delete + word deckId-reassign edge IF integration-only.

**Delete** — none.

## Implementation Steps
1. **Decide modify-vs-create per gap** (KISS): if the gap is pure logic → it belongs in phases 02/03 (unit), skip here. Only keep integration tests where real DB/HTTP/RBAC is essential.
2. **Study state-machine (integration-essential):** HARD and EASY ratings produce schema-persisted `ease_factor`/`interval_days`/`due_on` — assert via real Postgres + fixed clock. Reuse `oidcLogin()` + `FixedClockConfig`. Cover: new→GOOD→GOOD chain growing interval; HARD path; EASY path; reps progression. Add to `StudyApiIntegrationTest` (modify) unless it exceeds 200 LOC → then new file.
3. **Vocab admin RBAC (integration-essential):** for both admin controllers — no token → 401; `jwt()` non-admin → 403; `jwt().authorities(ROLE_ADMIN)` → 200/201. Mirror `AdminSecurityRulesTest` but against the real context (resource-server chain). Keep focused; this is the canonical 401-vs-403 proof for vocab admin.
4. **Cascade/reassign edges** (integration-only): delete deck → child words gone (DB `ON DELETE CASCADE`); update word `deckId` → reassigned. Add to `VocabAdminIntegrationTest`.
5. **Verb:** verb module already strong — add NOTHING unless phase-01 baseline reveals a concrete uncovered branch in `VerbController`/`VerbAdminController` error paths. If so, add one focused case to the existing `Verb*ControllerTest`.
6. **No new async tests** unless a real gap; if added, use `pollUntilTerminal()` / Awaitility (never fixed sleep).
7. Run: `cd engly-be && ./mvnw test -Dtest='*IntegrationTest,*RbacIntegrationTest'` (Docker required).

## Todo List
- [ ] Triage each residual gap: unit (skip here) vs integration (keep)
- [ ] Study HARD/EASY + interval-progression (modify `StudyApiIntegrationTest` or new state-machine file)
- [ ] Vocab admin RBAC 401/403/200 (both controllers)
- [ ] Deck cascade-delete + word deckId-reassign edges
- [ ] Verb: only if phase-01 shows a real uncovered branch
- [ ] Reuse container base/fixtures; no `@ServiceConnection`; no fixed-sleep async
- [ ] All compile + pass against real Postgres

## Success Criteria
- New/expanded integration tests pass via `./mvnw test` with Docker running.
- `/api/admin/**` 401-vs-403-vs-200 explicitly proven for vocab admin.
- Study SRS HARD/EASY paths covered end-to-end; controllers (study/vocab) no longer THIN in JaCoCo.
- No duplication of logic already unit-tested in phases 02/03 (DRY).

## Risk Assessment
- **Docker not running** → entire phase fails (hard prerequisite, same as phase-01). Mitigation: `docker ps` precheck.
- **Container-config divergence** (someone adds `@ServiceConnection`) → cached-context port mismatch / Hikari timeouts. Mitigation: reuse existing base classes verbatim.
- **Async flakiness** if new async assertions added. Mitigation: `pollUntilTerminal()`/Awaitility, no fixed sleep; prefer not adding async (module already covered).
- **Cross-test interference** on shared container (study tests use unique `userSub` per test). Mitigation: follow existing unique-user convention.
- **Over-building** integration where unit suffices. Mitigation: triage step 1 enforces YAGNI.

## Security Considerations
- RBAC tests are the security core of this phase: prove anonymous → 401, authenticated-non-admin → 403, admin → 200 on `/api/admin/**`; session endpoints reject anonymous. No bypass via wrong chain.

## Next Steps
- Last content phase. Phase-06 runs the full gate green and documents the run.
