import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { throwError, catchError, switchMap } from 'rxjs';
import { AuthService } from './auth.service';

/** Paths that must never trigger a refresh-retry (would cause infinite loop or are pointless). */
const SKIP_REFRESH_PATHS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/me',
];

function shouldAttemptRefresh(req: HttpRequest<unknown>): boolean {
  if (!req.url.startsWith('/api/')) return false;
  return !SKIP_REFRESH_PATHS.some((path) => req.url.startsWith(path));
}

/**
 * On a 401 response for an /api/** request (excluding login, register, refresh, and /api/me):
 *  1. Call auth.refresh() once to rotate the access cookie.
 *  2. Retry the original request with the new cookie.
 *  3. If refresh itself fails → set anonymous state and let the error propagate so guards redirect
 *     to /login.
 *
 * Loop prevention: SKIP_REFRESH_PATHS excludes /api/auth/refresh, so the refresh call itself
 * never re-enters this interceptor's retry branch. The retry also passes through this interceptor
 * but any subsequent 401 propagates directly (no second refresh attempt) because the retried
 * request is not wrapped in catchError again.
 */
export const authRefreshInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
) => {
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (
        err instanceof HttpErrorResponse &&
        err.status === 401 &&
        shouldAttemptRefresh(req)
      ) {
        return auth.refresh().pipe(
          switchMap(() => next(req)),
          catchError((refreshErr: unknown) => {
            // Refresh failed — session is truly expired. Set anonymous so guards redirect.
            auth.logout();
            return throwError(() => refreshErr);
          }),
        );
      }
      return throwError(() => err);
    }),
  );
};
