---
phase: 4
title: Frontend enrollment and verify
status: completed
priority: P1
dependencies:
  - 3
effort: M
---

# Phase 4: Frontend enrollment and verify

## Overview
Angular side: a "Bảo mật" section on the profile page to enroll/disable TOTP (QR rendered client-side), a `/mfa/verify` page, and routing so an admin is steered to enrollment or verification instead of seeing a dead `/admin`. Follows the existing signal + standalone-component style of `login-page.component.ts` and `profile-page.component.ts`.

## Requirements
- Functional: enroll (QR + manual secret + confirm code), disable (code), verify (code → returnUrl); `adminGuard` redirects by MFA state; 403 `MFA_REQUIRED` from any admin API call redirects to verify.
- Non-functional: Vietnamese copy consistent with login page; no secret stored in `localStorage`; specs for guard, service and the two components; `npx ng test --watch=false --browsers=ChromeHeadless` green.

## Architecture
```
AuthService.user() ── mfaEnrolled / mfaVerified ──► adminGuard
                                                  │  !enrolled → /profile?mfa=required
                                                  │  !verified → /mfa/verify?returnUrl=
MfaApiService: enroll / confirm / disable / verify
ProfilePage ─ embeds ─► MfaSettingsComponent (off | enrolling | on)
MfaVerifyPage ─► MfaApiService.verify ─► auth.setUser(resp) ─► navigate(returnUrl)
authRefreshInterceptor: 403 + code MFA_REQUIRED on /api/admin/** ─► router → /mfa/verify
```

## Related Code Files
- Create: `engly-fe/src/app/features/auth/mfa-api.service.ts`
- Create: `engly-fe/src/app/features/auth/mfa-verify-page.component.ts` (+ `.spec.ts`)
- Create: `engly-fe/src/app/features/profile/mfa-settings.component.ts` (+ `.spec.ts`)
- Modify: `engly-fe/src/app/core/auth.service.ts` (`UserInfo` + 2 fields; no new methods needed beyond `setUser`)
- Modify: `engly-fe/src/app/features/admin/admin.guard.ts` (+ `.spec.ts`)
- Modify: `engly-fe/src/app/core/auth-refresh.interceptor.ts` (+ `.spec.ts`)
- Modify: `engly-fe/src/app/app.routes.ts` (route `mfa/verify`, `canActivate: [authGuard]`)
- Modify: `engly-fe/src/app/features/profile/profile-page.component.ts` / `.html` (import + embed `<app-mfa-settings />`, read `?mfa=required` to show a banner)
- Modify: `engly-fe/package.json` (`angularx-qrcode@^19`), then `npm install`
- Modify: every spec/fixture building a `UserInfo` literal (grep `isAdmin:` in `*.spec.ts`) to add `mfaEnrolled: false, mfaVerified: false`

## Implementation Steps
1. `npm i angularx-qrcode@^19` in `engly-fe`. Verify it builds with Angular 19.2 (`npx ng build`), otherwise fall back to `qrcode` + `<canvas>`.
2. `UserInfo`: add `mfaEnrolled: boolean; mfaVerified: boolean;`. Fix fixtures.
3. `MfaApiService` (`providedIn: 'root'`, mirrors `profile-api.service.ts`):
   - `enroll(): Observable<{secret: string; otpauthUri: string}>` → `POST /api/profile/mfa/enroll`
   - `confirm(code)` → `POST /api/profile/mfa/confirm` → `void`
   - `disable(code)` → `http.delete('/api/profile/mfa', { body: {code}, withCredentials: true })`
   - `verify(code): Observable<UserInfo>` → `POST /api/auth/mfa/verify`
   All with `withCredentials: true`.
4. `MfaSettingsComponent` (standalone, selector `app-mfa-settings`): state signal `'off' | 'enrolling' | 'on'` derived initially from `auth.user()?.mfaEnrolled`; `enrollment` signal holds `{secret, otpauthUri}`; `code` model; `error` signal mapping `INVALID_MFA_CODE` → "Mã không đúng", `MFA_LOCKED` → "Nhập sai quá nhiều lần, thử lại sau 15 phút". Buttons: Bật → `enroll()` → show `<qrcode [qrdata]="otpauthUri" [width]="180">` + secret in `<code>` + 6-digit input → Xác nhận → `confirm` → `auth.refreshMe()` → state `on`. Tắt → code input → `disable` → `auth.refreshMe()` → `off`. Input: `inputmode="numeric" pattern="\d{6}" maxlength="6" autocomplete="one-time-code"`.
5. `MfaVerifyPageComponent` (route `/mfa/verify`, guarded by `authGuard`): heading "Xác thực hai lớp", single code input, submit → `verify(code)` → `auth.setUser(user)` → `router.navigateByUrl(returnUrl ?? '/admin')`. Error mapping as above; 409 `MFA_NOT_ENROLLED` → navigate `/profile?mfa=required`.
6. `adminGuard`: after `ensureLoaded()`, when `isAuthenticated() && isAdmin()`:
   ```ts
   const u = auth.user()!;
   if (!u.mfaEnrolled) return router.parseUrl('/profile?mfa=required');
   if (!u.mfaVerified) return router.parseUrl('/mfa/verify?returnUrl=' + encodeURIComponent(state.url));
   return true;
   ```
   Keep the non-admin and anonymous branches as they are. Update the Javadoc ("backend `MfaStepUpFilter` is the real gate").
7. Interceptor: add a second branch in `catchError`: `err.status === 403 && err.error?.code === 'MFA_REQUIRED' && req.url.startsWith('/api/admin/')` → `router.navigateByUrl('/mfa/verify?returnUrl=' + encodeURIComponent(router.url))` then rethrow. `MFA_ENROLLMENT_REQUIRED` → `/profile?mfa=required`. Inject `Router` via `inject()`. Do not touch the 401 branch.
8. Profile page: inject `ActivatedRoute`; if `queryParamMap.get('mfa') === 'required'` show a banner "Tài khoản quản trị bắt buộc bật xác thực hai lớp" above the section. Embed `<app-mfa-settings />` under the existing card.
9. Routes: add
   ```ts
   { path: 'mfa/verify', canActivate: [authGuard],
     loadComponent: () => import('./features/auth/mfa-verify-page.component').then(m => m.MfaVerifyPageComponent) },
   ```
10. Specs: `admin.guard.spec.ts` (3 new cases: admin not enrolled, admin enrolled not verified, admin verified); `auth-refresh.interceptor.spec.ts` (403 MFA_REQUIRED redirects, 403 other code does not); `mfa-verify-page.component.spec.ts` (success navigates to returnUrl, 400 shows error); `mfa-settings.component.spec.ts` (off→enrolling shows QR, confirm→on).
11. `npx ng test --watch=false --browsers=ChromeHeadless` and `npx ng build`.

## Success Criteria
- [ ] Admin login (password and Google) → `/admin` redirects to profile (not enrolled) then to `/mfa/verify` (enrolled), then renders admin
- [ ] Regular user sees the Bảo mật section but nothing forces them through it
- [ ] Refreshing the browser after verify keeps admin access (flag survives via refresh rotation)
- [ ] All FE specs green; no `any` leakage in new code

## Risk Assessment
- `angularx-qrcode` peer-dep mismatch with Angular 19.2 → check at step 1, fallback named.
- Double redirect loop if `/api/me` is stale after verify → `verify` response is applied with `setUser` before navigating; guard reads the signal, not the network.
- Interceptor redirect firing during the verify page itself → the branch is limited to `/api/admin/` URLs.
