# Research Report: Spring Security 6 + Angular 19 Auth Patterns for Study Feature

**Date:** 2026-06-01  
**Scope:** Authenticated-but-not-admin `/api/study/**` endpoint integration (Spring Boot 3.4.5 + Angular 19)

---

## Executive Summary

Engly's BFF security model (OAuth2 login → httpOnly session cookie) allows two integration patterns for the study feature:
1. **Recommended: Extend Chain 2 (session/BFF)** — simplest, reuses existing identity. Add `/api/study/**` matcher with `authenticated()` before `anyRequest()`.
2. **Alternative: Add Chain 3 (stateless JWT)** — if SPA needs bearer tokens for /api/study/** (future mobile/external clients).

**OidcUser principal is available in BFF session requests** via `@AuthenticationPrincipal OidcUser` or SecurityContext; `sub` (user ID) accessed as `OidcUser.getSubject()`.

---

## Current Security Setup Summary

### Chain 1 — Admin API (Bearer JWT, Stateless)
- **Path:** `/api/admin/**`
- **Auth:** `Authorization: Bearer <JWT>` (verified against OPEN-GATE JWK set)
- **Roles:** `hasRole('ADMIN')` via `roles` claim extraction
- **Session:** None (SessionCreationPolicy.STATELESS)

### Chain 2 — BFF / Public (Session)
- **Paths:** `/api/me`, `/api/token` → `authenticated()`; `/api/verbs/**` → `permitAll()`
- **Auth:** httpOnly session cookie after OAuth2 round-trip
- **Roles:** Extracted from id_token `roles` claim into authorities
- **Session:** Enabled (default JSESSIONID management)

### Identity Flow
1. Browser: GET `/oauth2/authorization/opengate` → OPEN-GATE OAuth2 login
2. OPEN-GATE → backend's `/login/oauth2/code/opengate` callback
3. OidcUserService decorator: reads id_token, extracts roles, builds OidcUser with authorities
4. Backend: set httpOnly JSESSIONID cookie, store OidcUser in session
5. SPA: subsequent requests auto-send JSESSIONID; server resolves OidcAuthenticationToken from session

---

## Pattern 1: Extend Chain 2 (Recommended)

### Why
- No new chain needed; session already established after login
- OidcUser identity persists for the SPA lifetime
- Simplest code path; leverages existing `/api/me` pattern

### Implementation

**SecurityConfig.java** — add matcher in Chain 2's `authorizeHttpRequests`:
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/verbs/**").permitAll()
    .requestMatchers("/oauth2/**", "/login/**").permitAll()
    .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
    .requestMatchers("/api/me", "/api/token").authenticated()
    .requestMatchers("/api/study/**").authenticated()  // ← NEW: any authenticated user
    .anyRequest().permitAll())
```

**Key ordering rule:** specific matchers BEFORE general. `/api/admin/**` is in Chain 1 (Order 1), so it matches first; this Chain 2 (Order 2) never sees admin requests.

### Server-side: Read User ID

**StudyController.java**:
```java
@GetMapping("/api/study/flashcards")
public ResponseEntity<?> getFlashcards(
    @AuthenticationPrincipal OidcUser user) {  // ← Spring auto-injects from SecurityContext
  String userId = user.getSubject();  // e.g. "user-123" from id_token "sub" claim
  // Query user's flashcards by userId
  return ResponseEntity.ok(...);
}
```

**Alternative (if needed):**
```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
OidcUser user = (OidcUser) auth.getPrincipal();
String userId = user.getClaims().get("sub").toString();
```

### Frontend: Generic Auth Guard

**auth.guard.ts** (reusable for `/study` routes):
```typescript
export const authGuard: CanActivateFn = async (): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);

  await auth.ensureLoaded();

  if (auth.isAuthenticated()) {
    return true;  // any logged-in user allowed
  }
  // anonymous → initiate OAuth2 login
  auth.login();
  return false;
};
```

**Routing:**
```typescript
{
  path: 'study',
  canActivate: [authGuard],  // ← reused guard
  children: [
    { path: 'flashcards', component: FlashcardsComponent }
  ]
}
```

---

## Pattern 2: Add Chain 3 (Stateless JWT)

### Why
- Future-proof: if mobile app or external client needs bearer token access to `/api/study/**`
- Decouples SPA from admin API pattern (separate concerns)
- Mirrors admin API architecture

### Implementation

**SecurityConfig.java** — add new Chain 3 (Order 1.5):
```java
@Bean
@Order(1)  // Admin (bearer JWT, stateless)
public SecurityFilterChain adminApiSecurityFilterChain(...) { ... }

@Bean
@Order(1)  // ← NEW Chain for authenticated endpoints (bearer JWT, stateless)
public SecurityFilterChain studyApiSecurityFilterChain(HttpSecurity http) throws Exception {
  http
    .securityMatcher("/api/study/**")
    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
    .oauth2ResourceServer(oauth2 -> oauth2
      .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
    .sessionManagement(session -> session
      .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .csrf(csrf -> csrf.disable());
  return http.build();
}
```

**Note:** `@Order(1)` for both admin & study means Spring applies both in declaration order (first admin, then study). If collision occurs (unlikely), reorder to `@Order(2)` for study.

**SPA Integration:**
- SPA calls `/api/me` (session) to fetch user + bearer JWT
- AuthService stores token (reuse admin token pattern OR fetch new one from `/api/study-token`)
- SPA attaches `Authorization: Bearer <token>` to `/api/study/**` calls

---

## Best Practices for BFF Cookie-Session Flow

### 1. OidcUser Availability
✓ **Confirmed:** OidcUser principal IS available in session-authenticated requests.
- SecurityContext holds OidcAuthenticationToken after successful login
- `@AuthenticationPrincipal OidcUser` works in controllers
- No special filter/converter needed (session session manager deserializes from HttpSession)

### 2. Sub Claim Extraction
✓ **Preferred:** `OidcUser.getSubject()` (OIDC standard, type-safe)
```java
String userId = user.getSubject();  // returns "sub" claim value
```

✗ **Avoid:** Raw claim access if possible
```java
String userId = (String) user.getClaims().get("sub");  // works but less safe
```

### 3. Matcher Ordering (Critical)
Longest/most-specific matchers FIRST:
```java
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.requestMatchers("/api/study/**").authenticated()
.requestMatchers("/api/**").permitAll()  // catch-all, last
```
Break: if `/api/**` comes before `/api/study/**`, the latter never matches.

### 4. Stateless vs. Session Trade-offs

| Aspect | Chain 2 (Session) | Chain 3 (Stateless JWT) |
|--------|-------------------|------------------------|
| Token source | httpOnly cookie | Bearer header |
| Revocation | Immediate (clear session) | Wait for expiry |
| Server memory | Stores session | Stateless (scalable) |
| Mobile-ready | No (cookies not portable) | Yes |
| Complexity | Lower | Higher (token mgmt) |

---

## Angular 19 Functional Guards

### Generic Auth Guard
```typescript
export const authGuard: CanActivateFn = async (): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);

  await auth.ensureLoaded();
  if (auth.isAuthenticated()) return true;
  auth.login();
  return false;
};
```

**Advantages over `adminGuard`:**
- No role check; any authenticated user passes
- Reusable for /study, /profile, etc.
- Defers identity load (ensureLoaded) before deciding

### Integration with Existing AuthService
No changes needed to AuthService (no-op):
- `ensureLoaded()` already fetches `/api/me` (contains isAuthenticated check)
- Can add optional `isStudyUser?: boolean` to UserInfo if tier-based access needed later

---

## Unresolved Questions

1. **Token Refresh:** Does OPEN-GATE IdP support refresh tokens in OIDC flow? If yes, should we rotate access tokens server-side for `/api/study` stateless JWT?
2. **Sub Claim Format:** Confirm OPEN-GATE's `sub` claim is always a string UUID/user ID (not complex object).
3. **Session Timeout:** What's the configured session timeout? Does it align with token expiry for Pattern 2?

---

## Recommendation

**Use Pattern 1 (Extend Chain 2)** for MVP:
- Minimal code changes (3-line matcher addition)
- Reuses existing OidcUser identity + session infrastructure
- Frontend: copy `adminGuard` → `authGuard` (remove role check)
- Backend: `@AuthenticationPrincipal OidcUser user` + `user.getSubject()` for user keying

**Revisit Pattern 2** if future roadmap requires:
- Mobile app with bearer token auth
- Micro-service consuming `/api/study` (needs token, not cookie)
- Separate token lifecycle from session lifetime
