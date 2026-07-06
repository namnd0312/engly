import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AuthService, UserInfo } from './auth.service';

const ANON_USER: UserInfo = {
  email: null, name: null, isAdmin: false, avatarUrl: null, avatarThumbUrl: null,
};
const ALICE: UserInfo = {
  email: 'a@b.com', name: 'Alice', isAdmin: false, avatarUrl: null, avatarThumbUrl: null,
};
const ADMIN: UserInfo = {
  email: 'admin@b.com', name: 'Admin', isAdmin: true, avatarUrl: null, avatarThumbUrl: null,
};

describe('AuthService', () => {
  let service: AuthService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  // ── ensureLoaded / loadMe ──────────────────────────────────────────────────

  it('loadMe() sets the user on 200 (withCredentials)', () => {
    service.loadMe();

    const req = httpTesting.expectOne('/api/me');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(ALICE);

    expect(service.user()).toEqual(ALICE);
    expect(service.isAuthenticated()).toBeTrue();
    expect(service.isAdmin()).toBeFalse();
  });

  it('isAdmin() is true when /api/me returns isAdmin: true', () => {
    service.loadMe();
    httpTesting.expectOne('/api/me').flush(ADMIN);

    expect(service.isAdmin()).toBeTrue();
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('ensureLoaded() resolves once /api/me settles and is memoized', async () => {
    const promise = service.ensureLoaded();
    // Second call must reuse the same in-flight promise (no extra request).
    expect(service.ensureLoaded()).toBe(promise);

    httpTesting.expectOne('/api/me').flush(ANON_USER);

    await expectAsync(promise).toBeResolved();
  });

  it('loadMe() recovers via one refresh-retry when the access cookie has expired', () => {
    service.loadMe();

    // Access cookie expired → first /api/me is 401.
    httpTesting
      .expectOne('/api/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    // Refresh cookie still valid → rotate the access cookie.
    httpTesting.expectOne('/api/auth/refresh').flush(null);

    // Retry succeeds with the fresh cookie.
    httpTesting.expectOne('/api/me').flush(ALICE);

    expect(service.user()).toEqual(ALICE);
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('loadMe() stays anonymous when the refresh also fails (truly expired)', () => {
    service.loadMe();
    httpTesting
      .expectOne('/api/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    httpTesting
      .expectOne('/api/auth/refresh')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(service.user()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('loadMe() stays anonymous on a non-401 error without attempting refresh', () => {
    service.loadMe();
    httpTesting
      .expectOne('/api/me')
      .flush(null, { status: 500, statusText: 'Server Error' });

    // No refresh attempt for non-401 — verify() in afterEach asserts no stray request.
    expect(service.user()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  // ── loginWithPassword ──────────────────────────────────────────────────────

  it('loginWithPassword() POSTs credentials and sets user on success', () => {
    let result: UserInfo | undefined;
    service.loginWithPassword('a@b.com', 'secret').subscribe((u) => (result = u));

    const req = httpTesting.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBeTrue();
    expect(req.request.body).toEqual({ email: 'a@b.com', password: 'secret' });
    req.flush(ALICE);

    // Background hydrate: login response has null avatar URLs, so /api/me is re-fetched.
    httpTesting.expectOne('/api/me').flush({ ...ALICE, avatarThumbUrl: 'http://s3/64.webp' });

    expect(result).toEqual(ALICE);
    expect(service.user()?.avatarThumbUrl).toBe('http://s3/64.webp');
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('loginWithPassword() propagates 401 without setting user', () => {
    let err: unknown;
    service.loginWithPassword('x@y.com', 'wrong').subscribe({
      error: (e) => (err = e),
    });

    httpTesting
      .expectOne('/api/auth/login')
      .flush({ message: 'Bad credentials' }, { status: 401, statusText: 'Unauthorized' });

    expect(err).toBeTruthy();
    expect(service.user()).toBeNull();
  });

  // ── register ──────────────────────────────────────────────────────────────

  it('register() POSTs payload and sets user on 200', () => {
    let result: UserInfo | undefined;
    service.register('a@b.com', 'pass', 'Alice').subscribe((u) => (result = u));

    const req = httpTesting.expectOne('/api/auth/register');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBeTrue();
    expect(req.request.body).toEqual({ email: 'a@b.com', password: 'pass', displayName: 'Alice' });
    req.flush(ALICE);

    // Background hydrate via /api/me (same as login).
    httpTesting.expectOne('/api/me').flush(ALICE);

    expect(result).toEqual(ALICE);
    expect(service.user()).toEqual(ALICE);
  });

  it('register() propagates 409 without setting user', () => {
    let err: unknown;
    service.register('taken@b.com', 'pass', 'Bob').subscribe({ error: (e) => (err = e) });

    httpTesting
      .expectOne('/api/auth/register')
      .flush({ message: 'Email taken' }, { status: 409, statusText: 'Conflict' });

    expect(err).toBeTruthy();
    expect(service.user()).toBeNull();
  });

  // ── refresh ───────────────────────────────────────────────────────────────

  it('refresh() POSTs /api/auth/refresh with withCredentials', () => {
    let completed = false;
    service.refresh().subscribe({ complete: () => (completed = true) });

    const req = httpTesting.expectOne('/api/auth/refresh');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBeTrue();
    expect(req.request.body).toBeNull();
    req.flush(null);

    expect(completed).toBeTrue();
  });

  it('refresh() de-duplicates concurrent calls into a single request', () => {
    let aDone = false;
    let bDone = false;
    service.refresh().subscribe({ complete: () => (aDone = true) });
    service.refresh().subscribe({ complete: () => (bDone = true) });

    // Both callers share one in-flight POST (prevents the refresh-rotation race → reload loop).
    const inFlight = httpTesting.match('/api/auth/refresh');
    expect(inFlight.length).toBe(1);
    inFlight[0].flush(null);
    expect(aDone).toBeTrue();
    expect(bDone).toBeTrue();

    // Once settled, a later refresh issues a fresh request.
    service.refresh().subscribe();
    httpTesting.expectOne('/api/auth/refresh').flush(null);
  });

  // ── logout ────────────────────────────────────────────────────────────────

  it('logout() POSTs /api/auth/logout, clears the user, and navigates home', () => {
    const navigate = spyOn(service as unknown as { navigate: (u: string) => void }, 'navigate');

    service.loadMe();
    httpTesting.expectOne('/api/me').flush(ALICE);
    expect(service.isAuthenticated()).toBeTrue();

    service.logout();
    const req = httpTesting.expectOne('/api/auth/logout');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(null);

    expect(service.user()).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('logout() still clears state if /api/auth/logout returns an error', () => {
    const navigate = spyOn(service as unknown as { navigate: (u: string) => void }, 'navigate');

    service.loadMe();
    httpTesting.expectOne('/api/me').flush(ALICE);

    service.logout();
    httpTesting
      .expectOne('/api/auth/logout')
      .flush(null, { status: 500, statusText: 'Server Error' });

    expect(service.user()).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  // ── setUser / refreshMe (profile feature) ─────────────────────────────────

  it('setUser() replaces the identity signal without an HTTP call', () => {
    service.setUser({ ...ALICE, name: 'Renamed', avatarUrl: 'http://s3/256.webp' });

    expect(service.user()?.name).toBe('Renamed');
    expect(service.user()?.avatarUrl).toBe('http://s3/256.webp');
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('refreshMe() re-fetches /api/me even after a previous load', async () => {
    service.loadMe();
    httpTesting.expectOne('/api/me').flush(ALICE);

    const refreshed = service.refreshMe();
    httpTesting
      .expectOne('/api/me')
      .flush({ ...ALICE, avatarThumbUrl: 'http://s3/64.webp' });
    await refreshed;

    expect(service.user()?.avatarThumbUrl).toBe('http://s3/64.webp');
  });
});
