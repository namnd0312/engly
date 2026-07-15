---
title: Add Google & Facebook SSO login
description: >-
  Bridge Google + Facebook social login into the app's existing self-issued-JWT
  cookie session
status: completed
priority: P2
branch: main
tags:
  - auth
  - sso
  - oauth2
  - security
blockedBy: []
blocks: []
created: '2026-07-12T16:15:06.871Z'
createdBy: 'ck:plan'
source: skill
---

# Add Google & Facebook SSO login

## Overview

Add "Sign in with Google" and "Sign in with Facebook" to Engly. The app keeps its
**current auth model unchanged**: it issues its OWN HS256 JWT in httpOnly cookies
(`ACCESS_TOKEN`/`REFRESH_TOKEN`) and stays a stateless resource server. Social login is a
one-time OAuth2 handshake (Spring `oauth2Login()`) whose success handler resolves-or-creates
the local `app_user`, then calls the **existing** `AuthTokenService.issueSession(...)` to mint
the same cookie session as password login. After the redirect back to the SPA, every API call
is identical to today's password path.

The SSO hook is already designed in: `issueSession(...)` is documented "Used by register/login/SSO",
`UserAccount.passwordHash` is already nullable for SSO-only accounts, the V6 migration already
anticipates SSO accounts, and `spring-boot-starter-oauth2-client` is already in `pom.xml`.

## Product/Security decisions (locked by user)

1. **Account linking (revised after red-team Finding #1/#2)** — auto-link a social identity to an
   existing account **only when BOTH sides are verified**: the provider marks the email verified AND
   the existing local account's `email_verified` is already `true`. Rationale: password signup performs
   no email-ownership verification (no verification flow exists), so a password account's email may be
   attacker-squatted — auto-linking a verified social identity into it is account takeover
   (pre-account-hijacking). Consequences:
   - Google SSO accounts are created with `email_verified = true`, so a repeat Google login on the same
     email links to the same account (the common case stays seamless).
   - A password account (always `email_verified = false`) is **never** auto-linked; an email collision
     produces a distinct SSO account instead of a silent merge.
   - **Facebook is never email-linked** — no trustworthy `email_verified` signal exists in the Graph
     API. Facebook always keys on `(provider, provider_user_id)`.
2. **Facebook without email** — allowed. Create an email-less account keyed on provider id; user
   can add an email later on the profile page. Requires making `app_user.email` nullable.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Data model & account linking](./phase-01-data-model-account-linking.md) | Completed |
| 2 | [Backend OAuth2 login flow](./phase-02-backend-oauth2-login-flow.md) | Completed |
| 3 | [Frontend social login UI](./phase-03-frontend-social-login-ui.md) | Completed |
| 4 | [Tests & docs](./phase-04-tests-docs.md) | Completed |

## Red Team Review

### Session — 2026-07-12
**Findings:** 9 distinct (all accepted; every finding carried `file:line` codebase evidence)
**Severity breakdown:** 4 Critical, 3 High, 2 Medium

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Pre-account-hijacking via email auto-link into unverified password account | Critical | Accept (user-revised policy) | Completed |
| 2 | Facebook "email present ⇒ verified" is unenforceable (no Graph `email_verified`) | High | Accept | Completed |
| 3 | Phantom `app.frontend-url` property — did not exist; SecurityConfig has no redirect code | Critical | Accept | Completed |
| 4 | Dev-proxy origin split (`changeOrigin:true`) breaks handshake session + auth cookies | Critical | Accept | Completed |
| 5 | Catch-unique-violation + re-resolve inside one `@Transactional` → `UnexpectedRollbackException` | Critical | Accept | Phase 1, Phase 2 |
| 6 | Blank-default client-id YAML crashes startup (`Assert.hasText`) | High | Accept | Phase 2 |
| 7 | SSO email not normalized before `findByEmail` → duplicate accounts / linking bypass | High | Accept | Phase 1, Phase 2 |
| 8 | Chain A `securityMatcher` unspecified → could swallow `/api/**` | Medium | Accept | Phase 2 |
| 9 | Entity/data/test bundle: `@Column(nullable=false)`, unset `emailVerified`, null-email path untested, FB Graph v2.8 endpoints, non-existent `oidcLogin()` test helper | Medium | Accept | Phase 1, Phase 2, Phase 4 |

**User decision on #1/#2:** "Link only into verified local accounts." Auto-link requires provider-verified
email AND existing `email_verified=true`; Google accounts stored verified; password accounts never
auto-linked; Facebook keyed on provider id only (never email-linked).

### Whole-Plan Consistency Sweep
- `plan.md` decision #1 rewritten to the verified-both-sides policy; acceptance criteria still hold
  (verified-email link case now means Google→Google).
- Phase 1 resolution order + `ssoUser` factory + `email_verified` persistence updated; entity `@Column`
  nullability flip added; `REQUIRES_NEW` isolation added; email normalization added.
- Phase 2 `app.frontend-url` reframed as NEW property; `securityMatcher`, conditional
  `ClientRegistrationRepository`, Facebook `provider:` override, and transaction isolation added.
- Phase 3 dev-proxy single-origin fix added.
- Phase 4 test approach corrected (`OAuth2AuthenticationToken`, not `oidcLogin()`); new tests listed.
- No remaining references to "reuse existing frontend-url", "Facebook email = verified for linking", or
  the old provider-only-verified linking rule. **Zero unresolved contradictions.**

## Dependencies

- Phase 2 depends on Phase 1 (needs the `app_user_identity` table + `UserAccount` SSO factory).
- Phase 3 depends on Phase 2 (needs the `/oauth2/authorization/{provider}` entry points + redirect behavior).
- Phase 4 depends on Phases 1–3.
- External: Google Cloud OAuth client + Facebook App must be registered to obtain client id/secret
  and whitelisted redirect URIs (see Phase 2 → External setup). Not code; a deployment prerequisite.

## Acceptance criteria (whole plan)

- Clicking Google/Facebook on the login page completes a real OAuth2 authorization-code flow and
  returns the user to the SPA authenticated, with the same `ACCESS_TOKEN`/`REFRESH_TOKEN` cookies
  password login sets.
- A verified-email social login into an email-matching existing account links (one `app_user`, one
  extra `app_user_identity` row) instead of creating a duplicate.
- A Facebook login with no email creates a usable email-less account; the app never dead-ends.
- Password login, refresh rotation, logout, RBAC, and all existing tests still pass unchanged.
- No client secrets committed; all provider credentials come from env.
