# Phase 02 — Rebuild FE Image, Redeploy k8s, SSO E2E Manual Test

## Context Links
- Overview: [plan.md](plan.md)
- Depends on: [phase-01-fe-profile-page-route-nav.md](phase-01-fe-profile-page-route-nav.md) (`blockedBy`).
- Deploy script: `k8s/deploy-to-orbstack.sh` (builds engly-be:local + engly-fe:local, applies `k8s/`).
- FE service: `k8s/30-engly-fe-frontend.yaml` (NodePort, nodePort 30090 → `http://localhost:30090`).
- Backend identity (read-only): `engly-be/.../identity/api/SessionController.java` (`GET /api/me`), `.../common/SecurityConfig.java` (confidential-client PKCE).
- Project memory: SSO needs 4 services up (Postgres, engly-be, engly-fe, OPEN-GATE IdP `:8080` + its Postgres).

## Overview
- **Priority:** P3
- **Status:** pending
- **Description:** Rebuild the engly-fe image with the new profile page, redeploy to OrbStack k8s
  (`engly` namespace), then run the manual SSO/BFF e2e test through `http://localhost:30090`.
  This phase is the actual point of the task: prove the existing OIDC login flow works end-to-end.

## Key Insights
- FE image change (incl. the `nginx.conf` blocker fix) → rebuild `engly-fe:local` then `kubectl -n engly rollout restart deploy/engly-fe`. Full path: rerun `k8s/deploy-to-orbstack.sh`. engly-be normally needs NO rebuild — EXCEPT if step-4b preflight shows a wrong `redirect_uri` origin, in which case the forwarded-headers fix (`application.yml`) requires an engly-be rebuild/redeploy too.
- The page itself has zero new backend dependency; if login fails it's the EXISTING SSO wiring, not this feature.
- Known historical gotchas (already fixed — list as risks to check first if login breaks): confidential-client PKCE in `SecurityConfig`; Boot-4 Flyway module on the OPEN-GATE IdP.

## Requirements
**Functional (manual e2e)**
- Preflight: all 4 services Running/Ready in `engly` namespace + IdP reachable.
- Login → land back authenticated → `/profile` shows real `name`, `email`, `sub`.
- Logout → `/profile` shows the login prompt.

**Non-functional**
- Use OrbStack k8s only; access strictly via NodePort `http://localhost:30090` (matches OAuth2 redirect/cookie domain assumptions).

## Architecture
```
Browser :30090 ──(NodePort)──► engly-fe pod (nginx, /api,/oauth2,/login,/logout proxied)
   │ click Login → /oauth2/authorization/opengate
   ▼
engly-be (BFF, :8081) ──Auth Code + PKCE──► OPEN-GATE IdP :8080 ──► callback ► httpOnly session cookie
   │
   ▼ GET /api/me (cookie) → {sub,email,name} → AuthService.user signal → /profile renders
```
> **CONFIRMED:** before phase-01's fix, `engly-fe/nginx.conf` proxied ONLY `/api/` — `/oauth2`,`/login`,`/logout` fell through to the SPA `index.html`, breaking login on k8s. Phase-01 adds those proxy blocks. This phase MUST preflight-verify they actually proxy (see step 4b) before attempting the login flow.

## Related Code Files
**Modify** — none (deployment/test phase).
**Create** — none (optional: append e2e result notes to `reports/` after run).
**Delete** — none.

## Implementation Steps
1. **Build FE image** (after phase-01 `ng build` passes):
   `cd /Users/admin/Desktop/DEV/AI/Engly && docker build -t engly-fe:local engly-fe` (or run `k8s/deploy-to-orbstack.sh` for the full build+apply).
2. **Roll out:** `kubectl -n engly rollout restart deploy/engly-fe && kubectl -n engly rollout status deploy/engly-fe`.
3. **Preflight — services up:**
   - `kubectl -n engly get pods` → engly-be, engly-fe, Postgres all Running/Ready.
   - Confirm OPEN-GATE IdP `:8080` (+ its Postgres) up and reachable from engly-be.
4. **Anonymous check:** open `http://localhost:30090/profile` in a fresh/incognito window → expect login prompt (no Profile link in header).
4b. **Proxy preflight (REQUIRED — validates the blocker fix):**
   - `curl -sS -i 'http://localhost:30090/oauth2/authorization/opengate'` → expect an HTTP **302** with a `Location:` pointing at the OPEN-GATE IdP authorize endpoint (`http://localhost:8080/oauth2/authorize?...`), **NOT** a `200` returning SPA `index.html`. A 200 with HTML means nginx still SPA-falls-back → phase-01 nginx fix missing/not deployed; STOP and fix before continuing.
   - Inspect the 302 `Location` `redirect_uri` param: it must be `http://localhost:30090/login/oauth2/code/opengate`. If it shows a different host/scheme (e.g. `engly-be:8081` or `http://...`), the forwarded-headers issue (Risk below) is in play.
