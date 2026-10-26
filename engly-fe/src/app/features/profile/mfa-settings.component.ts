import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { QRCodeComponent } from 'angularx-qrcode';
import { AuthService } from '../../core/auth.service';
import { MfaApiService, MfaEnrollment, mfaErrorMessage } from '../auth/mfa-api.service';

export type MfaSettingsState = 'off' | 'enrolling' | 'on';

/**
 * "Bảo mật" section of the profile page: enable / disable TOTP two-factor auth.
 * off → (Bật) enrolling: QR + manual secret + first code → (Xác nhận) on → (Tắt + code) off.
 * Initial state comes from the identity signal (mfaEnrolled); after each transition the identity
 * is reloaded so adminGuard and the header see the new flags.
 */
@Component({
  selector: 'app-mfa-settings',
  standalone: true,
  imports: [FormsModule, QRCodeComponent],
  template: `
    <section class="mfa-section">
      <h2 class="section-title">Bảo mật — Xác thực hai lớp</h2>

      @switch (state()) {
        @case ('off') {
          <p class="hint">Bảo vệ tài khoản bằng mã từ ứng dụng xác thực. Bắt buộc với tài khoản quản trị.</p>
          <button class="btn btn-primary" type="button" (click)="enroll()" [disabled]="busy()">
            {{ busy() ? 'Đang tạo…' : 'Bật xác thực hai lớp' }}
          </button>
        }
        @case ('enrolling') {
          <ol class="steps">
            <li>Quét mã QR bằng Google Authenticator, Authy hoặc ứng dụng tương tự.</li>
            <li>Nhập mã 6 số đang hiển thị để xác nhận.</li>
          </ol>
          <div class="qr-wrap">
            <qrcode [qrdata]="enrollment()!.otpauthUri" [width]="180" errorCorrectionLevel="M" />
          </div>
          <p class="hint">Không quét được? Nhập thủ công: <code class="secret">{{ enrollment()!.secret }}</code></p>
          <form class="code-form" (ngSubmit)="confirm()" novalidate>
            <input
              class="field-input code-input"
              type="text"
              name="code"
              [(ngModel)]="code"
              inputmode="numeric"
              pattern="\\d{6}"
              maxlength="6"
              autocomplete="one-time-code"
              placeholder="123456"
              aria-label="Mã xác thực"
            />
            <button class="btn btn-primary" type="submit" [disabled]="busy()">Xác nhận</button>
            <button class="btn btn-ghost" type="button" (click)="cancel()" [disabled]="busy()">Huỷ</button>
          </form>
        }
        @case ('on') {
          <p class="hint on-badge">✓ Xác thực hai lớp đang bật.</p>
          <form class="code-form" (ngSubmit)="disable()" novalidate>
            <input
              class="field-input code-input"
              type="text"
              name="code"
              [(ngModel)]="code"
              inputmode="numeric"
              pattern="\\d{6}"
              maxlength="6"
              autocomplete="one-time-code"
              placeholder="Mã để tắt"
              aria-label="Mã xác thực để tắt"
            />
            <button class="btn btn-danger" type="submit" [disabled]="busy()">
              {{ busy() ? 'Đang tắt…' : 'Tắt xác thực hai lớp' }}
            </button>
          </form>
        }
      }

      @if (errorMessage()) {
        <p class="error-msg" role="alert">{{ errorMessage() }}</p>
      }
    </section>
  `,
  styles: [
    `
      .mfa-section { padding-top: 1.25rem; border-top: 1px solid #f1f5f9; margin-top: 1.5rem; }
      .section-title { font-size: 1rem; font-weight: 700; color: #111827; margin: 0 0 0.75rem; }
      .hint { color: #6b7280; font-size: 0.85rem; margin: 0 0 0.75rem; }
      .on-badge { color: #16a34a; font-weight: 600; }
      .steps { color: #374151; font-size: 0.85rem; padding-left: 1.2rem; margin: 0 0 0.75rem; }
      .qr-wrap { display: flex; justify-content: center; margin: 0.5rem 0 0.75rem; }
      .secret { font-family: ui-monospace, monospace; font-size: 0.8rem; word-break: break-all; }
      .code-form { display: flex; gap: 0.6rem; flex-wrap: wrap; align-items: center; }
      .field-input {
        padding: 0.6rem 0.8rem;
        border: 1px solid #d1d5db;
        border-radius: 9px;
        font-size: 0.95rem;
        color: #111827;
      }
      .code-input { width: 9.5rem; letter-spacing: 0.3em; text-align: center; }
      .field-input:focus { outline: none; border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.15); }
      .btn {
        display: inline-flex;
        align-items: center;
        padding: 0.6rem 1.25rem;
        border-radius: 9px;
        border: none;
        font-size: 0.9rem;
        font-weight: 600;
        cursor: pointer;
      }
      .btn:disabled { opacity: 0.6; cursor: default; }
      .btn-primary { background: linear-gradient(135deg, #6366f1, #7c3aed); color: #fff; }
      .btn-danger { background: #fee2e2; color: #b91c1c; }
      .btn-ghost { background: transparent; color: #6b7280; }
      .error-msg { color: #dc2626; font-size: 0.82rem; margin: 0.5rem 0 0; }
    `,
  ],
})
export class MfaSettingsComponent {
  private readonly auth = inject(AuthService);
  private readonly api = inject(MfaApiService);

  readonly state = signal<MfaSettingsState>(this.auth.user()?.mfaEnrolled ? 'on' : 'off');
  readonly enrollment = signal<MfaEnrollment | null>(null);
  readonly busy = signal(false);
  readonly errorMessage = signal<string | null>(null);
  code = '';

  enroll(): void {
    this.errorMessage.set(null);
    this.busy.set(true);
    this.api.enroll().subscribe({
      next: (enrollment) => {
        this.busy.set(false);
        this.enrollment.set(enrollment);
        this.code = '';
        this.state.set('enrolling');
      },
      error: (err) => this.fail(err),
    });
  }

  confirm(): void {
    if (!this.validCode()) return;
    this.busy.set(true);
    this.api.confirm(this.code).subscribe({
      next: () => this.transition('on'),
      error: (err) => this.fail(err),
    });
  }

  disable(): void {
    if (!this.validCode()) return;
    this.busy.set(true);
    this.api.disable(this.code).subscribe({
      next: () => this.transition('off'),
      error: (err) => this.fail(err),
    });
  }

  cancel(): void {
    // The unconfirmed enrollment stays server-side but grants nothing; the next "Bật" replaces it.
    this.enrollment.set(null);
    this.errorMessage.set(null);
    this.state.set('off');
  }

  private validCode(): boolean {
    this.errorMessage.set(null);
    if (!/^\d{6}$/.test(this.code)) {
      this.errorMessage.set('Vui lòng nhập mã gồm 6 chữ số.');
      return false;
    }
    return true;
  }

  private transition(next: MfaSettingsState): void {
    this.busy.set(false);
    this.enrollment.set(null);
    this.code = '';
    this.state.set(next);
    void this.auth.refreshMe();
  }

  private fail(err: { status?: number; error?: { code?: string } }): void {
    this.busy.set(false);
    this.errorMessage.set(mfaErrorMessage(err));
    // Server state differs from what this view assumed (e.g. reset by an operator) → resync.
    const code = err?.error?.code;
    if (code === 'MFA_NOT_ENROLLED' || code === 'MFA_ALREADY_ENROLLED') {
      this.enrollment.set(null);
      this.state.set(code === 'MFA_NOT_ENROLLED' ? 'off' : 'on');
      void this.auth.refreshMe();
    }
  }
}
