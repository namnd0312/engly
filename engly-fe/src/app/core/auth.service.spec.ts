import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { AuthService, UserInfo } from './auth.service';

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

  it('loadMe() sets the user on 200 (withCredentials)', () => {
    const user: UserInfo = { sub: 'u-1', email: 'a@b.com', name: 'Alice' };
    service.loadMe();

    const req = httpTesting.expectOne('/api/me');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBeTrue();
    req.flush(user);

    expect(service.user()).toEqual(user);
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('loadMe() stays anonymous on 401', () => {
    service.loadMe();
    httpTesting
      .expectOne('/api/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(service.user()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('login() redirects to the BFF authorization endpoint', () => {
    const navigate = spyOn(service as unknown as { navigate: (u: string) => void }, 'navigate');
    service.login();
    expect(navigate).toHaveBeenCalledWith('/oauth2/authorization/opengate');
  });

  it('logout() posts /logout, clears the user, and returns home', () => {
    const navigate = spyOn(service as unknown as { navigate: (u: string) => void }, 'navigate');

    service.loadMe();
    httpTesting.expectOne('/api/me').flush({ sub: 'u-1', email: null, name: null });
    expect(service.isAuthenticated()).toBeTrue();

    service.logout();
    const req = httpTesting.expectOne('/logout');
    expect(req.request.method).toBe('POST');
    req.flush(null);

    expect(service.user()).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/');
  });
});
