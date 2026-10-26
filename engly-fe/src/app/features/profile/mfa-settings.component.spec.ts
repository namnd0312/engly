import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { MfaSettingsComponent } from './mfa-settings.component';
import { MfaApiService } from '../auth/mfa-api.service';
import { AuthService, UserInfo } from '../../core/auth.service';

function userWith(mfaEnrolled: boolean): UserInfo {
  return {
    email: 'a@b.com', name: 'Alice', isAdmin: true,
    avatarUrl: null, avatarThumbUrl: null, mfaEnrolled, mfaVerified: false,
  };
}

describe('MfaSettingsComponent', () => {
  let api: jasmine.SpyObj<MfaApiService>;
  let refreshMe: jasmine.Spy;

  function create(enrolled: boolean) {
    api = jasmine.createSpyObj<MfaApiService>('MfaApiService', ['enroll', 'confirm', 'disable', 'verify']);
    const auth = { user: signal<UserInfo | null>(userWith(enrolled)), refreshMe: () => Promise.resolve() };
    refreshMe = spyOn(auth, 'refreshMe').and.callThrough();
    TestBed.configureTestingModule({
      imports: [MfaSettingsComponent],
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: MfaApiService, useValue: api },
      ],
    });
    const fixture = TestBed.createComponent(MfaSettingsComponent);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('starts in "off" for a user without enrollment and in "on" for an enrolled one', () => {
    expect(create(false).component.state()).toBe('off');
    TestBed.resetTestingModule();
    expect(create(true).component.state()).toBe('on');
  });

  it('enroll → shows the QR with the otpauth URI and the manual secret', () => {
    const { fixture, component } = create(false);
    api.enroll.and.returnValue(of({ secret: 'MZXW6YTBOI', otpauthUri: 'otpauth://totp/Engly:a%40b.com?secret=MZXW6YTBOI' }));

    component.enroll();
    fixture.detectChanges();

    expect(component.state()).toBe('enrolling');
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('qrcode')).not.toBeNull();
    expect(el.querySelector('.secret')?.textContent).toContain('MZXW6YTBOI');
  });

  it('confirm with a valid code → "on" and identity reloaded', () => {
    const { component } = create(false);
    api.enroll.and.returnValue(of({ secret: 's', otpauthUri: 'otpauth://x' }));
    api.confirm.and.returnValue(of(undefined));
    component.enroll();
    component.code = '123456';

    component.confirm();

    expect(api.confirm).toHaveBeenCalledWith('123456');
    expect(component.state()).toBe('on');
    expect(refreshMe).toHaveBeenCalled();
  });

  it('confirm with a wrong code → stays enrolling with an error', () => {
    const { component } = create(false);
    api.enroll.and.returnValue(of({ secret: 's', otpauthUri: 'otpauth://x' }));
    api.confirm.and.returnValue(throwError(() => ({ status: 400, error: { code: 'INVALID_MFA_CODE' } })));
    component.enroll();
    component.code = '000000';

    component.confirm();

    expect(component.state()).toBe('enrolling');
    expect(component.errorMessage()).toContain('không đúng');
  });

  it('disable with a valid code → "off"', () => {
    const { component } = create(true);
    api.disable.and.returnValue(of(undefined));
    component.code = '123456';

    component.disable();

    expect(api.disable).toHaveBeenCalledWith('123456');
    expect(component.state()).toBe('off');
    expect(refreshMe).toHaveBeenCalled();
  });

  it('disable after an operator reset (MFA_NOT_ENROLLED) → resyncs to "off"', () => {
    const { component } = create(true);
    api.disable.and.returnValue(throwError(() => ({ status: 409, error: { code: 'MFA_NOT_ENROLLED' } })));
    component.code = '123456';

    component.disable();

    expect(component.state()).toBe('off');
    expect(refreshMe).toHaveBeenCalled();
  });

  it('cancel while enrolling → back to "off" without API calls', () => {
    const { component } = create(false);
    api.enroll.and.returnValue(of({ secret: 's', otpauthUri: 'otpauth://x' }));
    component.enroll();

    component.cancel();

    expect(component.state()).toBe('off');
    expect(api.confirm).not.toHaveBeenCalled();
  });
});
