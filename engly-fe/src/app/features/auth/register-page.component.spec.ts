import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { RegisterPageComponent } from './register-page.component';
import { AuthService } from '../../core/auth.service';

/**
 * register() normally issues no session — success shows a "check your inbox" confirmation
 * instead of navigating away, and offers a resend action. Exception: with the backend kill-switch
 * disabled, register issues a session immediately — the page must land like a normal login instead.
 */
describe('RegisterPageComponent', () => {
  let navigateSpy: jasmine.Spy;

  function createWith(auth: Partial<AuthService>): RegisterPageComponent {
    TestBed.configureTestingModule({
      imports: [RegisterPageComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    navigateSpy = spyOn(TestBed.inject(Router), 'navigate').and.returnValue(Promise.resolve(true));
    return TestBed.createComponent(RegisterPageComponent).componentInstance;
  }

  it('on success (gate enabled) shows the confirmation state and does not navigate', () => {
    const component = createWith({
      register: () => of({ email: 'a@b.com' }),
      isAuthenticated: () => false,
    } as unknown as AuthService);

    component.displayName = 'Alice';
    component.email = 'a@b.com';
    component.password = 'supersecret';
    component.submit();

    expect(component.submitted()).toBeTrue();
    expect(component.submittedEmail()).toBe('a@b.com');
    expect(component.errorMessage()).toBeNull();
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('on success with the kill-switch disabled (session issued), navigates home instead of showing confirmation', () => {
    const component = createWith({
      register: () =>
        of({ email: 'a@b.com', name: 'Alice', isAdmin: false, avatarUrl: null, avatarThumbUrl: null }),
      isAuthenticated: () => true,
    } as unknown as AuthService);

    component.displayName = 'Alice';
    component.email = 'a@b.com';
    component.password = 'supersecret';
    component.submit();

    expect(component.submitted()).toBeFalse();
    expect(navigateSpy).toHaveBeenCalledWith(['/']);
  });

  it('on 409 shows the "email taken" error and stays on the form', () => {
    const component = createWith({
      register: () => throwError(() => ({ status: 409 })),
      isAuthenticated: () => false,
    } as unknown as AuthService);

    component.displayName = 'Bob';
    component.email = 'taken@b.com';
    component.password = 'supersecret';
    component.submit();

    expect(component.submitted()).toBeFalse();
    expect(component.errorMessage()).toContain('đã được sử dụng');
  });

  it('resend() calls auth.resendVerification with the submitted email', () => {
    let calledWith: string | undefined;
    const component = createWith({
      register: () => of({ email: 'a@b.com' }),
      isAuthenticated: () => false,
      resendVerification: (email: string) => {
        calledWith = email;
        return of(undefined);
      },
    } as unknown as AuthService);

    component.displayName = 'Alice';
    component.email = 'a@b.com';
    component.password = 'supersecret';
    component.submit();

    component.resend();

    expect(calledWith).toBe('a@b.com');
    expect(component.resendMessage()).toContain('gửi lại');
  });
});
