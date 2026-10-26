import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { MfaVerifyPageComponent } from './mfa-verify-page.component';
import { MfaApiService } from './mfa-api.service';
import { AuthService, UserInfo } from '../../core/auth.service';

const ADMIN_VERIFIED: UserInfo = {
  email: 'admin@b.com',
  name: 'Admin',
  isAdmin: true,
  avatarUrl: null,
  avatarThumbUrl: null,
  mfaEnrolled: true,
  mfaVerified: true,
};

describe('MfaVerifyPageComponent', () => {
  let verifyResult: Observable<UserInfo>;
  let currentUser: UserInfo | null = null;
  let setUserSpy: jasmine.Spy;
  let navigateSpy: jasmine.Spy;

  function create(returnUrl: string | null = '/admin/decks'): MfaVerifyPageComponent {
    const api = { verify: (_code: string) => verifyResult };
    const auth = { setUser: (_u: UserInfo) => undefined, user: (): UserInfo | null => currentUser };
    setUserSpy = spyOn(auth, 'setUser');
    const route = {
      snapshot: { queryParamMap: { get: (k: string) => (k === 'returnUrl' ? returnUrl : null) } },
    };
    TestBed.configureTestingModule({
      imports: [MfaVerifyPageComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: route },
        { provide: AuthService, useValue: auth },
        { provide: MfaApiService, useValue: api },
      ],
    });
    navigateSpy = spyOn(TestBed.inject(Router), 'navigateByUrl').and.returnValue(Promise.resolve(true));
    const fixture = TestBed.createComponent(MfaVerifyPageComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('rejects a malformed code locally without calling the API', () => {
    verifyResult = of(ADMIN_VERIFIED);
    const c = create();
    c.code = '12ab';
    c.submit();
    expect(c.errorMessage()).toContain('6 chữ số');
    expect(setUserSpy).not.toHaveBeenCalled();
  });

  it('on success applies the identity and navigates to returnUrl', () => {
    verifyResult = of(ADMIN_VERIFIED);
    const c = create('/admin/decks');
    c.code = '123456';
    c.submit();
    expect(setUserSpy).toHaveBeenCalledWith(ADMIN_VERIFIED);
    expect(navigateSpy).toHaveBeenCalledWith('/admin/decks');
    expect(c.loading()).toBeFalse();
  });

  it('keeps the loaded identity (avatar) and only flips the MFA flags', () => {
    currentUser = { ...ADMIN_VERIFIED, avatarThumbUrl: 'http://s3/64.webp', mfaVerified: false };
    verifyResult = of(ADMIN_VERIFIED);
    const c = create();
    c.code = '123456';
    c.submit();
    expect(setUserSpy).toHaveBeenCalledWith(
      jasmine.objectContaining({ avatarThumbUrl: 'http://s3/64.webp', mfaVerified: true }),
    );
    currentUser = null;
  });

  it('falls back to /admin when no returnUrl is given', () => {
    verifyResult = of(ADMIN_VERIFIED);
    const c = create(null);
    c.code = '123456';
    c.submit();
    expect(navigateSpy).toHaveBeenCalledWith('/admin');
  });

  it('shows the wrong-code message on 400 INVALID_MFA_CODE', () => {
    verifyResult = throwError(() => ({ status: 400, error: { code: 'INVALID_MFA_CODE' } }));
    const c = create();
    c.code = '123456';
    c.submit();
    expect(c.errorMessage()).toContain('không đúng');
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('shows the lockout message on 429 MFA_LOCKED', () => {
    verifyResult = throwError(() => ({ status: 429, error: { code: 'MFA_LOCKED' } }));
    const c = create();
    c.code = '123456';
    c.submit();
    expect(c.errorMessage()).toContain('15 phút');
  });

  it('redirects to the profile page on 409 MFA_NOT_ENROLLED', () => {
    verifyResult = throwError(() => ({ status: 409, error: { code: 'MFA_NOT_ENROLLED' } }));
    const c = create();
    c.code = '123456';
    c.submit();
    expect(navigateSpy).toHaveBeenCalledWith('/profile?mfa=required');
  });
});
