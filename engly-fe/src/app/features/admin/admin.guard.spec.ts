import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { adminGuard } from './admin.guard';
import { AuthService } from '../../core/auth.service';

/** Stub AuthService exposing only what the guard touches. */
class FakeAuthService {
  authenticated = false;
  admin = false;

  ensureLoaded(): Promise<void> {
    return Promise.resolve();
  }
  isAuthenticated(): boolean {
    return this.authenticated;
  }
  isAdmin(): boolean {
    return this.admin;
  }
}

describe('adminGuard', () => {
  let auth: FakeAuthService;
  let router: Router;

  beforeEach(() => {
    auth = new FakeAuthService();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    router = TestBed.inject(Router);
  });

  const route = {} as ActivatedRouteSnapshot;

  function makeState(url = '/admin'): RouterStateSnapshot {
    return { url } as RouterStateSnapshot;
  }

  function run(url = '/admin'): Promise<boolean | UrlTree> {
    return TestBed.runInInjectionContext(
      () => adminGuard(route, makeState(url)) as Promise<boolean | UrlTree>,
    );
  }

  it('allows an authenticated admin', async () => {
    auth.authenticated = true;
    auth.admin = true;
    await expectAsync(run()).toBeResolvedTo(true);
  });

  it('routes an authenticated non-admin to /forbidden', async () => {
    auth.authenticated = true;
    auth.admin = false;
    const result = await run();
    const expected = router.parseUrl('/forbidden');
    expect(result.toString()).toBe(expected.toString());
  });

  it('redirects an anonymous user to /login with returnUrl', async () => {
    auth.authenticated = false;
    const result = await run('/admin');
    expect(result).not.toBeFalse();
    const serialized = router.serializeUrl(result as UrlTree);
    expect(serialized).toContain('/login');
    expect(serialized).toContain('returnUrl');
  });
});
