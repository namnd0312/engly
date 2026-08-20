import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { LoginPageComponent } from './login-page.component';
import { AuthService } from '../../core/auth.service';

/**
 * The SSO failure handler redirects the browser to /login?error=sso; the login page reads that param
 * on construction and surfaces a friendly message.
 */
describe('LoginPageComponent SSO error param', () => {
  function createWith(errorParam: string | null): LoginPageComponent {
    const route = {
      snapshot: { queryParamMap: { get: (k: string) => (k === 'error' ? errorParam : null) } },
    };
    // Enough of Router to satisfy the routerLink directives rendered by the template.
    const router = {
      navigateByUrl: () => {},
      events: of(),
      createUrlTree: () => ({}),
      serializeUrl: () => '/',
    };
    TestBed.configureTestingModule({
      imports: [LoginPageComponent],
      providers: [
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: { loginWithProvider: () => {}, loginWithPassword: () => {} } },
      ],
    });
    return TestBed.createComponent(LoginPageComponent).componentInstance;
  }

  it('shows an SSO failure message when ?error=sso is present', () => {
    expect(createWith('sso').errorMessage()).toContain('mạng xã hội');
  });

  it('shows no error without the sso query param', () => {
    expect(createWith(null).errorMessage()).toBeNull();
  });
});

/** login()'s 403 EMAIL_NOT_VERIFIED path: shows the activation message + a working resend button. */
describe('LoginPageComponent unverified-account handling', () => {
  function createWith(auth: {
    loginWithPassword: () => Observable<unknown>;
    resendVerification?: (email: string) => Observable<unknown>;
  }): LoginPageComponent {
    const route = {
      snapshot: { queryParamMap: { get: () => null } },
    };
    const router = {
      navigateByUrl: () => {},
      events: of(),
      createUrlTree: () => ({}),
      serializeUrl: () => '/',
    };
    TestBed.configureTestingModule({
      imports: [LoginPageComponent],
      providers: [
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router },
        { provide: AuthService, useValue: auth },
      ],
    });
    return TestBed.createComponent(LoginPageComponent).componentInstance;
  }

  it('403 EMAIL_NOT_VERIFIED shows the activation message and enables resend', () => {
    const component = createWith({
      loginWithPassword: () =>
        throwError(() => ({ status: 403, error: { code: 'EMAIL_NOT_VERIFIED' } })),
    });
    component.email = 'a@b.com';
    component.password = 'supersecret';

    component.submit();

    expect(component.errorMessage()).toContain('chưa được kích hoạt');
    expect(component.showResend()).toBeTrue();
  });

  it('plain 401 shows the generic error and does not enable resend', () => {
    const component = createWith({
      loginWithPassword: () => throwError(() => ({ status: 401 })),
    });
    component.email = 'a@b.com';
    component.password = 'wrong';

    component.submit();

    expect(component.errorMessage()).toContain('không đúng');
    expect(component.showResend()).toBeFalse();
  });

  it('resend() calls auth.resendVerification with the entered email', () => {
    let calledWith: string | undefined;
    const component = createWith({
      loginWithPassword: () =>
        throwError(() => ({ status: 403, error: { code: 'EMAIL_NOT_VERIFIED' } })),
      resendVerification: (email: string) => {
        calledWith = email;
        return of(undefined);
      },
    });
    component.email = 'a@b.com';
    component.password = 'supersecret';
    component.submit();

    component.resend();

    expect(calledWith).toBe('a@b.com');
    expect(component.resendMessage()).toContain('gửi lại');
  });
});
