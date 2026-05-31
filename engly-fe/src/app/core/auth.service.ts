import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of, tap } from 'rxjs';

/** Identity projection returned by GET /api/me. */
export interface UserInfo {
  sub: string;
  email: string | null;
  name: string | null;
}

/**
 * BFF auth client (phase-05). Identity is backed by an httpOnly session cookie set by the
 * backend after the OAuth2 round-trip — no access/refresh tokens ever touch JS.
 *
 * - loadMe(): fetch current identity; any error (incl. 401) → anonymous, never throws.
 * - login()/logout(): full-page navigation so the browser follows the OAuth2 redirects.
 *   Angular auto-attaches X-XSRF-TOKEN to the logout POST from the XSRF-TOKEN cookie.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _user = signal<UserInfo | null>(null);
  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);

  /** Load current identity. 401/error → anonymous (null). Non-blocking, never throws. */
  loadMe(): void {
    this.http
      .get<UserInfo>('/api/me', { withCredentials: true })
      .pipe(
        tap((u) => this._user.set(u)),
        catchError(() => {
          this._user.set(null);
          return of(null);
        }),
      )
      .subscribe();
  }

  /** Start the OAuth2 Authorization Code flow via the BFF. */
  login(): void {
    this.navigate('/oauth2/authorization/opengate');
  }

  /** End the BFF session, clear local state, then return home. */
  logout(): void {
    this.http
      .post('/logout', null, { withCredentials: true })
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        this._user.set(null);
        this.navigate('/');
      });
  }

  /** Full-page navigation. Extracted so it can be spied on in unit tests. */
  protected navigate(url: string): void {
    window.location.href = url;
  }
}
