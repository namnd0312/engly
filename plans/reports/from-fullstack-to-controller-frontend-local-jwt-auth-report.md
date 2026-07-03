# Frontend Local JWT Auth Migration Report

## Files Created
- `src/app/core/auth-refresh.interceptor.ts` — functional HttpInterceptorFn; 401-retry with refresh
- `src/app/core/auth-refresh.interceptor.spec.ts` — 7 specs covering retry, skip-paths, error propagation
- `src/app/features/auth/login-page.component.ts` — standalone login form + OpenGate SSO button
- `src/app/features/auth/register-page.component.ts` — standalone registration form

## Files Modified
- `src/app/core/auth.service.ts` — removed `_token`/`/api/token`; added `loginWithPassword`, `register`, `loginWithOpenGate`, `refresh`; `logout` now POSTs `/api/auth/logout`
- `src/app/core/auth.service.spec.ts` — full rewrite for new API (13 specs)
- `src/app/core/auth.guard.ts` — anonymous → `router.parseUrl('/login?returnUrl=...')` instead of `auth.login()`
- `src/app/core/auth.guard.spec.ts` — updated: asserts UrlTree to /login, not loginCalls
- `src/app/features/admin/admin.guard.ts` — same anonymous redirect change
- `src/app/features/admin/admin.guard.spec.ts` — updated: asserts UrlTree contains /login
- `src/app/app.config.ts` — removed `adminBearerInterceptor`; added `authRefreshInterceptor`
- `src/app/app.routes.ts` — added `/login` and `/register` lazy routes
- `src/app/app.component.html` — "Login" button text changed to "Đăng nhập"
- `src/app/app.component.ts` — `login()` → `goToLogin()` (navigates to `/login`)
- `src/app/features/admin/vocab-import-page.component.ts` — removed `auth.token()` call; removed `AuthService` import
- `src/app/features/admin/vocab-import-page.component.spec.ts` — removed token stub; updated upload assertion
- `src/app/features/admin/data/tus-uploader.service.ts` — removed `token` from `TusUploadOptions`; removed bearer header; same-origin XHR sends cookies natively
- `src/app/features/profile/profile-page.component.ts` — `auth.login()` → `router.navigate(['/login'])`
- `src/app/features/admin/data/admin-deck-api.service.spec.ts` — fixed pre-existing `DeckRequest` missing `slug` field

## Files Deleted
- `src/app/core/admin-bearer.interceptor.ts`
- `src/app/core/admin-bearer.interceptor.spec.ts`

## AuthService Public API (final)

```typescript
// Signals
readonly user: Signal<UserInfo | null>
readonly isAuthenticated: Signal<boolean>
readonly isAdmin: Signal<boolean>

// Identity load (memoized)
ensureLoaded(): Promise<void>
loadMe(): void

// Credential auth
loginWithPassword(email: string, password: string): Observable<UserInfo>
register(email: string, password: string, displayName: string): Observable<UserInfo>

// SSO
loginWithOpenGate(): void  // full-page navigate to /oauth2/authorization/opengate

// Session management
refresh(): Observable<unknown>  // used by interceptor only
logout(): void  // POSTs /api/auth/logout, clears state, navigates '/'
```

## Refresh Interceptor Loop Prevention

Three independent guards:

1. **Path exclusion**: `SKIP_REFRESH_PATHS` = `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh`, `/api/me` — these never trigger a retry.
2. **Non-/api passthrough**: requests not starting with `/api/` bypass the catch entirely.
3. **Single retry**: the retried request is issued via `next(req)` without another `catchError` wrapping, so any subsequent 401 on the retry propagates directly — no second refresh attempt.

The refresh POST itself is excluded by path, so it cannot re-enter the retry branch even in degenerate configurations.

## Build + Test Results

- `npx tsc -p tsconfig.app.json --noEmit`: **PASS** (0 errors)
- `npx ng test --watch=false --browsers=ChromeHeadless`: **133/133 PASS**

## Deviations from Contract

- `tus-uploader.service.ts` — `TusUploadOptions.token` removed (was not in the migration spec but was a direct consequence of removing `AuthService.token`). Same-origin XHR sends the httpOnly access cookie automatically; no `withCredentials` option needed because the installed tus-js-client version does not expose it in its TypeScript types. Auth is still enforced server-side.
- `profile-page.component.ts` and `admin-deck-api.service.spec.ts` — touched to fix compile-time errors caused by the deletion of `auth.login()` and the pre-existing `slug` omission, respectively. No behavioral changes beyond redirecting to `/login`.

---

Status: DONE
Summary: All 8 contract items implemented — new auth service API, refresh interceptor with loop prevention, login/register pages, guard redirects, header update. 133 tests pass, build clean.
