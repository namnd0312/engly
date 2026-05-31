# Deployment Guide

**Project:** Engly — English irregular-verb learning app
**MVP status:** Verb lookup runnable locally. SSO/BFF (phase 05) not yet implemented.

---

## Prerequisites

| Tool        | Version | Notes |
|-------------|---------|-------|
| Java        | 21      | `java -version` must show 21 |
| Node.js     | 24      | `node -v` |
| npm         | bundled with Node 24 | |
| Docker      | 29.4.0+ | Required for `docker compose` AND for running Testcontainers in tests |
| kubectl     | any     | Required for k8s deployment (OrbStack) |
| OrbStack    | any     | Docker Desktop alternative with built-in K8s |
| Git         | any     | |

> **Testcontainers note:** `./mvnw test` spins up an ephemeral `postgres:16-alpine` container via Docker. Docker daemon must be running before executing tests.

> **OrbStack K8s note:** Deployment targets OrbStack's K8s cluster (accessible via `kubectl`). The script `k8s/deploy-to-orbstack.sh` builds images into OrbStack's shared image store and applies manifests to the `engly` namespace.

---

## Environment Variables

### Backend

| Variable      | Default | Description |
|---------------|---------|-------------|
| `DB_USER`     | `engly` | Postgres username |
| `DB_PASSWORD` | `engly` | Postgres password |

Set via shell export or a `.env` file (never commit `.env` to git):

```bash
export DB_USER=engly
export DB_PASSWORD=engly
```

### Planned — Phase 05 (SSO/BFF, not yet active)

| Variable              | Description |
|-----------------------|-------------|
| `OIDC_CLIENT_SECRET`  | OAuth2 client secret for the `engly` client registered in OPEN-GATE-APP |
| `OIDC_ISSUER_URI`     | OPEN-GATE issuer URI, e.g. `http://localhost:9000` |

These will be wired into `application.yml` under `spring.security.oauth2.client.*` in phase 05.

---

## Local Run Order

Start services in this order (each depends on the previous):

### 1. Start PostgreSQL (Docker Compose)

```bash
cd /path/to/Engly
docker compose up -d
```

This starts Postgres on **port 5433** (mapped from container port 5432).
Database name: `engly`, user: `engly`, password: `engly`.

Verify:
```bash
docker compose ps        # postgres container should be "Up"
psql -h localhost -p 5433 -U engly -d engly -c '\dt'
```

### 2. Start Backend (Spring Boot)

```bash
cd engly-be
./mvnw spring-boot:run
```

- Flyway applies `V1__create_irregular_verb_table.sql` + `V2__seed-irregular-verbs-180-rows-ai-draft-unverified.sql` on first run.
- API available at **http://localhost:8081**

Verify:
```bash
curl http://localhost:8081/api/verbs?q=go
curl http://localhost:8081/api/verbs/go
```

### 3. Start Frontend (Angular)

```bash
cd engly-fe
npm install        # first time only
npm start          # runs: ng serve --proxy-config proxy.conf.json
```

- App available at **http://localhost:4200**
- `/api/**` requests are proxied to `http://localhost:8081` via `proxy.conf.json`

---

## Running Tests

### Backend Tests

```bash
cd engly-be
./mvnw test
```

- Docker must be running (Testcontainers pulls `postgres:16-alpine` on first run).
- Test classes: `EnglyApplicationTests`, `VerbQueryServiceTest`, `VerbControllerTest`, `VerbApiIntegrationTest`, `SecurityRulesTest`.
- HTML report: `engly-be/target/surefire-reports/`

### Frontend Tests

```bash
cd engly-fe
npx ng test --watch=false --browsers=ChromeHeadless
```

If running in a no-sandbox environment (CI, Docker):
```bash
npx ng test --watch=false --browsers=ChromeHeadlessNoSandbox
```

To use `ChromeHeadlessNoSandbox`, add a custom launcher in `karma.conf.js`:
```js
customLaunchers: {
  ChromeHeadlessNoSandbox: {
    base: 'ChromeHeadless',
    flags: ['--no-sandbox', '--disable-setuid-sandbox']
  }
}
```

---

## Docker Compose Reference

The `docker-compose.yml` at project root defines:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: engly
      POSTGRES_USER: engly
      POSTGRES_PASSWORD: engly
    ports:
      - "5433:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
volumes:
  pgdata:
```

---

## Planned — Phase 05: OPEN-GATE-APP Client Registration

When phase 05 (SSO/BFF) is implemented, register the `engly` OAuth2 client in OPEN-GATE-APP:

1. Start OPEN-GATE-APP on **port 9000**.
2. Register a client with:
   - `client_id`: `engly`
   - `client_secret`: `<secret>` (store in `OIDC_CLIENT_SECRET` env var — never in code)
   - `redirect_uri`: `http://localhost:8081/login/oauth2/code/open-gate`
   - `scopes`: `openid`, `profile`, `email`
   - `grant_types`: `authorization_code`
3. Add to `backend/src/main/resources/application.yml`:
   ```yaml
   spring:
     security:
       oauth2:
         client:
           registration:
             open-gate:
               client-id: engly
               client-secret: ${OIDC_CLIENT_SECRET}
               scope: openid,profile,email
               redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
           provider:
             open-gate:
               issuer-uri: ${OIDC_ISSUER_URI:http://localhost:9000}
   ```
4. Re-enable CSRF (`CookieCsrfTokenRepository.withHttpOnlyFalse()`) in `SecurityConfig`.
5. Add `MeController` (GET `/api/me`) returning authenticated user info.

---

## Build for Production

### Backend — Maven

```bash
cd engly-be
./mvnw -DskipTests package
java -jar target/engly-0.0.1-SNAPSHOT.jar
```

JAR output: `engly-be/target/engly-0.0.1-SNAPSHOT.jar`

### Frontend — Angular

```bash
cd engly-fe
npm run build        # outputs to dist/frontend/
```

### Kubernetes Deployment

Deploy both to OrbStack K8s:

```bash
sh k8s/deploy-to-orbstack.sh
```

This builds Docker images (`engly-be:local`, `engly-fe:local`) into OrbStack's shared store, applies manifests, and waits for rollouts. Access the SPA at `http://localhost:31485` (NodePort exposed by OrbStack's LoadBalancer).

To teardown:
```bash
kubectl delete namespace engly
```
