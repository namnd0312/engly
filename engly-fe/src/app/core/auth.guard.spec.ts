import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { provideRouter } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

/**
 * Specs for the generic authGuard (CanActivateFn).
 * Mirrors the adminGuard spec pattern: stub AuthService, run in injection context.
 * Contract:
 *  - authenticated → returns true (allow navigation).
 *  - anonymous     → calls auth.login(), returns false (block navigation).
 */

class FakeAuthService {
  private _authenticated = false;
  loginCalls = 0;
  private _loadPromise = Promise.resolve();

  setAuthenticated(v: boolean): void { this._authenticated = v; }
  ensureLoaded(): Promise<void> { return this._loadPromise; }
  isAuthenticated(): boolean { return this._authenticated; }
  login(): void { this.loginCalls++; }
}

describe('authGuard', () => {
  let auth: FakeAuthService;

  beforeEach(() => {
    auth = new FakeAuthService();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
      ],
    });
  });

  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;

  function run(): Promise<boolean> {
    return TestBed.runInInjectionContext(
      () => authGuard(route, state) as Promise<boolean>,
    );
  }

  it('allows navigation when user is authenticated', async () => {
    auth.setAuthenticated(true);
    await expectAsync(run()).toBeResolvedTo(true);
  });

  it('calls auth.login() and returns false when user is anonymous', async () => {
    auth.setAuthenticated(false);
    const result = await run();
    expect(result).toBeFalse();
    expect(auth.loginCalls).toBe(1);
  });

  it('awaits ensureLoaded() before checking authentication', async () => {
    // Simulate slow identity load: ensureLoaded resolves after guard is called.
    let resolve!: () => void;
    const slowPromise = new Promise<void>((r) => { resolve = r; });
    auth['_loadPromise'] = slowPromise;
    auth.setAuthenticated(true);

    const guardPromise = run();
    // Resolve the slow load
    resolve();
    const result = await guardPromise;
    expect(result).toBeTrue();
  });
});
