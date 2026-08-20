---
phase: 3
title: "Frontend activation flow"
status: completed
effort: "M"
---

# Phase 3: Frontend activation flow

## Overview

Match the new backend contract in the Angular SPA: registration no longer logs
in — it shows a "check your inbox" state; a new `/verify-email` page consumes the
token link; login handles the 403 unverified case with a "resend activation email"
action.

## Requirements

- Functional: register → confirmation screen (no redirect to `/`); `/verify-email?token=`
  activates + lands logged in; login 403 → inline message + resend button.
- Non-functional: keep Vietnamese copy consistent with existing auth pages; reuse
  the existing `.auth-page` styling.

## Architecture

- `AuthService.register` stops setting `_user` (no session now) and returns the
  `{email}` confirmation. New `verifyEmail(token)` and `resendVerification(email)`
  methods. New route `/verify-email` → `VerifyEmailPageComponent`.

## Related Code Files

- Create: `engly-fe/src/app/features/auth/verify-email-page.component.ts`
- Modify: `engly-fe/src/app/core/auth.service.ts`
- Modify: `engly-fe/src/app/features/auth/register-page.component.ts`
- Modify: `engly-fe/src/app/features/auth/login-page.component.ts`
- Modify: `engly-fe/src/app/app.routes.ts` (add `/verify-email` — use `loadComponent`, matching all existing routes)
- Modify: `engly-fe/src/app/core/auth-refresh.interceptor.ts` (add the two new auth paths to `SKIP_REFRESH_PATHS`)

## Implementation Steps

1. **`auth.service.ts`**:
   - `register(...)`: change endpoint call to expect `{ email: string }` (202); **remove**
     the `_user.set(u)` / `refreshMe()` side effects (no session issued now). Return
     the observable so the component can show the confirmation.
   - Add `verifyEmail(token: string): Observable<UserInfo>` → `POST /api/auth/verify-email`
     `{ token }` with `withCredentials`; on success `_user.set(u)` + background
     `refreshMe()` (mirrors `loginWithPassword`).
   - Add `resendVerification(email: string): Observable<void>` →
     `POST /api/auth/resend-verification` `{ email }`.

2. **`register-page.component.ts`**: on success, instead of
   `router.navigate(['/'])`, switch the view to a confirmation state (signal
   `submitted = true`) showing "Chúng tôi đã gửi email kích hoạt tới {{email}}.
   Vui lòng kiểm tra hộp thư." with a "Gửi lại email" link that calls
   `resendVerification(email)`. Keep the existing 409/400 error handling.

3. **`verify-email-page.component.ts`** (route `/verify-email`):
   - Read `token` from the query params (`ActivatedRoute`).
   - **If already authenticated (`auth.isAuthenticated()`), short-circuit to `success`
     WITHOUT calling verify** — prevents a page refresh / bfcache re-mount from re-POSTing
     an already-consumed token and flashing a false "expired" error, and avoids the
     login-CSRF surface (Phase 2 step 4).
   - **On load, strip the token from the URL** via `history.replaceState` (or
     `router.navigate([], {queryParams:{}})`) so the secret does not linger in history /
     leak via `Referer`. Also emit a `<meta name="referrer" content="no-referrer">` (or
     set it on this route) so the verify page never forwards the token in a Referer header.
   - States: `verifying` (default, calls `auth.verifyEmail(token)` once on init),
     `success` (redirect to `/` after a short beat or a "Vào ứng dụng" button),
     `error` (invalid/expired 400 → message + a resend form asking for email; copy notes
     that **only the most recent activation link is valid**, since resend invalidates
     older links).
   - Missing token → immediate error state. Guard against double-init (fire verify exactly
     once).

4. **`login-page.component.ts`**: in the login error handler, branch on
   `err.status === 403` (and optionally `err.error?.code === 'EMAIL_NOT_VERIFIED'`):
   show "Tài khoản chưa được kích hoạt" + a "Gửi lại email kích hoạt" button that
   calls `auth.resendVerification(email)` with the entered email. Keep 401 generic.

5. **`app.routes.ts`**: add a **lazy** route matching the existing convention (all 7
   current routes use `loadComponent`, none eager):
   `{ path: 'verify-email', loadComponent: () => import('./features/auth/verify-email-page.component').then(m => m.VerifyEmailPageComponent) }`
   (public, no guard).

6. **`auth-refresh.interceptor.ts`**: add `/api/auth/verify-email` and
   `/api/auth/resend-verification` to `SKIP_REFRESH_PATHS`. They never 401 today, but the
   list must stay complete so a future change can't trigger a refresh→logout loop on these
   unauthenticated POSTs (consistent with `login`/`register`/`refresh` already skipped).

## Success Criteria

- [ ] Registering shows the "check your inbox" confirmation and does NOT navigate to `/`.
- [ ] Visiting `/verify-email?token=<valid>` activates and lands the user logged in.
- [ ] Visiting `/verify-email?token=<bad>` shows the expired/invalid state + resend.
- [ ] Refreshing `/verify-email` after a successful activation shows success (not a false
      "expired"), because the authenticated short-circuit skips re-POST.
- [ ] The token is removed from the URL after the page loads (not left in history).
- [ ] Login with an unverified account shows the activation message + working resend.
- [ ] `npx ng build` (or `ng test`) compiles with no type errors.

## Risk Assessment

- **Silent no-session regression**: since `register` no longer sets `_user`, any
  code assuming an authenticated state post-register must be checked. Search usages
  of `auth.register(` — currently only `register-page.component.ts`.
- **Resend UX enumeration**: backend returns 202 always; FE must show a neutral
  "nếu email hợp lệ, chúng tôi đã gửi lại" message, not "email sent to X".
