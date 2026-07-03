---
title: "Engly BE Test Suite + JaCoCo Coverage Gate"
description: "Full backend test audit, fill all coverage gaps (unit/slice/integration), wire JaCoCo 0.8.11 verify gate at LINE 65% / BRANCH 55%."
status: pending
priority: P2
effort: 17h
branch: main
tags: [testing, jacoco, coverage, spring-boot, backend]
created: 2026-06-13
---

# Engly BE Test Suite + JaCoCo Coverage Gate

Full-breadth test audit of `engly-be` (Spring Boot 3.4.5 / Java 21 / Maven) plus a JaCoCo
coverage gate enforced in `./mvnw verify`. Fill THIN/UNCOVERED gaps with layer-appropriate
tests: Mockito unit (services/domain), `@WebMvcTest` slice (controllers), `@SpringBootTest`
+ Testcontainers (end-to-end/repo). Verb module already strong — reinforce only real gaps.

## Inputs
- Scout gap map: `scout/scout-01-coverage-gap-map.md` (per-class cases + summary table)
- JaCoCo research: `research/researcher-01-jacoco-maven-coverage.md` (exact pom XML, excludes, thresholds)
- Spring test strategy: `research/researcher-02-spring-test-strategy.md` (decision matrix, @MockitoBean, jwt())
- Codebase docs: `/Users/admin/Desktop/DEV/AI/Engly/docs/codebase-summary.md`, `system-architecture.md`

## Key Decisions (locked)
- Scope: FULL audit, fill all gaps. BOTH test types, layer-appropriate.
- JaCoCo 0.8.11 in pom.xml: prepare-agent (initialize) + report + check (verify). BUNDLE scope.
- Initial threshold LINE 65% / BRANCH 55%; progressive raise to 75/65 later.
- Excludes: DTOs/records (`**/dto/**`, `*Request`, `*Response`), `@Configuration`, `EnglyApplication`.

## Phases
| # | Phase | Status | Effort | File |
|---|-------|--------|--------|------|
| 01 | Audit baseline + JaCoCo wiring + verify gate | ✅ done (baseline deferred*) | 2h | [phase-01](phase-01-jacoco-wiring-and-baseline.md) |
| 02 | Study module unit tests (Review/Queue/Deck/Settings/Clock) | ✅ done — 28 tests | 5h | [phase-02](phase-02-study-module-unit-tests.md) |
| 03 | Vocab admin unit tests (DeckAdmin, VocabWordAdmin) | ✅ done — 44 tests | 2.5h | [phase-03](phase-03-vocab-admin-unit-tests.md) |
| 04 | Identity + common (SessionController, GlobalExceptionHandler) | ✅ done — 18 tests | 3h | [phase-04](phase-04-identity-common-slice-tests.md) |
| 05 | Integration / RBAC reinforcement (thin spots) | ⏸ blocked — needs Docker | 3h | [phase-05](phase-05-integration-rbac-reinforcement.md) |
| 06 | Full verification + coverage report + docs | ⏸ blocked — needs Docker | 1.5h | [phase-06](phase-06-full-verification.md) |

\* JaCoCo plugin + `unit-only` profile + `@Tag("integration")` on 13 IT classes wired & working (163 unit tests green, Docker-free). Baseline capture + enforced gate (phase-06) require a full `./mvnw verify` with Docker up.

## Dependencies
- Phase 01 first (JaCoCo gate + baseline) — all later phases measured against it.
- Phases 02–04 independent of each other (parallelizable; different packages, no file conflicts).
- Phase 05 depends on 02–04 (reuses fixtures, fills residual gaps). Phase 06 last (gate sign-off).
- Docker (OrbStack) MUST be running for Testcontainers phases (01 verify, 02 partly, 05). See phase-01.

## Convention Notes
- New tests use Spring Boot 3.4 idiom `@MockitoBean` (not deprecated `@MockBean`). Existing
  tests still use `@MockBean` — do NOT mass-migrate (out of scope); only new files use new idiom.
- Mockito unit tests for study/vocab services follow existing `VerbQueryServiceTest` pattern
  (constructor injection, reflection helper for JPA entity fields).
- `Sm2Input`/`Sm2Result` are package-private in `com.engly.study.application` → unit tests that
  build them directly MUST live in that package.

## Validation Summary

**Validated:** 2026-06-13 · **Questions asked:** 4

### Confirmed Decisions
- **Gate strategy:** Measure-first. Phase-01 wires JaCoCo with `check` skipped (or threshold 0) to capture the real baseline %; phase-06 sets the enforced gate to baseline − small margin. No red build before tests exist. (Supersedes the hard 65/55-from-phase-01 reading.)
- **Docker dependency:** Tag-and-skip. Integration tests get `@Tag("integration")` + a Maven profile so unit tests run standalone when Docker/OrbStack is absent; CI provides Docker to run the full set. (CHANGE vs original plan — see Action Items.)
- **Mock annotation:** New files use `@MockitoBean`; existing `@MockBean` left untouched (migration = separate future task).
- **Async tests:** Reuse the proven `pollUntilTerminal()`/singleton-container polling pattern; no executor re-architecture.

### Action Items (apply during implementation — phase files NOT yet edited)
- [ ] **phase-01:** Add `@Tag("integration")` to Testcontainers/`@SpringBootTest` tests + a Surefire/Maven profile (e.g. `-DexcludedGroups=integration` default off, or a `unit-only` profile) to allow Docker-less unit runs. Set JaCoCo `check` to non-enforcing (skip/0) for baseline capture.
- [ ] **phase-04 (GlobalExceptionHandler standalone) / phase-02 / phase-03:** pure unit + `@WebMvcTest` files stay untagged → runnable without Docker.
- [ ] **phase-05:** tag all new integration tests `@Tag("integration")`.
- [ ] **phase-06:** compute baseline, set enforced gate = baseline − margin (override the 65/55 estimate if baseline differs); document `./mvnw verify` (full) vs unit-only profile.

## Unresolved Questions
1. Threshold feasibility: 65/55 BUNDLE is an estimate; if baseline (phase-01) already exceeds it,
   raise immediately to baseline+safety-margin rather than leaving a slack gate. Confirm in phase-06.
2. Should JaCoCo `check` run in CI only or also in a pre-commit hook? (Research Q3 — defer, CI-only.)
3. Per-package thresholds (services stricter than controllers) deferred to the 75/65 raise — keep
   BUNDLE-only now (KISS).
4. `@MockBean` deprecation: Spring Boot 3.4 warns; full migration is a separate cleanup task.