5. **Login:** click Login → redirected to OPEN-GATE IdP → authenticate → redirected back authenticated; header shows user + Profile link.
6. **Profile check:** navigate `/profile` → verify Name, Email, Sub shown as plain text and match the logged-in account (`—` only where backend returns null).
7. **Logout:** click Logout → returns home → revisit `/profile` → expect login prompt again; Profile link gone from header.
8. Record pass/fail + any anomalies.

## Todo List
- [ ] Build `engly-fe:local` image
- [ ] `rollout restart` + `rollout status` engly-fe
- [ ] Preflight: 4 services Running/Ready + IdP reachable
- [ ] Anonymous `/profile` → login prompt
- [ ] **Proxy preflight: `curl /oauth2/authorization/opengate` → 302 to IdP (not SPA HTML)**
- [ ] **Verify 302 `redirect_uri` = `http://localhost:30090/login/oauth2/code/opengate` (forwarded-headers OK)**
- [ ] Login via OPEN-GATE IdP → authenticated, Profile link visible
- [ ] `/profile` shows name/email/sub matching account
- [ ] Logout → `/profile` reverts to login prompt
- [ ] Record results

## Success Criteria
- Full cycle passes: anonymous prompt → login → profile shows correct name/email/sub → logout → prompt.
- No console errors on `/api/me`, `/oauth2/*`, `/login`, `/logout`.
- Confirms existing SSO/BFF OIDC flow works e2e in k8s (the real objective).

## Risk Assessment
- **Stale image:** `kubectl` may keep the old image (`imagePullPolicy: Never`/cached `:local`). Mitigation: confirm new image id; restart deploy; if needed bump a tag or delete pod.
- **PKCE / confidential-client (known gotcha):** if the OAuth round-trip fails, recheck confidential-client PKCE config in `SecurityConfig.java`.
- **IdP Flyway (known gotcha):** OPEN-GATE IdP may fail to start without its Boot-4 Flyway module — verify IdP pod healthy before testing.
- **(CONFIRMED) nginx not proxying auth paths:** if phase-01's `nginx.conf` edit is missing or the old image is still running, `/oauth2|/login|/logout` SPA-fall-back and login cannot start/complete. Mitigation: step 4b proxy preflight (must see 302, not HTML) before testing login.
- **Reverse-proxy forwarded-headers → redirect_uri mismatch (verify):** engly-be derives `{baseUrl}` for `redirect-uri: {baseUrl}/login/oauth2/code/opengate` from the INCOMING request. Behind nginx, Spring may see nginx's own host/scheme (e.g. `engly-be:8081` / `http`) instead of the browser origin `http://localhost:30090`, producing a `redirect_uri` the IdP rejects (mismatch) and a callback that can't round-trip. Mitigations: (a) nginx must set `proxy_set_header Host`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-Port` on the auth-path blocks; (b) engly-be must honor forwarded headers — set Spring `server.forward-headers-strategy: framework` (or `native`) in `application.yml` (currently the `server:` block has only `port: 8081`, so this is NOT yet configured). Verify via step 4b: the 302 `redirect_uri` must read `http://localhost:30090/...`. Fallback if a clean baseUrl can't be derived: pin an absolute `redirect-uri: http://localhost:30090/login/oauth2/code/opengate` in `application.yml` for the k8s profile. Keep minimal — only add `forward-headers-strategy` if the preflight shows a wrong origin.
- **Service down:** any of the 4 services down → login fails. Mitigation: preflight step 3.

## Security Considerations
- Tokens never reach the browser; only the httpOnly session cookie + `/api/me` projection. Verify no token leakage in network tab.
- Logout must invalidate the BFF session (POST `/logout` with X-XSRF-TOKEN) — confirm `/api/me` returns 401 afterward.
- Test with a non-production IdP account in the local OrbStack environment only.

## Next Steps
- On pass: feature complete; optionally update `docs/codebase-summary.md` / `docs/system-architecture.md` to note the `/profile` route.
- On fail: triage via the known-gotcha risks above; the page itself needs no change (FE-only, signal-driven).

## Unresolved Questions
1. ~~In k8s, are `/oauth2`/`/login`/`/logout` routed to engly-be by nginx?~~ **RESOLVED (CONFIRMED NO):** `engly-fe/nginx.conf` proxied only `/api/`; the auth paths SPA-fell-back, so login was broken on k8s. Fix added to phase-01 (nginx proxy blocks). Re-verified at deploy time by phase-02 step 4b.
2. **Is the OPEN-GATE OAuth2 redirect URI registered for the k8s origin `http://localhost:30090` (i.e. `http://localhost:30090/login/oauth2/code/opengate`), or only for the dev `ng serve` origin?** This is now tightly coupled to the forwarded-headers point: the IdP-registered `redirect_uri` must EXACTLY match what engly-be ultimately sends. Whatever origin Spring derives (or is pinned to) for `{baseUrl}` must be the one registered at OPEN-GATE — otherwise the IdP rejects with `redirect_uri` mismatch. Confirm registration + the step-4b-observed value agree.
3. Should an automated FE unit spec for `ProfilePageComponent` be required, or is the manual e2e sufficient for this YAGNI-scoped task? (Plan marks it optional.)
