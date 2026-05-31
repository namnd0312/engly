# Phase 01 — Backend Security / RBAC

## Context Links
- Research: `research/researcher-01-backend-security-jpa.md` (§1 role mapping, §2 URL security, §3 /api/me isAdmin)
- Code: `engly-be/.../common/SecurityConfig.java`, `engly-be/.../identity/api/SessionController.java`,
  `engly-be/src/main/resources/application.yml`, `engly-be/.../common/SecurityRulesTest.java`
- Docs: `docs/system-architecture.md` (SSO/BFF section)

## Overview
- **Date:** 2026-05-31
- **Description:** Map an OPEN-GATE OIDC roles claim → `ROLE_ADMIN` authority, lock `/api/admin/**`
  to `hasRole('ADMIN')` (keep `/api/verbs/**` public), and expose `isAdmin` from `/api/me`.
- **Priority:** P1 (blocks phases 02/03)
- **Implementation status:** complete
- **Review status:** complete

## Key Insights
- `oauth2Login`, PKCE, CSRF (`CookieCsrfTokenRepository` + `CsrfCookieFilter`) already wired — do NOT
  re-add. Only add a role-mapping `OidcUserService` bean + one URL matcher + extend `/api/me`.
- Registration id is `opengate`. With no `/userinfo`, identity (and roles) come from the **id_token**.
- `hasRole('ADMIN')` checks for authority `ROLE_ADMIN` — prefix manually.
- Roles claim name MUST be configurable (KEY UNKNOWN — see plan Unresolved Questions).

## Requirements
- Functional: authenticated user whose id_token roles claim contains the admin role gets `ROLE_ADMIN`;
  `/api/admin/**` requires it; `/api/me` returns `isAdmin`.
- Non-functional: offline-safe startup preserved (no issuer-uri added); no secrets committed; KISS —
  URL-based authz, no `@EnableMethodSecurity`.

## Architecture
```
id_token (OPEN-GATE)
  └─ claim "roles" (configurable) → OidcRoleMappingConfig.oidcUserService
       └─ map each role → "ROLE_" + role.toUpperCase() → GrantedAuthority
SecurityConfig.authorizeHttpRequests:
  /api/verbs/**  permitAll (unchanged)
  /api/admin/**  hasRole('ADMIN')   ← NEW (place BEFORE anyRequest)
SessionController.me() → adds isAdmin = authorities contains ROLE_ADMIN
```

## Related Code Files
**Create:**
- `engly-be/src/main/java/com/engly/common/OidcRoleMappingConfig.java` (~45 lines)

**Modify:**
- `engly-be/src/main/java/com/engly/common/SecurityConfig.java` — add `/api/admin/**` matcher; wire
  `oidcUserService` into `.oauth2Login(... .userInfoEndpoint(u -> u.oidcUserService(bean)) ...)`.
- `engly-be/src/main/java/com/engly/identity/api/SessionController.java` — add `isAdmin` to record + computation.
- `engly-be/src/main/resources/application.yml` — add `app.security.roles-claim-name: roles`.

**Delete:** none.

## Implementation Steps
1. Add config key to `application.yml` (under existing root, alongside `app.frontend-url` usage):
   ```yaml
   app:
     security:
       roles-claim-name: ${ROLES_CLAIM_NAME:roles}
   ```
2. Create `OidcRoleMappingConfig.java`:
   ```java
   @Configuration
   public class OidcRoleMappingConfig {
     @Bean
     OidcUserService oidcUserService(@Value("${app.security.roles-claim-name:roles}") String claim) {
       var delegate = new OidcUserService();
       return new OidcUserService() {
         @Override public OidcUser loadUser(OidcUserRequest req) {
           OidcUser user = delegate.loadUser(req);
           Set<GrantedAuthority> auths = new HashSet<>(user.getAuthorities());
           Object raw = user.getClaims().get(claim);            // KEY UNKNOWN: shape
           extractRoles(raw).forEach(r ->
             auths.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())));
           return new DefaultOidcUser(auths, user.getIdToken(), user.getUserInfo());
         }
       };
     }
     // tolerate List<String> or space/comma string; ignore other shapes (log-friendly)
     private static List<String> extractRoles(Object raw) {
       if (raw instanceof Collection<?> c)
         return c.stream().map(String::valueOf).toList();
       if (raw instanceof String s && !s.isBlank())
         return Arrays.stream(s.split("[ ,]+")).filter(x -> !x.isBlank()).toList();
       return List.of();
     }
   }
   ```
   NOTE: keep the `user-name-attribute: sub` (already set) so `DefaultOidcUser` resolves the name key.
