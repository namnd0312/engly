import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { MfaApiService, mfaErrorMessage } from './mfa-api.service';

/**
 * TOTP step-up page. Route: /mfa/verify?returnUrl= (auth-guarded).
 * An admin lands here (from adminGuard or a 403 MFA_REQUIRED) when the session has not yet
 * passed the second factor. On success the returned identity is applied to AuthService (so the
 * guard sees mfaVerified=true without a network round-trip) and the user is sent to returnUrl.
 */
@Component({
  selector: 'app-mfa-verify-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="auth-page">
      <h1 class="page-title">Xác thực hai lớp</h1>
      <p class="hint-msg">Nhập mã 6 số từ ứng dụng xác thực (Google Authenticator, Authy, …).</p>

      <form class="auth-form" (ngSubmit)="submit()" novalidate>
        <label class="field-label" for="code">Mã xác thực</label>
        <input
          id="code"
          class="field-input code-input"
          type="text"
          name="code"
          [(ngModel)]="code"
          inputmode="numeric"
          pattern="\\d{6}"
          maxlength="6"
          autocomplete="one-time-code"
          placeholder="123456"
          required
          autofocus
        />

        @if (errorMessage()) {
          <p class="error-msg" role="alert">{{ errorMessage() }}</p>
        }

        <button type="submit" class="btn btn-primary" [disabled]="loading()">
          {{ loading() ? 'Đang kiểm tra…' : 'Xác nhận' }}
        </button>
      </form>

      <p class="switch-link">
        Mất thiết bị? Liên hệ quản trị hệ thống. <a routerLink="/">Về trang chủ</a>
      </p>
    </section>
  `,
  styles: [
    `
      .auth-page {
        max-width: 400px;
        margin: 4rem auto;
        padding: 2rem 1.5rem;
        background: #fff;
        border-radius: 10px;
        box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
        font-family: system-ui, sans-serif;
      }
      .page-title { font-size: 1.5rem; font-weight: 700; color: #1a1a2e; margin: 0 0 0.75rem; text-align: center; }
      .hint-msg { color: #64748b; font-size: 0.875rem; margin: 0 0 1rem; text-align: center; }
      .auth-form { display: flex; flex-direction: column; gap: 0.5rem; }
      .field-label { font-size: 0.875rem; font-weight: 600; color: #374151; }
      .field-input {
        padding: 0.55rem 0.75rem;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font: inherit;
        outline: none;
      }
      .code-input { font-size: 1.4rem; letter-spacing: 0.4em; text-align: center; }
      .field-input:focus { border-color: #4f46e5; box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.15); }
      .error-msg { color: #b91c1c; font-size: 0.875rem; margin: 0.25rem 0 0; }
      .btn {
        margin-top: 1rem;
        padding: 0.6rem 1rem;
        border: none;
        border-radius: 6px;
        font: inherit;
        font-weight: 600;
        cursor: pointer;
      }
      .btn:disabled { opacity: 0.6; cursor: not-allowed; }
      .btn-primary { background: #4f46e5; color: #fff; }
      .btn-primary:hover:not(:disabled) { background: #4338ca; }
      .switch-link { margin-top: 1.25rem; text-align: center; font-size: 0.875rem; color: #64748b; }
      .switch-link a { color: #4f46e5; font-weight: 600; text-decoration: none; }
    `,
  ],
})
export class MfaVerifyPageComponent {
  private readonly auth = inject(AuthService);
  private readonly api = inject(MfaApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  code = '';
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  submit(): void {
    this.errorMessage.set(null);
    if (!/^\d{6}$/.test(this.code)) {
      this.errorMessage.set('Vui lòng nhập mã gồm 6 chữ số.');
      return;
    }
    this.loading.set(true);
    this.api.verify(this.code).subscribe({
      next: (user) => {
        this.loading.set(false);
        // Keep the already-loaded identity (avatar URLs etc.); only the MFA flags changed.
        const current = this.auth.user();
        this.auth.setUser(current ? { ...current, mfaEnrolled: true, mfaVerified: true } : user);
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/admin';
        void this.router.navigateByUrl(returnUrl);
      },
      error: (err: { status?: number; error?: { code?: string } }) => {
        this.loading.set(false);
        if (err?.error?.code === 'MFA_NOT_ENROLLED') {
          void this.router.navigateByUrl('/profile?mfa=required');
          return;
        }
        this.errorMessage.set(mfaErrorMessage(err));
      },
    });
  }
}
