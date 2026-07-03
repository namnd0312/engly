import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, of, tap } from 'rxjs';

/** Identity projection returned by GET /api/me. */
export interface UserInfo {
  sub: string;
  email: string | null;
  name: string | null;
  isAdmin: boolean;
}

/**
 * App-owned JWT auth client. Identity is backed by httpOnly cookies (access + refresh) set by
 * the backend. Tokens never touch JS. CSRF is handled automatically via X-XSRF-TOKEN.
 *
 * - ensureLoaded(): memoized GET /api/me; any error → anonymous, never throws.
 * - loginWithPassword() / register(): POST credentials, receive identity in response.
 * - loginWithOpenGate(): full-page navigate to start the OAuth2 SSO flow.
 * - refresh(): POST /api/auth/refresh — used by the auth-refresh interceptor.
 * - logout(): POST /api/auth/logout, clear state, navigate home.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _user = signal<UserInfo | null>(null);
  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);
  readonly isAdmin = computed(() => this._user()?.isAdmin ?? false);

  /**
   * Memoized identity load — resolves once /api/me settles (success OR error). Guards await this
   * so they never decide before identity is known; the promise is reused on later calls.
   */
  private loadPromise?: Promise<void>;

  ensureLoaded(): Promise<void> {
    if (!this.loadPromise) {
      this.loadPromise = new Promise<void>((resolve) => {
        this.http
          .get<UserInfo>('/api/me', { withCredentials: true })
          .pipe(
            tap((u) => this._user.set(u)),
            catchError(() => {
              this._user.set(null);
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

  /**
   * Email/password login. On success sets _user from response and resets loadPromise for
   * consistency. Caller should handle 401 (bad credentials) observable errors.
   */
  loginWithPassword(email: string, password: string): Observable<UserInfo> {
    return this.http
      .post<UserInfo>('/api/auth/login', { email, password }, { withCredentials: true })
      .pipe(
        tap((u) => {
          this._user.set(u);
          this.loadPromise = Promise.resolve();
        }),
      );
  }

  /**
   * New account registration. On success sets _user from response and resets loadPromise.
   * Caller should handle 409 (email taken) and 400 (validation) observable errors.
   */
  register(email: string, password: string, displayName: string): Observable<UserInfo> {
    return this.http
      .post<UserInfo>(
        '/api/auth/register',
        { email, password, displayName },
        { withCredentials: true },
      )
      .pipe(
        tap((u) => {
          this._user.set(u);
          this.loadPromise = Promise.resolve();
        }),
      );
  }

  /** Start the OpenGate OAuth2 Authorization Code flow via full-page redirect. */
  loginWithOpenGate(): void {
    this.navigate('/oauth2/authorization/opengate');
  }

  /**
   * Refresh the access cookie using the refresh cookie. Used by the auth-refresh interceptor.
   * Caller handles 401 (refresh cookie missing/invalid).
   */
  refresh(): Observable<unknown> {
    return this.http.post('/api/auth/refresh', null, { withCredentials: true });
  }

  /** End the session: POST logout, clear local state, reset loadPromise, navigate home. */
  logout(): void {
    this.http
      .post('/api/auth/logout', null, { withCredentials: true })
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        this._user.set(null);
        this.loadPromise = undefined;
        this.navigate('/');
      });
  }

  /** Full-page navigation. Extracted so it can be spied on in unit tests. */
  protected navigate(url: string): void {
    window.location.href = url;
  }
}
