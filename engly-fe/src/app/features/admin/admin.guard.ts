import { inject } from '@angular/core';
import { Router, CanActivateFn, UrlTree } from '@angular/router';
import { AuthService } from '../../core/auth.service';

/**
 * Admin route guard. Awaits the memoized /api/me load so it never decides before identity is
 * known, then:
 *  - admin            → allow.
 *  - authenticated, non-admin → redirect to /forbidden (explicit "no permission" page).
 *  - anonymous        → redirect to /login (preserving returnUrl); block this navigation.
 *
 * UX only — the backend hasRole('ADMIN') rule is the real gate.
 */
export const adminGuard: CanActivateFn = async (route, state): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);

  await auth.ensureLoaded();

  if (auth.isAuthenticated() && auth.isAdmin()) {
    return true;
  }
  if (auth.isAuthenticated()) {
    return router.parseUrl('/forbidden');
  }
  return router.parseUrl('/login?returnUrl=' + encodeURIComponent(state.url));
};
