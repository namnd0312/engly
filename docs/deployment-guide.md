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
| `JWT_SECRET`              | — (ephemeral) | HS256 signing secret for the app's access JWTs (any length, SHA-256-derived). Unset → ephemeral key: every restart logs everyone out. **Required for prod / multiple replicas.** |
| `MFA_SECRET_KEY`          | — (ephemeral) | AES-256-GCM key protecting stored admin TOTP secrets (SHA-256-derived). Unset → ephemeral key: after every restart enrolled admins are **locked out** (`MFA_UNAVAILABLE`) until the SQL reset below. Set for any persistent deployment. |
| `JWT_ACCESS_TOKEN_TTL`    | `15m`   | Access-token lifetime (Spring `Duration`, e.g. `15m`, `1h`). |
| `JWT_REFRESH_TOKEN_TTL`   | `30d`   | Refresh-token lifetime. |
| `AUTH_COOKIE_SECURE`      | `false` | Set `true` when served over HTTPS so the auth cookies carry the `Secure` flag. |
| `ROLES_CLAIM_NAME`        | `roles` | App JWT claim name carrying roles (sourced from the local DB). |
| `FRONTEND_URL`            | `http://localhost:4200` | SPA origin the social-login handshake redirects back to (success → `/`, failure → `/login?error=sso`). No trailing slash. In prod set to the public app origin. |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | — (disabled) | Google OAuth2 web client. Both blank → Google login disabled. Never commit real values. |
| `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` | — (disabled) | Facebook app credentials. Both blank → Facebook login disabled. |
| `FACEBOOK_API_VERSION`    | `v19.0` | Graph API version for the Facebook endpoints (the built-in defaults to a deprecated version). |
| `S3_ENDPOINT`             | `http://localhost:4566` | S3 endpoint for the backend's own calls (avatar HeadObject). In k8s: `http://host.orb.internal:4566`. Blank on real AWS. |
| `S3_PUBLIC_ENDPOINT`      | `http://localhost:4566` | Browser-facing S3 host baked into presigned avatar URLs (signature is host-specific). Blank on real AWS. |
| `AVATAR_BUCKET`           | `engly-avatars` | S3 bucket holding avatar originals + resized variants. |
| `AWS_REGION` / `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `us-east-1` / `test` / `test` | Floci emulator defaults; real credentials (or blank keys → default provider chain) on real AWS. |
| `AVATAR_UPLOAD_URL_TTL` / `AVATAR_DOWNLOAD_URL_TTL` | `15m` / `1h` | Presigned PUT/GET lifetimes. |
| `MAIL_HOST` / `MAIL_PORT`  | `localhost` / `1025` | SMTP server for account-activation emails. Dev default targets Mailpit. |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | — (blank) | SMTP auth credentials. Blank for Mailpit; required for a real relay (e.g. SES-SMTP). Never commit real values. |
| `MAIL_SMTP_AUTH` / `MAIL_SMTP_STARTTLS` | `false` / `false` | Enable for a real relay that requires auth/TLS. |
| `MAIL_FROM`               | `no-reply@engly.local` | Sender address on activation emails. Must be a domain-verified sender in prod. |
| `EMAIL_VERIFICATION_ENABLED` | `true`  | **Rollback switch.** Set `false` to instantly revert to the pre-feature flow: register auto-verifies and issues a session immediately, login is never gated. No redeploy needed. |
| `EMAIL_VERIFICATION_TTL`  | `24h`   | Activation token lifetime. |
| `EMAIL_VERIFICATION_RESEND_COOLDOWN` | `60s` | Minimum interval between resend requests for the same account. |

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

Social login (optional): set the Google/Facebook client id+secret to enable "Sign in with Google/Facebook".
Register the provider's OAuth redirect URI as `{FRONTEND_URL}/login/oauth2/code/{provider}` (dev:
`http://localhost:4200/login/oauth2/code/google`) — it must match the origin the browser uses, which is
why the Angular dev proxy forwards `/oauth2` + `/login/oauth2` with `changeOrigin:false`. With both
providers unset, the app boots normally with password login only.

Email activation on registration: self-service sign-up creates the account but issues no session
until the emailed activation link is clicked (login is rejected with 403 until then). Prod needs a
real SMTP relay (e.g. AWS SES-SMTP) and a domain-verified `MAIL_FROM` sender — without it every
registration silently loses its activation email (the send is non-fatal by design, so registration
itself never fails, but the user gets stuck). `app.frontend-url` (`FRONTEND_URL`) must be the real
SPA origin so activation links resolve. If SMTP breaks in prod, set `EMAIL_VERIFICATION_ENABLED=false`
to revert to immediate-session-on-register (no redeploy) while the mail issue is fixed. The V11
migration backfills every pre-existing password account to `email_verified=true`, so only new
sign-ups after this feature ships are gated.

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

### 1c. Start Mailpit (optional, for activation emails)

Account-activation emails need an SMTP server. Without one, the backend still boots and
registration still succeeds — the send just fails silently (logged at WARN) and the activation
link is logged at INFO instead, so dev works either way:

```bash
docker run -d --name mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
```

View sent mail at **http://localhost:8025**. `MAIL_HOST`/`MAIL_PORT` already default to
`localhost:1025` (Mailpit's default), so no env vars are needed once it's running.

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

## Admin two-factor authentication (TOTP)

`/api/admin/**` requires `ROLE_ADMIN` **and** a session that passed a TOTP check (access-JWT claim `mfa=true`).
Regular users are not affected. Login (password or Google/Facebook) always starts with `mfa=false`.

- **Enroll:** Profile → "Bảo mật — Xác thực hai lớp" → scan the QR with Google Authenticator/Authy → enter a code.
- **Step-up:** opening `/admin` routes to `/mfa/verify`; one code keeps admin access for the refresh-token lifetime (30d).
- **Guards:** 5 wrong codes lock verification for 15 minutes; a code cannot be reused within its 30s window.
- **Keys:** set `JWT_SECRET` and `MFA_SECRET_KEY` to fixed values. In k8s both come from one secret:

```bash
kubectl -n engly create secret generic engly-jwt-secret \
  --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
  --from-literal=MFA_SECRET_KEY="$(openssl rand -base64 48)"
```

- **Lost device, or `MFA_SECRET_KEY` changed (no backup codes):** reset in the DB, then the admin enrolls again.

```sql
DELETE FROM user_mfa_totp WHERE user_id = (SELECT id FROM app_user WHERE email = '<admin email>');
UPDATE refresh_token SET mfa_verified = false WHERE user_id = (SELECT id FROM app_user WHERE email = '<admin email>');
```

- **Clock skew:** server and phone must agree within ±30s. Check with `kubectl -n engly exec deploy/engly-be -- date -u`.

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
