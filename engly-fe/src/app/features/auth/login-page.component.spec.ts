import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
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
