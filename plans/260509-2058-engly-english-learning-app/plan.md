---
title: "Engly — Daily English Learning Platform"
description: "Spring Boot 3 + Next.js 15 + Postgres + Redis. SRS vocab, grammar quizzes, gamification."
status: pending
priority: P2
effort: ~80h
branch: main
tags: [spring-boot, nextjs, postgres, redis, srs, gamification, auth]
created: 2026-05-09
---

## Intro
Greenfield daily English learning web app. Backend: Spring Boot 3.4 + Postgres 16 + Redis 7. Frontend: Next.js 15 + Tailwind + shadcn/ui. Core loop: vocab SRS (SM-2) + grammar quizzes drive XP + streak + leaderboard. Email/password + Google/GitHub OAuth. Stateless JWT (15m access, 7d Redis-rotated refresh). Single Gradle module, package-by-feature. ~80h effort across 8 phases.

## Key Decisions
| Topic | Decision | Rationale |
|---|---|---|
| BE build | Gradle Kotlin DSL, single module | Type-safe DSL, faster than Maven, KISS for MVP |
| JWT signing | HS256 (MVP) → RS256 path documented | Single-secret simpler; RS256 swap is config-only |
| Refresh tokens | Redis-only, hashed, rotated, replay-detect | Native TTL, no Postgres bloat |
| FE session | NextAuth v5 JWT strategy, BE = source of truth | Auth.js handles OAuth UX; BE owns identity |
| SRS | SM-2 (FSRS deferred) | Battle-tested, deterministic, 4 fields |
| Streak | Lazy on-read w/ user IANA TZ + `@Version` lock | Zero infra; idempotent per calendar day |
| Leaderboard | Redis ZSET alltime + weekly w/ 8d TTL | O(log N) atomic ZINCRBY |
| Tests | Testcontainers + `@ServiceConnection`, reuse on | Real Postgres+Redis, fast iter |

## Phases
| # | File | Status | Effort |
|---|---|---|---|
| 1 | [phase-01-setup-environment.md](./phase-01-setup-environment.md) | pending | 6h |
| 2 | [phase-02-database-schema.md](./phase-02-database-schema.md) | pending | 8h |
| 3 | [phase-03-authentication.md](./phase-03-authentication.md) | pending | 16h |
| 4 | [phase-04-vocabulary-srs.md](./phase-04-vocabulary-srs.md) | pending | 12h |
| 5 | [phase-05-grammar-quizzes.md](./phase-05-grammar-quizzes.md) | pending | 10h |
| 6 | [phase-06-gamification.md](./phase-06-gamification.md) | pending | 8h |
| 7 | [phase-07-profile-dashboard.md](./phase-07-profile-dashboard.md) | pending | 8h |
| 8 | [phase-08-testing-hardening-docs.md](./phase-08-testing-hardening-docs.md) | pending | 12h |

## Dependency Graph
```
P1 (env) ──> P2 (schema) ──> P3 (auth) ──┬─> P4 (vocab) ──┐
                                          ├─> P5 (grammar) ┼─> P6 (gamif) ─> P7 (profile) ─> P8 (test+docs)
                                          └────────────────┘
```
P4/P5 parallelizable after P3. P6 depends on both. P7 reads from all. P8 finalizes.

## Decisions Log
See [decisions-log.md](./decisions-log.md) for trade-off rationale.

## How to Run (target state after P1)
`docker compose up -d` (postgres+redis+mailhog) → `cd backend && ./gradlew bootRun` (port 8080) → `cd frontend && pnpm dev` (port 3000). Swagger UI at `/swagger-ui.html`. Seed data auto-applied via Flyway in dev profile.
