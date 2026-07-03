import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { provideRouter } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

/**
 * Specs for the generic authGuard (CanActivateFn).
 * Contract:
 *  - authenticated → returns true (allow navigation).
 *  - anonymous     → returns UrlTree pointing to /login?returnUrl=... (block navigation).
 */

class FakeAuthService {
  private _authenticated = false;
  private _loadPromise = Promise.resolve();

  setAuthenticated(v: boolean): void { this._authenticated = v; }
  ensureLoaded(): Promise<void> { return this._loadPromise; }
  isAuthenticated(): boolean { return this._authenticated; }
}

describe('authGuard', () => {
  let auth: FakeAuthService;
  let router: Router;

  beforeEach(() => {
    auth = new FakeAuthService();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
      ],
    });
    router = TestBed.inject(Router);
  });

  const route = {} as ActivatedRouteSnapshot;

  function makeState(url: string): RouterStateSnapshot {
    return { url } as RouterStateSnapshot;
  }

  function run(url = '/study') {
    return TestBed.runInInjectionContext(
      () => authGuard(route, makeState(url)),
    );
  }

  it('allows navigation when user is authenticated', async () => {
    auth.setAuthenticated(true);
    await expectAsync(run()).toBeResolvedTo(true);
  });

  it('returns a UrlTree to /login when anonymous', async () => {
    auth.setAuthenticated(false);
    const result = await run('/study');
    expect(result).toBeInstanceOf(Object);
    // UrlTree serialises to /login?returnUrl=%2Fstudy
    const serialized = router.serializeUrl(result as ReturnType<Router['parseUrl']>);
    expect(serialized).toContain('/login');
    expect(serialized).toContain('returnUrl');
  });

  it('awaits ensureLoaded() before checking authentication', async () => {
    let resolve!: () => void;
    const slowPromise = new Promise<void>((r) => { resolve = r; });
    auth['_loadPromise'] = slowPromise;
    auth.setAuthenticated(true);

    const guardPromise = run();
    resolve();
    const result = await guardPromise;
    expect(result).toBeTrue();
  });
});
