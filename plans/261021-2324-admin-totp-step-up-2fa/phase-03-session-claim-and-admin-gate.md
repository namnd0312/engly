---
phase: 3
title: Session claim and admin gate
status: completed
priority: P1
dependencies:
  - 2
effort: M
---

# Phase 3: Session claim and admin gate

## Overview
Wire MFA into the session: `mfa` claim in the access JWT, persistence of the step-up across refresh rotation, the `/api/admin/**` gate, the four HTTP endpoints, and the two new `/api/me` fields. After this phase the feature is complete and testable with curl; the SPA is phase 4.

## Requirements
- Functional: see "Key contracts" in `plan.md`. Admin without `mfa=true` gets a JSON 403 telling the SPA what to do next. Non-admin behavior on `/api/admin/**` unchanged (plain 403).
- Non-functional: `/api/auth/mfa/verify` is not added to the CSRF ignore list, but (like every cookie-bearer endpoint in this app) the resource server skips CSRF for bearer requests; SameSite=Lax is the mitigation — see `SecurityRulesTest` note. SSO handler untouched; stateless chain unchanged.

## Architecture
```
login/SSO ─► issueSession() ─► access JWT {mfa:false}, refresh_token.mfa_verified=false
POST /api/auth/mfa/verify {code}
   ├─ MfaService.verify(user, code)
   ├─ refresh cookie → RefreshToken.markMfaVerified()
   └─ re-mint access JWT {mfa:true} (refresh cookie untouched)
POST /api/auth/refresh ─► rotate(): new RefreshToken inherits mfa_verified; access JWT mfa = that flag
GET /api/admin/** ─► BearerTokenAuthenticationFilter ─► MfaStepUpFilter ─► hasRole(ADMIN)
   MfaStepUpFilter: authenticated AND ROLE_ADMIN AND claim mfa != true
       → 403 {code: MFA_REQUIRED | MFA_ENROLLMENT_REQUIRED}, chain stops
```
A filter (not an `AuthorizationManager`) is used because the response must distinguish enrolled vs not-enrolled, which needs a DB lookup and a JSON body; the existing `hasRole("ADMIN")` rule stays as the real authority check.

## Related Code Files
- Create: `engly-be/src/main/java/com/engly/identity/api/MfaController.java`
- Create: `engly-be/src/main/java/com/engly/identity/api/dto/MfaCodeRequest.java` (`@NotBlank @Pattern(regexp="\\d{6}") String code`)
- Create: `engly-be/src/main/java/com/engly/identity/api/dto/MfaEnrollResponse.java` (`secret`, `otpauthUri`)
- Create: `engly-be/src/main/java/com/engly/identity/application/MfaStepUpFilter.java`
- Create: `engly-be/src/test/java/com/engly/identity/api/MfaControllerTest.java` (`@WebMvcTest` style used by `verb/VerbControllerTest`, if the identity controllers have an equivalent; otherwise cover in phase 5 integration test)
- Modify: `engly-be/src/main/java/com/engly/identity/application/AuthTokenService.java`
- Modify: `engly-be/src/main/java/com/engly/common/SecurityConfig.java`
- Modify: `engly-be/src/main/java/com/engly/identity/api/SessionController.java`
- Modify: `engly-be/src/main/java/com/engly/identity/api/dto/UserInfoResponse.java`
- Modify: `engly-be/src/main/java/com/engly/identity/application/ProfileService.java` (`toUserInfo` signature gains the two flags)
- Modify: existing tests that construct `UserInfoResponse` positionally (grep `new UserInfoResponse(`)

## Implementation Steps
1. `AuthTokenService`:
   - `mintAccessToken(user, now, boolean mfa)` adds `.claim("mfa", mfa)`.
   - `createRefreshToken(user, now, boolean mfaVerified)` passes the flag to the entity.
   - `issueSession` → both false.
   - `rotate`: `boolean mfa = token.isMfaVerified();` after revoke → `mintAccessToken(user, now, mfa)` and `createRefreshToken(user, now, mfa)`.
   - New `@Transactional void upgradeToMfa(HttpServletRequest req, HttpServletResponse res, UserAccount user)`: read refresh cookie (same `readCookie`), look up by hash, must be active and belong to `user.getId()` (else `InvalidLoginException` → 401), `token.markMfaVerified()`, `writeAccessCookie(res, mintAccessToken(user, now, true))`. Refresh cookie is not rotated here.
