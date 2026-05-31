import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

/**
 * Attaches the admin bearer JWT to requests hitting the stateless admin API. The token is fetched
 * by AuthService during ensureLoaded() (which the admin guard awaits), so it's present by the time
 * any /api/admin call fires. Other requests pass through untouched.
 */
export const adminBearerInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.url.startsWith('/api/admin')) {
    const token = inject(AuthService).token();
    if (token) {
      req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
    }
  }
  return next(req);
};
