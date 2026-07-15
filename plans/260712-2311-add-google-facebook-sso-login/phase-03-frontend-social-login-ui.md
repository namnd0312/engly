---
phase: 3
title: Frontend social login UI
status: completed
effort: S
priority: P2
dependencies:
  - 2
---

# Phase 3: Frontend social login UI

## Overview

Add "Sign in with Google" and "Sign in with Facebook" buttons to the login (and register) pages.
Because the flow is a full-page browser redirect, the FE just links out to the backend entry points —
no token handling in JS (cookies are httpOnly, set by the backend, exactly like password login).

## Requirements

- Functional:
  - Buttons navigate the browser to `/oauth2/authorization/google` / `.../facebook`.
  - After the backend redirects back to `/`, the SPA loads identity via the existing `/api/me` path.
  - Show a friendly error when the URL has `?error=sso` (failure handler redirect target).
- Non-functional: consistent with existing `login-page.component.ts` styling; accessible buttons;
  reuse `AuthService` — do NOT add a new HTTP client for this.

## Architecture

- Full-page navigation (`window.location.href = '/oauth2/authorization/google'`), NOT Angular router
  and NOT `HttpClient` — the OAuth redirect must be a top-level browser navigation so the provider
  cookies/redirect work.
- On return to `/`, the app shell already calls `AuthService.ensureLoaded()` → `/api/me`, which now
  succeeds because cookies are set. No new bootstrap code needed.
- Error surfacing: login page reads `?error=sso` query param and renders an inline message
  ("Đăng nhập bằng mạng xã hội thất bại. Vui lòng thử lại.").

## Related Code Files

- Modify: `engly-fe/src/app/features/auth/login-page.component.ts`
  - add a divider + two social buttons; add `loginWithProvider(p: 'google'|'facebook')` calling a small
    method on `AuthService`; read `?error=sso` and set `errorMessage`.
- Modify: `engly-fe/src/app/features/auth/register-page.component.ts`
  - same social buttons (registration and login are the same OAuth entry — social has no separate signup).
- Modify: `engly-fe/src/app/core/auth.service.ts`
  - add `loginWithProvider(provider: 'google' | 'facebook'): void` doing the protected `navigate(...)`
    full-page redirect (reuse the existing `navigate()` seam so it is unit-testable).
- Optional: extract the two buttons into a small `social-login-buttons.component.ts` if both pages use
  them (DRY) — only if it reduces real duplication.

## Implementation Steps

1. Add `loginWithProvider` to `AuthService` using the existing `navigate()` method
   (`this.navigate('/oauth2/authorization/' + provider)`).
2. Add buttons + "hoặc" divider to `login-page.component.ts`; wire `(click)`.
3. Read `route.snapshot.queryParamMap.get('error')`; if `sso`, set the error signal.
4. Mirror buttons on `register-page.component.ts`.
5. Keep provider brand styling minimal (text + optional inline SVG); no external asset/CDN (offline-safe).

## Success Criteria

- [ ] Google/Facebook buttons visible on login + register pages, keyboard-accessible.
- [ ] Click performs a full-page redirect to the backend OAuth entry point.
- [ ] After a successful round-trip the header shows the logged-in user (existing `/api/me` path).
- [ ] `?error=sso` renders a friendly inline message.
- [ ] Existing FE unit tests pass; new `loginWithProvider` covered via the `navigate()` spy.

## Risk Assessment

- **SPA-router vs full navigation**: using `HttpClient` or router navigation would break the OAuth
  redirect — enforced by using `window.location` through the `navigate()` seam.
- **Dev proxy single-origin**: `/oauth2` + `/login/oauth2` must proxy with `changeOrigin: false` so the
  whole flow (handshake session + issued auth cookies) stays on :4200 — see Phase 2 step 6 / Finding #4.
  With the default `changeOrigin: true`, SSO silently fails (session lost, cookies land on :8081).
- Low blast radius: additive UI, no change to existing password form behavior.
