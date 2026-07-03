# Phase 06 — Full Verification + Coverage Report + Docs

## Context Links
- JaCoCo run/report: `research/researcher-01-jacoco-maven-coverage.md` §6
- All phase files (01–05) for the test inventory delivered.
- Docs to update: `/Users/admin/Desktop/DEV/AI/Engly/docs/project-changelog.md`, `code-standards.md` (testing section)

## Overview
- **Date:** 2026-06-13
- **Description:** Run the full `./mvnw clean verify` gate green, confirm all new + existing tests pass, coverage thresholds met (LINE ≥ 65% / BRANCH ≥ 55% BUNDLE), capture the final HTML report, and document how to run tests + coverage. Decide whether to raise the gate to baseline+margin.
- **Priority:** P2
- **Implementation status:** pending
- **Review status:** pending

## Key Insights
- Gate is enforced in `verify` (phase-01). This phase is the green sign-off: everything compiles, all tests pass, threshold met.
- If post-phases coverage comfortably exceeds 65/55, raise the `check` minimums to (baseline − small margin) so the gate has teeth — avoid a permanently-slack gate (addresses plan unresolved-Q1). Do NOT overshoot to 75/65 yet (that's the deferred progressive step).
- Docker (OrbStack) required — Testcontainers ITs run in `test` before the gate. `docker ps` first.

## Requirements
**Functional**
- `./mvnw clean verify` exits 0: all tests pass AND coverage gate passes.
- `target/site/jacoco/index.html` reflects new coverage; THIN/UNCOVERED classes from scout now green.

**Non-functional**
- Run reproducible; documented commands. No flaky failures across 2 consecutive runs.

## Architecture
Full Maven lifecycle: `clean` → `initialize` (agent) → `test` (unit + slice + Testcontainers ITs, coverage recorded) → `verify` (report + check gate).

## Related Code Files
**Modify**
- `engly-be/pom.xml` — optionally tighten `check` minimums to baseline+margin (if comfortably exceeded).
- `/Users/admin/Desktop/DEV/AI/Engly/docs/project-changelog.md` — add entry: test suite expansion + JaCoCo gate.
- `/Users/admin/Desktop/DEV/AI/Engly/docs/code-standards.md` — add "Testing & Coverage" subsection (how to run, layer conventions, @MockitoBean, excludes, threshold).

**Create** — none (docs updates only).

## Implementation Steps
1. `docker ps` — confirm OrbStack running.
2. `cd engly-be && ./mvnw clean verify` — full run. Fix any compile/test failures (delegate to `tester`/`debugger` per workflow); never disable a test to pass.
3. Open `target/site/jacoco/index.html`; confirm:
   - BUNDLE LINE ≥ 65%, BRANCH ≥ 55% (after excludes).
   - `study.application`, `vocab.admin`, `identity.api`, `common.GlobalExceptionHandler` materially up vs phase-01 baseline.
4. Run `./mvnw verify` a SECOND time to confirm no flakiness (esp. Testcontainers/async).
5. If coverage ≫ 65/55, raise `check` minimums to baseline − ~3% margin; re-run to confirm still green.
6. Document in `docs/code-standards.md`:
   - Run unit only: `./mvnw test`; full + coverage gate: `./mvnw verify`; report at `target/site/jacoco/index.html`.
   - Prerequisite: Docker/OrbStack for Testcontainers ITs.
   - Conventions: `@MockitoBean` (new), Mockito unit for services, `@WebMvcTest` slice for controllers, `@SpringBootTest`+Testcontainers for e2e, excludes (DTOs/config/EnglyApplication), threshold + progressive-raise roadmap (75/65 → 85/75).
7. Add changelog entry (date, summary, files added).
8. Record final coverage % + whether gate was raised in this file's Success Criteria / plan unresolved-Q1.

## Todo List
- [ ] `docker ps` ok
- [ ] `./mvnw clean verify` exits 0 (all tests + gate)
- [ ] HTML report confirms gap classes now covered
- [ ] Second run confirms no flakiness
- [ ] Optionally raise gate to baseline+margin; re-verify green
- [ ] Update `docs/code-standards.md` (testing & coverage section)
- [ ] Update `docs/project-changelog.md`
- [ ] Record final coverage numbers

## Success Criteria
- `./mvnw clean verify` green twice consecutively.
- BUNDLE LINE ≥ 65%, BRANCH ≥ 55% (or raised baseline) enforced and met.
- All scout HIGH-priority gaps (SessionController, GlobalExceptionHandler, study services) now covered.
- Docs updated; run instructions reproducible.

## Risk Assessment
- **Flaky Testcontainers/async** → intermittent gate failure. Mitigation: two consecutive runs; ensure async uses polling/Awaitility (phase-05); pin postgres image tags already in tests.
- **Coverage just below threshold** after phases → identify the specific red lines in HTML and add 1–2 targeted cases to the owning phase (don't lower the gate to pass).
- **Docs drift.** Mitigation: delegate to `docs-manager` per workflow.

## Security Considerations
- Confirm no secrets/credentials committed in any test fixture or report. RBAC + no-leak handler behavior validated in phases 04/05 remain green.

## Next Steps
- Future (out of scope): progressive raise to LINE 75% / BRANCH 65%; optional per-package rules; optional `@MockBean`→`@MockitoBean` migration of legacy tests; optional pre-commit JaCoCo check.
