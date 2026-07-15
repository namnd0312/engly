---
phase: 4
title: Tests & docs
status: completed
effort: M
priority: P2
dependencies:
  - 1
  - 2
  - 3
---

# Phase 4: Tests & docs

## Overview

Cover the new resolution/linking logic and the security wiring, keep the existing suite green, and
update architecture docs to describe the added SSO path.

## Requirements

- Functional: prove resolve-or-link behavior across the four resolution branches and that the OAuth2
  chain issues app cookies without weakening the `/api/**` chain.
- Non-functional: no fake/mocked-away behavior to force a pass; follow existing test style
  (`AuthFlowIntegrationTest`, `SecurityRulesTest`, `OidcRoleMappingConfigTest`).

## Architecture / test surface

**Correction (red-team Finding #9):** the suite has **no `oidcLogin()` helper** — existing security tests
authenticate with `.jwt(...)` against the app's own HS256 token (`SecurityRulesTest`, `SessionControllerTest`).
`OidcRoleMappingConfigTest` tests the app-JWT roles-claim→authority mapping, NOT any oauth2Login flow, so it
is not a precedent for SSO tests. For the success handler, construct an `OAuth2AuthenticationToken`
(with a `DefaultOAuth2User`/`DefaultOidcUser` + `authorizedClientRegistrationId`) and invoke the handler
directly with a `MockHttpServletResponse`.

Backend (JUnit + spring-security-test):
- `SocialLoginServiceTest` — over the resolution branches (verified-both-sides policy):
  1. returning linked user (`(provider, provider_user_id)` hit) → same `app_user`, no new row.
  2. Google verified email + existing **verified** account → links (new `app_user_identity`, no new `app_user`).
  3. Google verified email + existing **unverified** (password) account → does NOT merge; creates a
     separate SSO account (Finding #1 regression guard).
  4. Google verified email, no match → new user with `email_verified=true` + identity.
  5. Facebook (never email-linked) + email present → new account keyed on provider id; no link to an
     email-matching account (Finding #2 guard).
  6. Facebook no email → new email-less user + identity.
  7. mixed-case provider email vs stored lowercase → still resolves/links (Finding #7 guard).
  8. concurrent-create → inner `REQUIRES_NEW` isolation; unique violation → re-resolve → single account
     wins, no `UnexpectedRollbackException` (Finding #5 — use REAL transactions, not a mocked repo).
- `SecurityConfigOauth2Test` (or extend `SecurityRulesTest`) — `/oauth2/authorization/google` reachable;
  **`/api/**` still 401 unauthenticated and CSRF still enforced** on state-changing `/api/**` (Finding #8
  — proves Chain A `securityMatcher` didn't swallow Chain B).
- **Boot-with-SSO-unset test** (Finding #6): context loads with no `GOOGLE_*`/`FACEBOOK_*` env — no
  `ClientRegistrationRepository`, no Chain A, password login still works.
- Success-handler test: constructed `OAuth2AuthenticationToken` → response carries `ACCESS_TOKEN` +
  `REFRESH_TOKEN` Set-Cookie and a redirect to the `app.frontend-url` value.
- **Null-email mint / `/api/me` test** (Finding #9): an email-less SSO account mints a JWT (null `email`
  claim omitted, not throwing) and `/api/me` returns `email=null` without error.

Frontend (Jasmine/Karma, existing setup):
- `auth.service.spec.ts` — `loginWithProvider('google')` calls `navigate('/oauth2/authorization/google')`
  (spy on the `navigate` seam).
- login-page spec — `?error=sso` renders the error message.

## Related Code Files

- Create: `engly-be/src/test/java/com/engly/identity/application/SocialLoginServiceTest.java`
- Create/Modify: `engly-be/src/test/java/com/engly/common/` OAuth2 chain test (extend `SecurityRulesTest`
  if a new file is overkill).
- Modify: `engly-fe/src/app/core/auth.service.spec.ts`, login-page spec.
- Modify: `docs/system-architecture.md` — add SSO login section (two-chain design, resolve-or-link,
  cookie session reuse). Update `docs/deployment-guide.md` with the new env vars + provider redirect URIs.
- Modify: `README.md` only if it documents the auth/login flow (check first).

## Implementation Steps

1. Write `SocialLoginServiceTest` covering all resolution branches + the unverified-account no-merge
   guard (#3) + Facebook no-email-link guard (#5) + mixed-case (#7) + concurrent-create with real
   `REQUIRES_NEW` transactions (#8).
2. Add the OAuth2 security-chain test; assert `/api/**` still 401 + CSRF-enforced (Chain B intact).
3. Add the boot-with-SSO-unset context test.
4. Add the success-handler cookie/redirect test (constructed `OAuth2AuthenticationToken`) + the
   null-email mint / `/api/me` test.
5. FE specs for `loginWithProvider` + `?error=sso`.
6. Run backend `mvn test` (or the project's test command) and FE `npm test` — all green, no regressions.
7. Update `docs/system-architecture.md`, `docs/deployment-guide.md`; add env vars
   (`GOOGLE_CLIENT_ID/SECRET`, `FACEBOOK_CLIENT_ID/SECRET`, `FRONTEND_URL`) to k8s manifests as Secret/config
   refs (secrets not committed). Document the dev single-origin proxy requirement + provider redirect URIs.

## Success Criteria

- [ ] All resolution branches covered, including the unverified-account no-merge guard (Finding #1),
      Facebook no-email-link guard (#2), mixed-case normalization (#7), and concurrent-create with real
      `REQUIRES_NEW` transactions (#5).
- [ ] `/api/**` chain behavior asserted unchanged (401 + CSRF) — proves `securityMatcher` scoping (#8).
- [ ] Context boots with SSO env unset; password login unaffected (#6).
- [ ] Success handler proven to set both cookies + redirect to `app.frontend-url`.
- [ ] Null-email SSO account mints a JWT and serves `/api/me` without error (#9).
- [ ] FE specs for provider redirect + error param pass.
- [ ] Full backend + frontend suites green (no skipped/weakened tests).
- [ ] Architecture + deployment docs updated (incl. dev single-origin proxy + redirect URIs); secrets
      only via env/k8s Secret.

## Risk Assessment

- **Testing the real provider round-trip** is out of scope for CI (needs live Google/Facebook); cover
  the app-side logic with simulated `OAuth2User`/`OAuth2AuthenticationToken`, and do the live end-to-end
  manually per Phase 2 success criteria.
- **Coverage gate**: repo has JaCoCo (`260613-...jacoco-coverage` plan). Ensure new classes meet the
  existing threshold or adjust deliberately, not by excluding files silently.
