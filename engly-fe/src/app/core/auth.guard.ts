import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Generic authentication guard (CanActivateFn).
 * Awaits memoized /api/me load, then:
 *  - authenticated → allow.
 *  - anonymous     → redirect to /login (preserving returnUrl). Block navigation.
 *
 * UX only — backend /api/** endpoints enforce authentication server-side.
 */
export const authGuard: CanActivateFn = async (route, state): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);

  await auth.ensureLoaded();

  if (auth.isAuthenticated()) {
    return true;
  }

  return router.parseUrl('/login?returnUrl=' + encodeURIComponent(state.url));
};
