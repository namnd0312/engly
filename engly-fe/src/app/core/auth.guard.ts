import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Generic authentication guard (CanActivateFn).
 * Awaits memoized /api/me load, then:
 *  - authenticated → allow.
 *  - anonymous     → trigger OAuth2 login (full-page redirect); block navigation.
 *
 * UX only — backend /api/** endpoints enforce authentication server-side.
 */
export const authGuard: CanActivateFn = async (): Promise<boolean> => {
  const auth = inject(AuthService);

  await auth.ensureLoaded();

  if (auth.isAuthenticated()) {
    return true;
  }

  auth.login();
  return false;
};
