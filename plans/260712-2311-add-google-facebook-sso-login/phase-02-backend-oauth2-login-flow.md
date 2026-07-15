---
phase: 2
title: Backend OAuth2 login flow
status: completed
effort: L
priority: P1
dependencies:
  - 1
---

# Phase 2: Backend OAuth2 login flow

## Overview

Wire Spring `oauth2Login()` for Google + Facebook. On success, resolve-or-link the `app_user`
(Phase 1 rules) and hand off to the **existing** `AuthTokenService.issueSession(...)` so the browser
ends up with the same cookie session as password login. The OAuth2 handshake is the only stateful
part; the app API stays stateless.

## Requirements

- Functional:
  - `GET /oauth2/authorization/google` and `.../facebook` start the authorization-code flow.
  - Callback `/login/oauth2/code/{provider}` resolves identity, mints app cookies, redirects to SPA.
  - Apply the locked linking rules (verified-email auto-link; email-less allowed).
- Non-functional:
  - Keep the existing `/api/**` chain stateless and unchanged in behavior.
  - No secrets in source; client id/secret from env.
  - Failures redirect to the SPA login page with a safe error code, never a stack trace.

## Architecture

### Two filter chains (the key structural point)

`oauth2Login()` must persist the OAuth2 authorization request + `state` between the outbound redirect
and the callback — that needs an HTTP session. The current single chain is
`SessionCreationPolicy.STATELESS`, which would break the flow. Split into two ordered chains:

- **Chain A (`@Order(1)`)** — scoped with an explicit
  `http.securityMatcher("/oauth2/**", "/login/oauth2/**")` (red-team Finding #8: chain scoping is done
  with `securityMatcher`, NOT `authorizeHttpRequests` matchers — omitting it makes this CSRF-disabled,
  session-bearing chain the first match for **all** requests, including `/api/**`). Enables
  `oauth2Login()`, `SessionCreationPolicy.IF_REQUIRED` (session lives only for the handshake), CSRF
  disabled on these provider-driven endpoints (state param is the CSRF defense for the callback).
  Register this chain conditionally (`@ConditionalOnBean(ClientRegistrationRepository.class)`, see
  provider config below) so envs without SSO configured don't create it.
- **Chain B (existing `apiSecurityFilterChain`, `@Order(2)`)** — unchanged: stateless resource server
  for `/api/**` + SPA, cookie-JWT, CSRF as today.

`oauth2Login()` config:
- `.authorizationEndpoint(...)` default base `/oauth2/authorization`.
- `.redirectionEndpoint(...)` default base `/login/oauth2/code/{registrationId}`.
- `.successHandler(SsoAuthenticationSuccessHandler)` — custom (below).
- `.failureHandler(...)` — redirect to `${app.frontend-url}/login?error=sso` (no exception leakage).

