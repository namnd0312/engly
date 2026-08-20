import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, finalize, of, shareReplay, switchMap, tap, throwError } from 'rxjs';

/** Identity projection returned by GET /api/me. Email is the only external identifier (no sub). */
export interface UserInfo {
  email: string | null;
  name: string | null;
  isAdmin: boolean;
  /** Short-lived presigned S3 URL of the 256px avatar (null = no avatar). */
  avatarUrl: string | null;
  /** Short-lived presigned S3 URL of the 64px avatar thumbnail for the header. */
  avatarThumbUrl: string | null;
}

/** Response for a successful registration while the activation gate is enabled — no session yet. */
export interface RegisterResult {
  email: string;
}

/**
 * App-owned JWT auth client. Identity is backed by httpOnly cookies (access + refresh) set by
 * the backend. Tokens never touch JS. CSRF is handled automatically via X-XSRF-TOKEN.
 *
 * - ensureLoaded(): memoized GET /api/me; any error → anonymous, never throws.
 * - loginWithPassword(): POST credentials, receive identity in response.
 * - register(): POST sign-up. Issues NO session — the account must be activated via the emailed
 *   link first (see verifyEmail()).
 * - verifyEmail(): consumes the activation token and starts the session.
 * - resendVerification(): re-triggers the activation email (generic, no enumeration).
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
        this.fetchMe()
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

  /**
   * GET /api/me with one silent refresh-retry. The access cookie has a short TTL (15m); once it
   * expires the browser drops it and /api/me returns 401 even though the 30-day refresh cookie is
   * still valid. The refresh interceptor deliberately skips /api/me (to avoid logout/redirect churn
   * for anonymous visitors), so recovery is handled here instead: on 401, rotate the access cookie
   * via /api/auth/refresh and retry once. Any failure propagates so the caller falls back to
   * anonymous — quietly, without triggering logout().
   */
  private fetchMe(): Observable<UserInfo> {
    return this.http.get<UserInfo>('/api/me', { withCredentials: true }).pipe(
      catchError((err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 401) {
          return this.refresh().pipe(
            switchMap(() => this.http.get<UserInfo>('/api/me', { withCredentials: true })),
          );
        }
        return throwError(() => err);
      }),
    );
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
          // The auth endpoint returns avatar URLs as null (presigned S3 URLs are built only by
          // /api/me). Hydrate in the background so the avatar shows without a manual reload.
          void this.refreshMe();
        }),
      );
  }

  /**
   * New account registration. Normally issues NO session (202 + `{email}`) — the account is
   * unusable until the emailed activation link is clicked (see verifyEmail()). Exception: with the
   * backend kill-switch `EMAIL_VERIFICATION_ENABLED=false`, register instead returns 200 + full
   * identity + cookies (today's pre-feature behavior) — detected here by the presence of `isAdmin`
   * (only on the identity response) and handled the same as loginWithPassword, so the escape hatch
   * actually logs the user in rather than stranding them on a bogus "check your inbox" screen.
   * Caller should handle 409 (email taken) and 400 (validation) observable errors.
   */
  register(email: string, password: string, displayName: string): Observable<UserInfo | RegisterResult> {
    return this.http
      .post<UserInfo | RegisterResult>(
        '/api/auth/register',
        { email, password, displayName },
        { withCredentials: true },
      )
      .pipe(
        tap((body) => {
          if ('isAdmin' in body) {
            this._user.set(body);
            void this.refreshMe();
          }
        }),
      );
  }

  /**
   * Consume the emailed activation token. On success sets _user from the response (auto-login) and
   * hydrates avatar URLs via /api/me in the background, same as login. Caller should handle 400
   * (invalid/expired token) and 409 (already authenticated as someone else) observable errors.
   */
  verifyEmail(token: string): Observable<UserInfo> {
    return this.http
      .post<UserInfo>('/api/auth/verify-email', { token }, { withCredentials: true })
      .pipe(
        tap((u) => {
          this._user.set(u);
          void this.refreshMe();
        }),
      );
  }

  /** Re-trigger the activation email. Backend always returns 202 (no account-existence enumeration). */
  resendVerification(email: string): Observable<void> {
    return this.http.post<void>('/api/auth/resend-verification', { email }, { withCredentials: true });
  }

  /** Replace the current identity (used by the profile page after PATCH/avatar confirm). */
  setUser(user: UserInfo): void {
    this._user.set(user);
    this.loadPromise = Promise.resolve();
  }

  /** Force a fresh GET /api/me (e.g. to pick up newly resized avatar URLs). */
  refreshMe(): Promise<void> {
    this.loadPromise = undefined;
    return this.ensureLoaded();
  }

  /** Shared in-flight refresh, so concurrent 401s trigger only one POST /api/auth/refresh. */
  private refreshInFlight?: Observable<unknown>;

  /**
   * Refresh the access cookie using the refresh cookie. Used by the auth-refresh interceptor and
   * by the /api/me hydrate path. Caller handles 401 (refresh cookie missing/invalid).
   *
   * De-duplicated: on a page load with a stale access cookie, several /api/** calls 401 at once
   * (e.g. /api/verbs + /api/me). Each would fire its own refresh, but the backend ROTATES the
   * refresh token (revokes the old one), so the second concurrent refresh hits an already-revoked
   * token → 401 → spurious logout → full-page reload loop. Sharing one in-flight request removes
   * the race; the reference is cleared when it settles so the next 401 gets a fresh refresh.
   */
  refresh(): Observable<unknown> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.http.post('/api/auth/refresh', null, { withCredentials: true }).pipe(
        finalize(() => (this.refreshInFlight = undefined)),
        shareReplay(1),
      );
    }
    return this.refreshInFlight;
  }

  /**
   * Start a social (Google/Facebook) login. This MUST be a full-page browser navigation, not an XHR:
   * the OAuth2 handshake redirects through the provider and back, and the resulting session cookies are
   * set by the backend on the redirect responses. The backend redirects home on success (or to
   * /login?error=sso on failure), after which the app shell loads identity via /api/me as usual.
   */
  loginWithProvider(provider: 'google' | 'facebook'): void {
    this.navigate(`/oauth2/authorization/${provider}`);
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
