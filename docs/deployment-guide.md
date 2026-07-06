# Deployment Guide

**Project:** Engly — English irregular-verb learning app
**MVP status:** Full stack runnable locally and on Kubernetes (OrbStack).

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
| `ROLES_CLAIM_NAME`        | `roles` | App JWT claim name carrying roles (sourced from the local DB). |
| `S3_ENDPOINT`             | `http://localhost:4566` | S3 endpoint for the backend's own calls (avatar HeadObject). In k8s: `http://host.orb.internal:4566`. Blank on real AWS. |
| `S3_PUBLIC_ENDPOINT`      | `http://localhost:4566` | Browser-facing S3 host baked into presigned avatar URLs (signature is host-specific). Blank on real AWS. |
| `AVATAR_BUCKET`           | `engly-avatars` | S3 bucket holding avatar originals + resized variants. |
| `AWS_REGION` / `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `us-east-1` / `test` / `test` | Floci emulator defaults; real credentials (or blank keys → default provider chain) on real AWS. |
| `AVATAR_UPLOAD_URL_TTL` / `AVATAR_DOWNLOAD_URL_TTL` | `15m` / `1h` | Presigned PUT/GET lifetimes. |

Set via shell export or a `.env` file (never commit to git):

```bash
export DB_USER=engly
export DB_PASSWORD=engly
# JWT keys optional in dev (ephemeral keypair generated if unset):
# export JWT_PRIVATE_KEY="$(cat jwt-private.pem)"
# export JWT_PUBLIC_KEY="$(cat jwt-public.pem)"
```

Authentication note: the app issues its OWN JWTs for email/password accounts (access + refresh
delivered as httpOnly cookies).

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

### 1b. Start Floci + avatar resources (only for the avatar feature)

Avatar upload/resize uses a local AWS emulator ([Floci](https://floci.io/aws/), S3 + Lambda) —
opt-in compose profile so it won't clash with a standalone Floci already running on port 4566:

```bash
docker compose --profile floci up -d      # starts Floci on :4566 (needs /var/run/docker.sock)
./infra/floci/setup-floci.sh              # idempotent: bucket + CORS + resize Lambda + S3 trigger
```

The setup script builds the Python 3.12 + Pillow Lambda zip inside the AWS SAM build image
(`LAMBDA_ARCH=x86_64` to override the arm64 default) and wires an `s3:ObjectCreated:*`
notification (prefix `avatars/`, suffix `original`) to it. Smoke test:

```bash
aws --endpoint-url http://localhost:4566 s3 cp photo.jpg \
  s3://engly-avatars/avatars/u1/x1/original --content-type image/jpeg
aws --endpoint-url http://localhost:4566 s3 ls s3://engly-avatars/avatars/u1/x1/
# → 256.webp + 64.webp appear a few seconds later
```

Floci may not persist resources across restarts — re-run `setup-floci.sh` after
`docker compose --profile floci up -d` if avatars stop resolving. Without Floci the app still
boots and runs; only avatar upload/serving fails.

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
  - `common/SecurityRulesTest` — JWT resource server config + `/api/me` returns `isAdmin`
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