**`app.frontend-url` is a NEW property (red-team Finding #3).** It does NOT already exist — the current
`SecurityConfig` is a pure stateless JSON API with zero redirect code, and the earlier "reuse existing
per SecurityConfig/memory" note was a stale OPEN-GATE-era claim. Add it explicitly:
`app.frontend-url: ${FRONTEND_URL:http://localhost:4200}` in `application.yml`, bound via a small
`@ConfigurationProperties`/`@Value`. The redirect target is **always** this server-side property —
never a `redirect`/`returnUrl`/any request-supplied value (open-redirect defense).

### Identity resolution service

`SocialLoginService.resolveOrLink(provider, OAuth2User)` — implements the **verified-both-sides** policy
(see plan.md decision #1 and Phase 1 resolution order):
1. Extract `providerUserId` (Google: `sub`; Facebook: `id`), `email`, `emailVerified`, `displayName`
   (`name`). **Google** email is trusted only when the OIDC `email_verified` claim is `true`.
   **Facebook** has no `email_verified` field → `emailVerified` is always treated as `false` for FB
   (never email-linked, red-team Finding #2). **Normalize** the email (reuse `normalizeEmail`) before any
   comparison or persistence (Finding #7).
2. `findByProviderAndProviderUserId` → hit ⇒ return linked `UserAccount`.
3. Else if provider is Google AND `email != null && emailVerified` → `users.findByEmail(normalizedEmail)`:
   - hit **and existing account `email_verified == true`** ⇒ insert `UserIdentity` link row, return it.
   - hit but existing account **not** verified (e.g. password account) ⇒ do NOT merge; fall through to
     create a new SSO account (Finding #1 — no silent takeover of an unverified/squatted account).
   - miss ⇒ create `UserAccount.ssoUser(..., emailVerified=true)` + link row.
4. Else (Facebook, or Google without verified email) → create `UserAccount.ssoUser(..., emailVerified=false)`
   (email nullable) keyed on provider id + link row.
5. **Transaction isolation (Finding #5):** the create branch runs in an **inner `REQUIRES_NEW`**
   transaction (separate bean method) so a concurrent unique-constraint violation rolls back only the
   inner tx; on `DataIntegrityViolationException` re-run resolution once against a clean transaction.
   Do NOT catch-and-retry inside a single `@Transactional` (rollback-only → `UnexpectedRollbackException`).

### Success handler

`SsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler`:
- Cast principal to `OAuth2User`/`OidcUser`, read `registrationId` from
  `OAuth2AuthenticationToken.getAuthorizedClientRegistrationId()`.
- `UserAccount user = socialLoginService.resolveOrLink(registrationId, principal)`.
- `authTokenService.issueSession(user, response)` (sets `ACCESS_TOKEN` + `REFRESH_TOKEN` cookies).
- `response.sendRedirect(frontendUrl + "/")` where `frontendUrl` is the NEW `app.frontend-url` property
  (fixed server-side value, never request-derived).
- Invalidate the throwaway OAuth2 session after issuing cookies (clean up).

### Provider registration (config)

**Do NOT ship blank-default client-ids in `spring.security.oauth2.client.registration` (red-team
Finding #6).** With the `oauth2-client` starter on the classpath, Spring binds the `google`/`facebook`
registration keys even when `${GOOGLE_CLIENT_ID:}` is an empty string, then
`ClientRegistration.Builder.build()` runs `Assert.hasText(clientId)` → `IllegalArgumentException` at
startup, taking down password login too. That directly violates the "boots with SSO unset" criterion.

Instead build registrations from **non-blank** env values only, via a custom conditional bean:
- A `@Bean ClientRegistrationRepository` that reads `GOOGLE_*` / `FACEBOOK_*` and includes only providers
  whose client-id is non-blank; if none are set, the bean is absent entirely
  (`@ConditionalOnProperty`/programmatic guard) so no auto-config kicks in.
- Chain A is `@ConditionalOnBean(ClientRegistrationRepository.class)` (above), so with no providers the
  OAuth2 chain and `oauth2Login()` are simply not created.

**Facebook `provider:` override (red-team Finding #9).** `CommonOAuth2Provider.FACEBOOK` pins deprecated
Graph API **v2.8** endpoints (`https://www.facebook.com/v2.8/dialog/oauth`,
`https://graph.facebook.com/v2.8/oauth/access_token`). Override to a current Graph API version and keep
the email field on user-info:
```yaml
# built from env; illustrative shape (actual wiring is the conditional bean above)
provider:
  facebook:
    authorization-uri: https://www.facebook.com/v19.0/dialog/oauth
    token-uri:         https://graph.facebook.com/v19.0/oauth/access_token
    user-info-uri:     https://graph.facebook.com/me?fields=id,name,email
    user-name-attribute: id
# google needs no provider override (OIDC discovery).
# google scope: openid,email,profile   facebook scope: email,public_profile
```
Validate the chosen Facebook Graph version against a live Facebook app during the manual E2E step.

## Related Code Files

- Modify: `engly-be/src/main/java/com/engly/common/SecurityConfig.java`
  - add Chain A bean (`@Order(1)`, `securityMatcher`, `@ConditionalOnBean(ClientRegistrationRepository)`),
    annotate existing chain `@Order(2)`, keep it otherwise untouched.
- Create: `engly-be/src/main/java/com/engly/identity/application/SocialLoginService.java` (resolve-or-link,
  inner `REQUIRES_NEW` create method, email normalization).
- Create: `engly-be/src/main/java/com/engly/identity/application/SsoAuthenticationSuccessHandler.java`
- Create: a conditional `ClientRegistrationRepository` config (e.g.
  `engly-be/src/main/java/com/engly/identity/config/SocialLoginClientsConfig.java`) building only
  non-blank providers; absent when none configured.
- Create/Modify: add `app.frontend-url` binding (new property; `@ConfigurationProperties` or `@Value`).
- Modify: `engly-be/src/main/resources/application.yml` — add `app.frontend-url:
  ${FRONTEND_URL:http://localhost:4200}`; do NOT add blank-default `oauth2.client.registration` keys.
- Modify: `engly-fe/proxy.conf.json` — set `/oauth2` + `/login/oauth2` to `changeOrigin: false` (Finding #4).
- Reuse (no change): `AuthTokenService.issueSession(...)`, `UserAccountRepository`, Phase 1 `UserIdentityRepository`.
- Extract shared: `normalizeEmail` (from `UserAccountService`) to a util both password + SSO paths use.
- `pom.xml`: no change — `spring-boot-starter-oauth2-client` already present (line 64).

## Implementation Steps

1. Add `spring.security.oauth2.client` config to `application.yml` (env-driven, blank defaults).
2. `SocialLoginService.resolveOrLink(...)` implementing the resolution order; `@Transactional`.
   Extract provider-specific attribute reads into small private helpers (google vs facebook shape).
3. `SsoAuthenticationSuccessHandler` calling `resolveOrLink` + `issueSession` + redirect.
4. `SecurityConfig`: add ordered Chain A for `/oauth2/**` + `/login/oauth2/**` with `oauth2Login()`,
   `IF_REQUIRED` session, CSRF disabled on those matchers, success/failure handlers; mark existing
   chain `@Order(2)`. Verify `/api/**` behavior is byte-for-byte the same.
5. Make chain/registration conditional on at least one configured client-id so unconfigured envs boot.
6. **Dev single-origin fix (red-team Finding #4 — this is the whole flow's blocker in `ng serve`).**
   `proxy.conf.json` already forwards `/oauth2`+`/login` to :8081 but with `changeOrigin: true`, so Spring
   sees `Host: localhost:8081`, builds `redirect_uri` for :8081, and Google/Facebook redirect the browser
   **directly to :8081** — bypassing the :4200 proxy. Two failures cascade: (a) the handshake `JSESSIONID`
   was set on origin :4200, so the direct :8081 callback has no session → `authorization_request_not_found`
   → `?error=sso`; (b) `issueSession` writes `ACCESS_TOKEN`/`REFRESH_TOKEN` on the :8081 response → cookies
   bind to :8081 and the SPA on :4200 can't read them → user lands anonymous. Fix: keep the ENTIRE flow on
   one origin in dev — set the proxy entries for `/oauth2`+`/login/oauth2` to `changeOrigin: false` (so
   Spring builds the redirect_uri for :4200) and register the provider redirect URI as
   `http://localhost:4200/login/oauth2/code/{provider}`. Verify with a real E2E, not just a button-redirect
   check. (Prod is typically single-origin already — see Risk + open question.)

## External setup (deployment prerequisite, not code)

- Google Cloud Console → OAuth 2.0 Client (Web). Authorized redirect URI (dev, single-origin per
  Finding #4): `http://localhost:4200/login/oauth2/code/google` + the prod origin equivalent.
- Facebook for Developers → App → Facebook Login → Valid OAuth Redirect URIs:
  `http://localhost:4200/login/oauth2/code/facebook` (dev) + prod. Request `email` + `public_profile`.
- The dev redirect origin (:4200) must match the `changeOrigin: false` proxy setup in Phase 2 step 6.
- Set `GOOGLE_CLIENT_ID/SECRET`, `FACEBOOK_CLIENT_ID/SECRET` as env/secret (k8s Secret), never committed.

## Success Criteria

- [ ] Real Google flow: button → Google consent → back to SPA authenticated with app cookies.
- [ ] Real Facebook flow completes, including the no-email path (usable account created).
- [ ] Returning social user resolves to the same `app_user` (no duplicate rows).
- [ ] Verified-email match links to the existing password account.
- [ ] `/api/**` stateless behavior, password login, refresh, logout, RBAC unchanged (existing tests green).
- [ ] App boots with SSO env unset (feature simply inactive), and with it set.
- [ ] No secret in repo; `git grep` for client secrets is clean.

## Risk Assessment

- **Stateless vs oauth2Login session**: mitigated by the two-chain split; the API chain never gets a
  session. Verify Chain A matcher does not swallow `/api/**`.
- **Facebook "verified" assumption**: FB only returns emails it considers valid, but to stay strict,
  treat FB email as verified only when present; still auto-link only on the locked verified rule.
- **Open-redirect via success handler**: redirect to a fixed server-side `app.frontend-url`, never to a
  user-supplied `redirect`/`returnUrl` param — avoids open redirect.
- **CSRF on callback**: OAuth2 `state` param covers the callback; CSRF filter disabled only on the
  `/oauth2/**` + `/login/oauth2/**` matchers, not on `/api/**`.
- **Concurrent first login** duplicate: unique constraint + catch-and-re-resolve (Phase 1 note).
