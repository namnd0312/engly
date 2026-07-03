import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { authRefreshInterceptor } from './auth-refresh.interceptor';
import { AuthService } from './auth.service';

/**
 * The interceptor delegates refresh to AuthService.refresh() which in turn calls
 * POST /api/auth/refresh via HttpClient. We use the *real* AuthService here so that
 * the refresh call goes through HttpTestingController as well.
 *
 * For the "refresh fails → logout" test we need to spy on AuthService.logout().
 */
describe('authRefreshInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authRefreshInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => httpTesting.verify());

  it('passes through a successful /api request without refresh', () => {
    let done = false;
    http.get('/api/study/decks').subscribe(() => (done = true));

    httpTesting.expectOne('/api/study/decks').flush({ data: [] });
    expect(done).toBeTrue();
  });

  it('on 401 from /api/** retries after refresh and succeeds', () => {
    let body: unknown;
    http.get('/api/study/decks').subscribe((r) => (body = r));

    // First attempt → 401
    httpTesting.expectOne('/api/study/decks').flush(
      { message: 'Unauthorized' },
      { status: 401, statusText: 'Unauthorized' },
    );

    // Interceptor calls auth.refresh() → POST /api/auth/refresh via HttpClient
    httpTesting.expectOne('/api/auth/refresh').flush(null);

    // Retry of the original request
    httpTesting.expectOne('/api/study/decks').flush({ data: ['deck1'] });

    expect(body).toEqual({ data: ['deck1'] });
  });

  it('on 401 + failed refresh → calls auth.logout() and propagates error', () => {
    const logoutSpy = spyOn(auth, 'logout').and.callFake(() => { /* no-op in test */ });

    let err: unknown;
    http.get('/api/study/decks').subscribe({ error: (e) => (err = e) });

    httpTesting.expectOne('/api/study/decks').flush(
      { message: 'Unauthorized' },
      { status: 401, statusText: 'Unauthorized' },
    );

    // Refresh call fails
    httpTesting.expectOne('/api/auth/refresh').flush(
      { message: 'Refresh expired' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(err).toBeTruthy();
    expect(logoutSpy).toHaveBeenCalled();
  });

  it('does NOT refresh-retry a 401 from /api/auth/login', () => {
    let err: unknown;
    http.post('/api/auth/login', {}).subscribe({ error: (e) => (err = e) });

    httpTesting.expectOne('/api/auth/login').flush(
      { message: 'Bad credentials' },
      { status: 401, statusText: 'Unauthorized' },
    );

    // No retry request expected — verify() in afterEach confirms this
    expect(err).toBeTruthy();
  });

  it('does NOT refresh-retry a 401 from /api/auth/refresh itself', () => {
    let err: unknown;
    http.post('/api/auth/refresh', null).subscribe({ error: (e) => (err = e) });

    httpTesting.expectOne('/api/auth/refresh').flush(
      { message: 'Refresh expired' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(err).toBeTruthy();
  });

  it('does NOT refresh-retry a 401 from /api/me', () => {
    let err: unknown;
    http.get('/api/me').subscribe({ error: (e) => (err = e) });

    httpTesting
      .expectOne('/api/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(err).toBeTruthy();
  });

  it('passes non-401 errors through without refresh', () => {
    let err: unknown;
    http.get('/api/study/decks').subscribe({ error: (e) => (err = e) });

    httpTesting.expectOne('/api/study/decks').flush(
      { message: 'Server Error' },
      { status: 500, statusText: 'Internal Server Error' },
    );

    expect(err).toBeTruthy();
  });

  it('does not intercept non-/api requests', () => {
    let done = false;
    http.get('/assets/config.json').subscribe(() => (done = true));

    httpTesting.expectOne('/assets/config.json').flush({});
    expect(done).toBeTrue();
  });
});
