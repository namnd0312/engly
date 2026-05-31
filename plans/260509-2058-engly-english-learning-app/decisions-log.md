# Decisions Log — Engly

Each entry: decision + 1-line rationale. Updated as plan evolves.

## Build & Project Layout
- **Gradle Kotlin DSL vs Maven** → Gradle Kotlin DSL. Faster incremental builds, type-safe DSL, modern Spring Boot ecosystem default.
- **Single-module vs multi-module** → Single module, package-by-feature. KISS for MVP; revisit at >3 BE devs or service split.
- **Lombok + MapStruct** → Both. Lombok cuts boilerplate; MapStruct compile-time entity↔DTO mapping is fast + safe.

## Authentication & Tokens
- **HS256 vs RS256 JWT** → HS256 for MVP (single shared secret in env), RS256 documented as upgrade path. RS256 needs JWKS endpoint; not justified pre-launch. Swap is config-only.
- **Refresh token store: Postgres vs Redis** → Redis-only. Native TTL (7d), atomic rotation via `SET key value EX 604800 XX`, key naming `refresh:{userId}:{jti}` → SHA-256 of token. Replay-detection: presented refresh JTI not found → revoke all user refresh tokens.
- **Email-verify / password-reset tokens** → Short-lived JWT w/ `purpose` claim (15m verify, 30m reset). Used-jti list in Redis blacklist (TTL = token expiry). No DB column needed.
- **NextAuth session strategy** → JWT (Auth.js v5 default). BE-issued access+refresh embedded in NextAuth JWT cookie. NextAuth `jwt()` callback refreshes on expiry.
- **NextAuth v5 (Auth.js) vs custom auth** → Auth.js for OAuth UX + Credentials provider; BE remains identity source of truth. Avoids reinventing OAuth code-exchange.

## Algorithms & Data
- **SM-2 vs FSRS** → SM-2 for MVP. Deterministic, 4 fields, no ML training. FSRS deferred (Anki 23.10+ only ~2 years mature, more state).
- **Streak: cron job vs lazy on-read** → Lazy on-read. Zero infra, idempotent per calendar day via `LocalDate.now(userZone)`, `@Version` optimistic lock prevents double-increment.
- **Single Redis DB index vs separate** → Single DB index 0 for MVP. Document key prefixes: `refresh:*`, `leaderboard:*`, `bucket:*`, `revoked:jti:*`, `idempotency:*`. Separate index when memory pressure or eviction policy diverges.
- **Leaderboard: SQL vs Redis ZSET** → Redis ZSET. ZINCRBY O(log N) atomic, ZREVRANGE for top-N + neighbors. SQL window functions slow at scale.

## Frontend
- **RSC vs client components for dashboard** → Mostly client (TanStack Query handles cache + revalidation cleanly for interactive widgets). RSC for marketing/static pages (landing, terms).
- **Zustand inclusion** → Defer. TanStack Query covers server state; React `useState`/`useReducer` covers local. Add Zustand only if global client state needed (e.g., onboarding wizard).
- **Heatmap library** → `react-calendar-heatmap` (small, MIT, ~10kb). Custom SVG only if styling constraints force it.
- **Form validation** → react-hook-form + Zod. Schema shared client+server (Zod parses BE error responses into form-field errors).

## Infrastructure
- **Docker compose services** → postgres:16-alpine, redis:7-alpine, mailhog (dev SMTP). Production stack out of scope; deployment-guide phase 8 documents target (Cloud Run / Fly / Railway).
- **CI** → GitHub Actions: matrix `backend` (`./gradlew check`) + `frontend` (`pnpm lint && pnpm build`). No deploy stage in MVP.
- **Logging** → Logback w/ JSON encoder (logstash-logback-encoder). Stdout only; aggregation deferred.
- **Metrics** → Spring Actuator `health`, `info`, `metrics` (no Prometheus exporter in MVP).

## Testing
- **Testcontainers + `@ServiceConnection`** → Yes. Auto-wires JdbcConnectionDetails / RedisConnectionDetails. Static `@Container` + reuse-on for fast dev iter.
- **`@DataJpaTest` vs `@SpringBootTest`** → Both. Repo tests use `@DataJpaTest` (faster, no Service/Controller bean graph). Service+API tests use `@SpringBootTest`.
- **Coverage target** → ~70% on service layer (business logic). Lower bar on controllers (covered by integration), entities (data classes), config.
- **E2E** → Playwright, golden path only (signup → review → quiz → leaderboard).

## Open Trade-offs (revisit post-MVP)
- FSRS pilot timing.
- Leaderboard sharding threshold (>10k writes/sec).
- Refresh-token Postgres mirror for audit (currently Redis-only loses history).
- Multi-module split when team grows.
