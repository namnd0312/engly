import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UserInfo } from '../../core/auth.service';

/** Enrollment material returned by POST /api/profile/mfa/enroll. */
export interface MfaEnrollment {
  /** Base32 secret for manual entry in the authenticator app. */
  secret: string;
  /** otpauth:// URI rendered as the QR code. */
  otpauthUri: string;
}

/**
 * TOTP two-factor API client. Enrollment lives under /api/profile/mfa; the step-up itself is
 * POST /api/auth/mfa/verify, which re-mints the access cookie with mfa=true and returns the
 * identity with both MFA flags set so the caller can update AuthService without a /api/me round-trip.
 */
@Injectable({ providedIn: 'root' })
export class MfaApiService {
  private readonly http = inject(HttpClient);

  enroll(): Observable<MfaEnrollment> {
    return this.http.post<MfaEnrollment>('/api/profile/mfa/enroll', null, { withCredentials: true });
  }

  confirm(code: string): Observable<void> {
    return this.http.post<void>('/api/profile/mfa/confirm', { code }, { withCredentials: true });
  }

  disable(code: string): Observable<void> {
    return this.http.delete<void>('/api/profile/mfa', { body: { code }, withCredentials: true });
  }

  verify(code: string): Observable<UserInfo> {
    return this.http.post<UserInfo>('/api/auth/mfa/verify', { code }, { withCredentials: true });
  }
}

/** Map a backend MFA error (status + `code`) to user-facing Vietnamese copy. */
export function mfaErrorMessage(err: { status?: number; error?: { code?: string } }): string {
  switch (err?.error?.code) {
    case 'INVALID_MFA_CODE':
      return 'Mã không đúng hoặc đã được dùng. Vui lòng thử lại.';
    case 'MFA_LOCKED':
      return 'Nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.';
    case 'MFA_NOT_ENROLLED':
      return 'Tài khoản chưa bật xác thực hai lớp.';
    case 'MFA_UNAVAILABLE':
      return 'Xác thực hai lớp tạm thời không khả dụng. Vui lòng liên hệ quản trị hệ thống.';
    case 'MFA_ALREADY_ENROLLED':
      return 'Xác thực hai lớp đã được bật.';
    default:
      return 'Thao tác thất bại. Vui lòng thử lại.';
  }
}
