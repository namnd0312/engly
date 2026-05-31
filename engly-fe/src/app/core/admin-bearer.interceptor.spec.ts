import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { adminBearerInterceptor } from './admin-bearer.interceptor';
import { AuthService } from './auth.service';

class FakeAuthService {
  current: string | null = 'jwt-abc';
  token(): string | null {
    return this.current;
  }
}

describe('adminBearerInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let auth: FakeAuthService;

  beforeEach(() => {
    auth = new FakeAuthService();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([adminBearerInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('adds Authorization: Bearer to /api/admin requests when a token exists', () => {
    http.get('/api/admin/verbs').subscribe();
    const req = httpTesting.expectOne('/api/admin/verbs');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-abc');
    req.flush({});
  });

  it('does not add Authorization when no token is present', () => {
    auth.current = null;
    http.get('/api/admin/verbs').subscribe();
    const req = httpTesting.expectOne('/api/admin/verbs');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('leaves non-admin requests untouched', () => {
    http.get('/api/me').subscribe();
    const req = httpTesting.expectOne('/api/me');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });
});
