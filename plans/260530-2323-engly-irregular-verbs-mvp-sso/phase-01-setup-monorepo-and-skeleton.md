# Phase 01 — Setup Monorepo + Skeletons

## Context Links
- [plan.md](plan.md)
- [Brainstorm summary](../reports/brainstorm-summary-260530-2323-engly-irregular-verbs-ddd-sso.md)
- IdP (reference only): `/Users/admin/Desktop/DEV/BACK_END/OPEN-GATE-APP`

## Overview
- **Date:** 2026-05-30
- **Priority:** P1 (blocks all)
- **Effort:** 6h
- **Implementation status:** completed
- **Review status:** not reviewed
- **Description:** Scaffold greenfield monorepo: Spring Boot 3.4.x backend (package-by-feature: verb/identity/common), Angular 19 standalone frontend, docker-compose Postgres, root README. No business logic yet — compilable skeletons + run scripts.

## Key Insights
- Greenfield: no existing code/docs. Create `docs/` placeholder structure now (filled in phase 06).
- Verb module FLAT; over-engineering forbidden. Layout chosen so new bounded contexts add without touching verb/.
- Same-origin dev (Angular proxy) decided here to dodge CORS/CSRF later.

## Requirements
**Functional**
- Backend boots on :8081, health endpoint reachable.
- Frontend boots on :4200 with proxy `/api` → :8081.
- `docker compose up` starts Postgres on host :5433 (Engly DB).
**Non-functional**
- Gradle Kotlin DSL. Java 21 toolchain. Files < 200 lines. kebab-case where applicable (Java classes keep PascalCase per language norm; config/yaml/ts kebab-case).

## Architecture
```
engly/
├── backend/
│   ├── build.gradle.kts, settings.gradle.kts, gradle/ (wrapper)
│   └── src/main/java/com/engly/
│       ├── EnglyApplication.java
│       ├── verb/        { api/ application/ domain/ infrastructure/ }   (empty pkgs + .gitkeep)
│       ├── identity/    { api/ config/ }
│       └── common/      (SecurityConfig stub: permitAll for now)
│   └── src/main/resources/application.yml (port 8081, datasource :5433, flyway on)
├── frontend/  (Angular 19 workspace, standalone, proxy.conf.json)
├── docker-compose.yml  (postgres:16, 5433:5432, volume)
├── docs/   (placeholder .md files)
├── README.md
└── .gitignore
```
- Dependencies (backend): spring-boot-starter-web, -data-jpa, -validation, flyway-core, flyway-database-postgresql, postgresql driver, (oauth2-client added phase 05). Lombok optional.
- SecurityConfig stub: permitAll everything in phase 01 (real rules in phase 05).

## Related Code Files
**Create**
- `/Users/admin/Desktop/DEV/AI/Engly/backend/build.gradle.kts`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/settings.gradle.kts`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/EnglyApplication.java`
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/java/com/engly/common/security-config.java` (class `SecurityConfig`)
- `/Users/admin/Desktop/DEV/AI/Engly/backend/src/main/resources/application.yml`
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/` (ng new workspace)
- `/Users/admin/Desktop/DEV/AI/Engly/frontend/proxy.conf.json`
- `/Users/admin/Desktop/DEV/AI/Engly/docker-compose.yml`
- `/Users/admin/Desktop/DEV/AI/Engly/README.md`
- `/Users/admin/Desktop/DEV/AI/Engly/.gitignore`
- `/Users/admin/Desktop/DEV/AI/Engly/docs/{system-architecture,codebase-summary,deployment-guide}.md` (placeholders)
**Modify / Delete:** none

## Implementation Steps
1. Init git repo at project root (currently NOT a repo) — `git init`; add `.gitignore` (gradle build/, node_modules/, .env, dist/, target/).
2. Backend: generate Gradle Kotlin DSL skeleton (Spring Initializr or manual). Set Java 21 toolchain, Spring Boot 3.4.x. Add deps above (defer oauth2-client).
3. Create package dirs verb/{api,application,domain,infrastructure}, identity/{api,config}, common/ with `.gitkeep`.
4. `EnglyApplication.java` with `@SpringBootApplication`.
5. `SecurityConfig` stub: `SecurityFilterChain` permitAll + CSRF disabled TEMPORARILY (note: re-enabled in phase 05).
6. `application.yml`: server.port 8081; datasource url `jdbc:postgresql://localhost:5433/engly`, user/pass via env with dev defaults; jpa ddl-auto `validate`; flyway enabled.
7. Frontend: `ng new` Angular 19, standalone, routing, no SSR. Add `proxy.conf.json` mapping `/api` → `http://localhost:8081`. Wire proxy into `ng serve` (angular.json serve options or `--proxy-config`).
8. `docker-compose.yml`: postgres:16-alpine, ports 5433:5432, POSTGRES_DB=engly, named volume.
9. `README.md`: prerequisites, how to run (docker compose up; backend `./gradlew bootRun`; frontend `npm start`), port table.
10. Verify: `./gradlew build` compiles; `ng build` succeeds; `docker compose up -d` + `psql -h localhost -p 5433 -U postgres -d engly -c '\l'`.

## Todo List
- [x] git init + .gitignore
- [x] Backend Gradle Kotlin DSL skeleton (Boot 3.4.5, Java 21)
- [x] Package-by-feature dirs (verb/identity/common)
- [x] EnglyApplication + SecurityConfig stub
- [x] application.yml (8081, datasource 5433, flyway)
- [x] Angular 19 workspace + proxy.conf.json
- [x] docker-compose.yml (Postgres 5433)
- [x] README + docs/ placeholders
- [x] Verify backend build + ng build + Postgres reachable

## Success Criteria
- `./gradlew build` green; backend boots :8081 (Flyway runs with zero migrations OK).
- `npm start` serves :4200; proxy forwards `/api/*` to :8081.
- Postgres reachable on :5433 via docker compose.
- Repo tree matches monorepo layout; all packages present.

## Risk Assessment
- Boot 3.4.x + Flyway-postgresql artifact mismatch → pin `flyway-database-postgresql`. *Low.*
- Angular 19 proxy not picked up by `ng serve` → set in angular.json serve.options.proxyConfig. *Low.*
- Port 5433 collision with another local PG → make port configurable via env. *Low.*

## Security Considerations
- CSRF disabled ONLY transiently in skeleton — MUST be re-enabled (CookieCsrfTokenRepository) in phase 05; leave a `// TODO phase-05` marker.
- No secrets committed; datasource creds via env with dev-only defaults.

## Next Steps
- Phase 02 (schema + seed) builds on Flyway config here.
- Phase 05 replaces SecurityConfig stub with real permitAll(verbs)/authenticated(/api/me) + oauth2Login.
