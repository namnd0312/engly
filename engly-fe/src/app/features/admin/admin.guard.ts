import { inject } from '@angular/core';
import { Router, CanActivateFn, UrlTree } from '@angular/router';
import { AuthService } from '../../core/auth.service';

/**
 * Admin route guard. Awaits the memoized /api/me load so it never decides before identity is
 * known, then:
 *  - admin, 2FA enrolled + verified this session → allow.
 *  - admin, not enrolled  → redirect to /profile?mfa=required (must enable TOTP first).
 *  - admin, not verified  → redirect to /mfa/verify (preserving returnUrl).
 *  - authenticated, non-admin → redirect to /forbidden (explicit "no permission" page).
 *  - anonymous        → redirect to /login (preserving returnUrl); block this navigation.
 *
 * UX only — the backend hasRole('ADMIN') rule plus MfaStepUpFilter are the real gate.
 */
export const adminGuard: CanActivateFn = async (route, state): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);

  await auth.ensureLoaded();

  if (auth.isAuthenticated() && auth.isAdmin()) {
    const user = auth.user();
    if (!user?.mfaEnrolled) {
      return router.parseUrl('/profile?mfa=required');
    }
    if (!user.mfaVerified) {
      return router.parseUrl('/mfa/verify?returnUrl=' + encodeURIComponent(state.url));
    }
    return true;
  }
  if (auth.isAuthenticated()) {
    return router.parseUrl('/forbidden');
  }
  return router.parseUrl('/login?returnUrl=' + encodeURIComponent(state.url));
};
