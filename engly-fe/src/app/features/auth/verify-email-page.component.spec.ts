import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { VerifyEmailPageComponent } from './verify-email-page.component';
import { AuthService, UserInfo } from '../../core/auth.service';

const ALICE: UserInfo = {
  email: 'a@b.com',
  name: 'Alice',
  isAdmin: false,
  avatarUrl: null,
  avatarThumbUrl: null,
  mfaEnrolled: false,
  mfaVerified: false,
};

class FakeAuthService {
  authenticated = false;
  verifyEmailCalls: string[] = [];
  verifyEmailResult: Observable<UserInfo> = of(ALICE);

  ensureLoaded(): Promise<void> {
    return Promise.resolve();
  }
  isAuthenticated(): boolean {
    return this.authenticated;
  }
  verifyEmail(token: string): Observable<UserInfo> {
    this.verifyEmailCalls.push(token);
    return this.verifyEmailResult;
  }
  resendVerification() {
    return of(undefined);
  }
}

describe('VerifyEmailPageComponent', () => {
  let auth: FakeAuthService;
  let navigateSpy: jasmine.Spy;

  /** Configure `auth` (authenticated / verifyEmailResult) via `configure` BEFORE the component is created. */
  function createWith(
    token: string | null,
    configure: (a: FakeAuthService) => void = () => {},
  ): VerifyEmailPageComponent {
    auth = new FakeAuthService();
    configure(auth);
    const route = {
      snapshot: { queryParamMap: { get: (k: string) => (k === 'token' ? token : null) } },
    };

    TestBed.configureTestingModule({
      imports: [VerifyEmailPageComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: route },
        { provide: AuthService, useValue: auth },
      ],
    });
    navigateSpy = spyOn(TestBed.inject(Router), 'navigate').and.returnValue(Promise.resolve(true));
    const fixture = TestBed.createComponent(VerifyEmailPageComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('valid token, not authenticated: calls verifyEmail and ends in success', async () => {
    const component = createWith('valid-token');
    await Promise.resolve();
    await Promise.resolve();

    expect(auth.verifyEmailCalls).toEqual(['valid-token']);
    expect(component.state()).toBe('success');
  });

  it('missing token: immediate error state, no verifyEmail call', () => {
    const component = createWith(null);

    expect(component.state()).toBe('error');
    expect(auth.verifyEmailCalls).toEqual([]);
  });

  it('invalid/expired token: error state with resend form visible', async () => {
    const component = createWith('bad-token', (a) => {
      a.verifyEmailResult = throwError(() => ({ status: 400 }));
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(auth.verifyEmailCalls).toEqual(['bad-token']);
    expect(component.state()).toBe('error');
  });

  it('already authenticated: short-circuits to success WITHOUT calling verifyEmail', async () => {
    const component = createWith('valid-token', (a) => {
      a.authenticated = true;
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(auth.verifyEmailCalls).toEqual([]);
    expect(component.state()).toBe('success');
  });

  it('strips the token from the URL on load', () => {
    createWith('valid-token');

    expect(navigateSpy).toHaveBeenCalledWith([], jasmine.objectContaining({ queryParams: {} }));
  });
});
