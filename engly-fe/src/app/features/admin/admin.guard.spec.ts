import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { adminGuard } from './admin.guard';
import { AuthService } from '../../core/auth.service';

/** Stub AuthService exposing only what the guard touches. */
class FakeAuthService {
  authenticated = false;
  admin = false;
  loginCalls = 0;

  ensureLoaded(): Promise<void> {
    return Promise.resolve();
  }
  isAuthenticated(): boolean {
    return this.authenticated;
  }
  isAdmin(): boolean {
    return this.admin;
  }
  login(): void {
    this.loginCalls++;
  }
}

describe('adminGuard', () => {
  let auth: FakeAuthService;

  beforeEach(() => {
    auth = new FakeAuthService();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
  });

  // Dummy args — the guard ignores route/state.
  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;

  function run(): Promise<boolean | UrlTree> {
    return TestBed.runInInjectionContext(
      () => adminGuard(route, state) as Promise<boolean | UrlTree>,
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
    const expected = TestBed.inject(Router).parseUrl('/forbidden');
    expect(result.toString()).toBe(expected.toString());
  });

  it('triggers login and blocks for an anonymous user', async () => {
    auth.authenticated = false;
    const result = await run();
    expect(result).toBeFalse();
    expect(auth.loginCalls).toBe(1);
  });
});
