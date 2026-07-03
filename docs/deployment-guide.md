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

| Variable                  | Default | Description |
|---------------------------|---------|-------------|
| `DB_USER`                 | `engly` | Postgres username |
| `DB_PASSWORD`             | `engly` | Postgres password |
| `JWT_PRIVATE_KEY`         | — (ephemeral) | RSA private key (PEM/base64 DER body) for signing the app's access JWTs. If unset, an ephemeral keypair is generated at startup — fine for a single instance (tokens drop on restart). **Required for prod / multiple replicas.** |
| `JWT_PUBLIC_KEY`          | — (ephemeral) | Matching RSA public key (PEM/base64 DER body) for verifying access JWTs. |
| `JWT_ACCESS_TOKEN_TTL`    | `15m`   | Access-token lifetime (Spring `Duration`, e.g. `15m`, `1h`). |
| `JWT_REFRESH_TOKEN_TTL`   | `30d`   | Refresh-token lifetime. |
| `AUTH_COOKIE_SECURE`      | `false` | Set `true` when served over HTTPS so the auth cookies carry the `Secure` flag. |
| `OAUTH2_OPENGATE_CLIENT_SECRET` | — | OAuth2 client secret for `engly` client in OPEN-GATE-APP — only for the optional SSO button (never commit) |
| `ROLES_CLAIM_NAME`        | `roles` | App JWT claim name carrying roles (sourced from the local DB). |

Set via shell export or a `.env` file (never commit to git):

```bash
export DB_USER=engly
export DB_PASSWORD=engly
# JWT keys optional in dev (ephemeral keypair generated if unset):
# export JWT_PRIVATE_KEY="$(cat jwt-private.pem)"
# export JWT_PUBLIC_KEY="$(cat jwt-public.pem)"
export OAUTH2_OPENGATE_CLIENT_SECRET=<secret-from-open-gate>  # only if using OPEN-GATE SSO
```

Authentication note: the app issues its OWN JWTs for email/password accounts (access + refresh
delivered as httpOnly cookies). OPEN-GATE is now only an optional SSO button — on SSO login the
backend provisions/links a local user by email and issues the app's own cookies.

### Frontend

No env vars needed (config baked into application.yml; API endpoints proxy via nginx/Angular).

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
curl http://localhost:8081/api/verbs?q=go          # public search
curl http://localhost:8081/api/verbs/go            # public detail
curl http://localhost:8081/api/me                  # 401 (unauthenticated)
curl http://localhost:8081/api/admin/verbs         # 401 (unauthenticated)
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
- Test classes:
  - `EnglyApplicationTests` — smoke test (context loads)
  - `verb/VerbQueryServiceTest` — public search service unit tests
  - `verb/VerbControllerTest` — public endpoints (`@WebMvcTest`)
  - `verb/VerbApiIntegrationTest` — full-stack public CRUD regression
  - `verb/admin/AdminSecurityRulesTest` — admin endpoint auth (401/403/200)
  - `verb/admin/VerbAdminControllerTest` — admin CRUD endpoints (`@WebMvcTest`)
  - `verb/admin/VerbAdminIntegrationTest` — full-stack admin CRUD + 409 duplicate
  - `common/SecurityRulesTest` — OAuth2/OIDC config + `/api/me` returns `isAdmin`
- HTML report: `engly-be/target/surefire-reports/`

### Frontend Tests

```bash
cd engly-fe
npm test -- --watch=false --browsers=ChromeHeadless
```

If running in a no-sandbox environment (CI, Docker):
```bash
npm test -- --watch=false --browsers=ChromeHeadlessNoSandbox
```

To use `ChromeHeadlessNoSandbox`, ensure `karma.conf.js` includes:
```js
customLaunchers: {
  ChromeHeadlessNoSandbox: {
    base: 'ChromeHeadless',
    flags: ['--no-sandbox', '--disable-setuid-sandbox']
  }
}
```

Test files:
- `src/core/auth.service.spec.ts` — isAdmin signal, login/logout
- `src/features/verb-search/` — public search/detail tests
- `src/features/admin/admin.guard.spec.ts` — guard auth flow
- `src/features/admin/admin-verb-api.service.spec.ts` — CRUD mocks
- `src/features/admin/admin-form-page.component.spec.ts` — form validation

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

## OPEN-GATE-APP Client Registration

Admin CRUD requires OAuth2 authentication + OIDC roles claim mapping. Follow these steps:

### Local Setup

1. Start OPEN-GATE-APP on **port 9000** (ensure running before starting backend).
2. Register a client in OPEN-GATE-APP:
   - `client_id`: `engly`
   - `client_secret`: `<secret>` (store in `OAUTH2_OPENGATE_CLIENT_SECRET` env var — **never commit**)
   - `redirect_uri`: `http://localhost:8081/login/oauth2/code/opengate`
   - `scopes`: `openid profile email`
   - `grant_types`: `authorization_code`
   - Create an admin role/group and assign test user to it; confirm the role appears in id_token `roles` claim (name configurable via `ROLES_CLAIM_NAME`).

3. Backend reads from `application.yml` (registration/provider already configured):
   - `OAUTH2_OPENGATE_CLIENT_SECRET` env var → client secret
   - `ROLES_CLAIM_NAME` env var (default `roles`) → id_token claim name for admin roles

4. Start backend with env vars set:
   ```bash
   export OAUTH2_OPENGATE_CLIENT_SECRET=<secret>
   export ROLES_CLAIM_NAME=roles  # or your custom claim name
   ./mvnw spring-boot:run
   ```

5. Test login flow:
   - Browser: `http://localhost:4200` → Login button → redirects to `/oauth2/authorization/opengate`
   - OPEN-GATE login form → redirects back to app
   - `GET /api/me` now returns user info + `isAdmin: true/false` based on roles claim

### Production (Kubernetes)

Create a Secret for `OAUTH2_OPENGATE_CLIENT_SECRET` and mount in backend Deployment (see `k8s/20-engly-be-backend.yaml`):

```yaml
env:
  - name: OAUTH2_OPENGATE_CLIENT_SECRET
    valueFrom:
      secretKeyRef:
        name: oauth2-secret
        key: client-secret
  - name: ROLES_CLAIM_NAME
    value: "roles"  # or your IdP's claim name
```

Admin role assignment in OPEN-GATE is IdP-specific; consult OPEN-GATE-APP admin docs.

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