2. `UserInfoResponse`: add `boolean mfaEnrolled, boolean mfaVerified` at the end. `from(user)` → `(…, false, false)` (auth endpoints: a fresh session is never verified; enrolled state is hydrated by the SPA's follow-up `/api/me`, same pattern as avatar URLs — document this in the Javadoc). Update `ProfileService.toUserInfo(user, isAdmin, mfaEnrolled, mfaVerified)` and its callers.
3. `SessionController.me`: `mfaVerified = Boolean.TRUE.equals(jwt.getClaim("mfa"))`; `mfaEnrolled = mfaService.isEnrolled(user.getId())` (false in the orElseGet branch). Inject `MfaService`.
4. `MfaController` (`@RestController`, no class-level mapping because paths span two prefixes):
   - `POST /api/profile/mfa/enroll` → resolve user from `jwt.getSubject()` via `UserAccountRepository.findBySub` (same as `ProfileService`), return `MfaEnrollResponse`.
   - `POST /api/profile/mfa/confirm` body `MfaCodeRequest` → 204.
   - `DELETE /api/profile/mfa` body `MfaCodeRequest` → 204 (DELETE with body is fine with Angular `http.delete(url, {body})`).
   - `POST /api/auth/mfa/verify` body `MfaCodeRequest` → `mfaService.verify(user, code)`; `tokenService.upgradeToMfa(req, res, user)`; return `UserInfoResponse` with `mfaEnrolled=true, mfaVerified=true` (so the SPA can `setUser` without a round-trip).
5. `SecurityConfig` (api chain):
   - Insert `.requestMatchers("/api/auth/mfa/**").authenticated()` **before** `.requestMatchers("/api/auth/**").permitAll()` (first match wins).
   - Register `MfaStepUpFilter` with `.addFilterAfter(mfaStepUpFilter, BearerTokenAuthenticationFilter.class)`. Inject it as a bean (constructor: `MfaService`, `UserAccountRepository`, `ObjectMapper`).
   - Leave `/api/admin/**` as `hasRole("ADMIN")`.
6. `MfaStepUpFilter extends OncePerRequestFilter`:
   - `shouldNotFilter` → `!request.getRequestURI().startsWith("/api/admin/")`.
   - In `doFilterInternal`: `auth = SecurityContextHolder.getContext().getAuthentication()`; if not a `JwtAuthenticationToken` with `ROLE_ADMIN` → continue chain. If `Boolean.TRUE.equals(jwt.getClaim("mfa"))` → continue. Else: look up user by `jwt.getSubject()`, `code = mfaService.isEnrolled(id) ? "MFA_REQUIRED" : "MFA_ENROLLMENT_REQUIRED"`, write 403 JSON `{status:403, message:"Second factor required", code}` with the same key set `GlobalExceptionHandler.buildResponse` uses, `application/json`, and return without calling the chain.
7. Tests to update/add now: any test constructing `UserInfoResponse`; `AuthFlowIntegrationTest` assertions still hold (new fields are additive). Add `MfaStepUpFilterTest` (unit: mock chain, three branches) if quick; full flow coverage lands in phase 5.
8. Manual smoke with curl (document in the phase report): login as admin → `GET /api/admin/decks` 403 `MFA_ENROLLMENT_REQUIRED` → enroll → confirm with a code from an authenticator (or compute via `TotpCodec` in a jshell) → `GET` 403 `MFA_REQUIRED` → verify → 200 → refresh → 200.

## Success Criteria
- [ ] `rotate()` preserves `mfa=true`; `issueSession()` always yields `mfa=false`
- [ ] Non-admin hitting `/api/admin/**` still gets the plain 403 from `hasRole`
- [ ] `/api/auth/mfa/verify` without session → 401
- [ ] `./mvnw test` green including the updated `UserInfoResponse` call sites

## Risk Assessment
- Filter ordering: must run after `BearerTokenAuthenticationFilter` or the context is empty → assert in a test that an admin with `mfa=false` gets the custom body, not a bare 403.
- Access JWT minted with `mfa=true` outlives a `disable` by ≤15 min — same accepted property as logout (audit L2). Note in Javadoc.
- `DELETE` with a JSON body: Spring handles it; if a proxy strips it later, switch to `POST /api/profile/mfa/disable`. Keep DELETE for now (REST-consistent).
