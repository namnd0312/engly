import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of, switchMap, tap } from 'rxjs';

/** Identity projection returned by GET /api/me. */
export interface UserInfo {
  sub: string;
  email: string | null;
  name: string | null;
  isAdmin: boolean;
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
  readonly isAdmin = computed(() => this._user()?.isAdmin ?? false);

  // Admin bearer JWT (id_token) fetched from the BFF so the SPA can call the stateless admin API.
  // Read by adminBearerInterceptor. Null for non-admins / anonymous.
  private readonly _token = signal<string | null>(null);
  readonly token = this._token.asReadonly();

  /**
   * Memoized identity load — resolves once /api/me settles (success OR error). The admin guard
   * awaits this so it never decides before identity is known; the promise is reused on later calls.
   */
  private loadPromise?: Promise<void>;

  ensureLoaded(): Promise<void> {
    if (!this.loadPromise) {
      this.loadPromise = new Promise<void>((resolve) => {
        this.http
          .get<UserInfo>('/api/me', { withCredentials: true })
          .pipe(
            tap((u) => this._user.set(u)),
            // Admins additionally fetch a bearer JWT for the stateless admin API. The guard
            // awaits ensureLoaded(), so the token is ready before any /admin call runs.
            switchMap((u) =>
              u.isAdmin
                ? this.http.get<{ token: string }>('/api/token', { withCredentials: true }).pipe(
                    tap((t) => this._token.set(t.token)),
                    catchError(() => of(null)),
                  )
                : of(null),
            ),
            catchError(() => {
              this._user.set(null);
              this._token.set(null);
              return of(null);
            }),
          )
          .subscribe(() => resolve());
      });
    }
    return this.loadPromise;
  }

  /** Load current identity. 401/error → anonymous (null). Non-blocking, never throws. */
  loadMe(): void {
    void this.ensureLoaded();
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
        this._token.set(null);
        // Drop the memoized load so a later session re-fetches /api/me (defensive: matters
        // if logout is ever switched from a full-page reload to in-SPA navigation).
        this.loadPromise = undefined;
        this.navigate('/');
      });
  }

  /** Full-page navigation. Extracted so it can be spied on in unit tests. */
  protected navigate(url: string): void {
    window.location.href = url;
  }
}