3. In `SecurityConfig.securityFilterChain`, add the admin rule BEFORE `.anyRequest()`:
   ```java
   .requestMatchers("/api/verbs/**").permitAll()
   .requestMatchers("/api/admin/**").hasRole("ADMIN")   // NEW
   .requestMatchers("/oauth2/**", "/login/**").permitAll()
   ```
   and inject the bean into the chain method signature, wiring it:
   ```java
   .oauth2Login(oauth2 -> oauth2
       .authorizationEndpoint(a -> a.authorizationRequestResolver(pkceResolver(repo)))
       .userInfoEndpoint(u -> u.oidcUserService(oidcUserService))   // NEW
       .defaultSuccessUrl(frontendUrl, true))
   ```
   (Add `OidcUserService oidcUserService` param to the `securityFilterChain` method; Spring injects the bean.)
4. Extend `SessionController`:
   ```java
   public record UserInfoResponse(String sub, String email, String name, boolean isAdmin) {}
   @GetMapping("/api/me")
   public UserInfoResponse me(@AuthenticationPrincipal OidcUser p) {
     boolean isAdmin = p.getAuthorities().stream()
         .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
     return new UserInfoResponse(p.getSubject(), p.getEmail(), p.getFullName(), isAdmin);
   }
   ```
5. Compile: `cd engly-be && ./mvnw -q -DskipTests compile`.
6. **Live verify (after IdP available):** log in, decode the id_token, confirm the actual roles claim
   name/shape; adjust `roles-claim-name` / `extractRoles` if needed (see Unresolved Question 1).

## Todo List
- [ ] Add `app.security.roles-claim-name` to `application.yml`
- [ ] Create `OidcRoleMappingConfig` with `OidcUserService` bean + tolerant `extractRoles`
- [ ] Add `/api/admin/**` → `hasRole('ADMIN')` matcher in `SecurityConfig`
- [ ] Wire `oidcUserService` into `.oauth2Login().userInfoEndpoint(...)`
- [ ] Add `isAdmin` to `SessionController.UserInfoResponse` + computation
- [ ] `./mvnw -q -DskipTests compile` passes
- [ ] (deferred) Inspect real id_token to confirm claim shape

## Success Criteria
- Compiles clean. `/api/admin/**` returns 401 (anon) / 403 (authenticated non-admin); admin OIDC
  principal passes. `/api/me` JSON includes `isAdmin`. Public `/api/verbs/**` behavior unchanged.

## Risk Assessment
- **Claim shape mismatch** (HIGH likelihood, see KEY UNKNOWN) → admin never gets ROLE_ADMIN. Mitigation:
  tolerant `extractRoles`, configurable name, live token inspection step.
- **Matcher ordering** → `anyRequest().permitAll()` would shadow admin rule if placed before. Mitigation:
  put admin matcher before `anyRequest()`.
- **`DefaultOidcUser` ctor** needs name attribute key when `user-name-attribute` set — using the
  3-arg ctor (authorities, idToken, userInfo) keeps the existing `sub` mapping.

## Security Considerations
- No DB roles; authority derives solely from a validated id_token (JWK-verified by Spring).
- ROLE_ prefix + uppercase normalization prevents `hasRole` mismatches.
- No secret/claim values logged.

## Next Steps
- Enables phase 02 (admin endpoints reachable) and phase 03 (`/api/me.isAdmin`).
