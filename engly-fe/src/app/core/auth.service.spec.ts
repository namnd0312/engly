import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AuthService, UserInfo } from './auth.service';

const ANON_USER: UserInfo = { sub: 'u-1', email: null, name: null, isAdmin: false };
const ALICE: UserInfo = { sub: 'u-1', email: 'a@b.com', name: 'Alice', isAdmin: false };
const ADMIN: UserInfo = { sub: 'u-2', email: 'admin@b.com', name: 'Admin', isAdmin: true };

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

  it('loadMe() stays anonymous on 401', () => {
    service.loadMe();
    httpTesting
      .expectOne('/api/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

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

    expect(result).toEqual(ALICE);
    expect(service.user()).toEqual(ALICE);
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

  // ── loginWithOpenGate ─────────────────────────────────────────────────────

  it('loginWithOpenGate() navigates to the SSO authorization endpoint', () => {
    const navigate = spyOn(service as unknown as { navigate: (u: string) => void }, 'navigate');
    service.loginWithOpenGate();
    expect(navigate).toHaveBeenCalledWith('/oauth2/authorization/opengate');
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
});
