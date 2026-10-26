# Engly — Irregular Verbs Learning Platform

Monorepo: `engly-be/` (Spring Boot 3.4.5, **Maven**) · `engly-fe/` (Angular 19) · `k8s/` (OrbStack manifests).

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21 |
| Maven | 3.9+ (or use `engly-be/mvnw`) |
| Node | 20+ |
| Docker | 24+ |
| kubectl + OrbStack k8s | for the k8s deploy |

## Local Run (dev)

```bash
# 1. Start Postgres
docker compose up -d

# 2. Start backend (port 8081)
cd engly-be && ./mvnw spring-boot:run

# 3. Start frontend (port 4200, dev proxy /api -> 8081)
cd engly-fe && npm start
```

Admin accounts must enable 2FA (Profile → Bảo mật) before `/admin` works. Set `MFA_SECRET_KEY` (and
`JWT_SECRET`) in `engly-be/.env` so enrollments and sessions survive restarts. See `docs/deployment-guide.md`.

Registration sends an activation email — without a local SMTP server the send just fails silently
(logged at WARN) and the activation link is logged at INFO instead. To view real emails, run
Mailpit: `docker run -d -p 1025:1025 -p 8025:8025 axllent/mailpit`, then check http://localhost:8025.

## Kubernetes Deploy (OrbStack)

OrbStack's Docker engine shares its image store with its k8s cluster, so locally
built images need no registry push.

```bash
sh k8s/deploy-to-orbstack.sh      # builds engly-be:local + engly-fe:local, applies k8s/
```

App is then served by the `engly-fe` LoadBalancer at **http://localhost:31485**
(nginx serves the SPA and reverse-proxies `/api` → the `engly-be` Service).
Confirm the NodePort with `kubectl -n engly get svc engly-fe`.

```bash
kubectl -n engly get pods,svc        # status
kubectl delete namespace engly       # tear down
```

## Port Table

| Service | Port |
|---------|------|
| IdP (OPEN-GATE, planned) | 9000 |
| Backend (Spring Boot) | 8081 |
| Frontend (Angular dev) | 4200 |
| Frontend (k8s LoadBalancer NodePort) | 31485 |
| Postgres (docker-compose dev) | 5433 |
| Postgres (in-cluster Service) | 5432 |

## Stop (local dev)

```bash
docker compose down        # stop (keep volume)
docker compose down -v     # stop + delete data
```

## Tests

```bash
cd engly-be && ./mvnw test                                  # backend: 363 tests (Testcontainers needs Docker)
cd engly-fe && npx ng test --watch=false --browsers=ChromeHeadless   # frontend: 200 specs
```
