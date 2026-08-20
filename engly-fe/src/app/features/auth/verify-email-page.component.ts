import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Meta } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';

type VerifyState = 'verifying' | 'success' | 'error';

/**
 * Consumes the activation link (`/verify-email?token=...`) sent on registration.
 * Route: /verify-email (public — no guard).
 *
 * - Strips the token from the URL on load (history.replaceState) and sets a strict
 *   `Referrer-Policy: no-referrer` on this page, so the secret never leaks via browser history or
 *   a third-party `Referer` header.
 * - If already authenticated, short-circuits straight to `success` WITHOUT calling verify — avoids
 *   re-POSTing an already-consumed token on refresh/bfcache (false "expired" flash) and avoids the
 *   login-CSRF surface the backend guards against.
 * - Fires the verify call exactly once (double-init guard).
 */
@Component({
  selector: 'app-verify-email-page',
  standalone: true,
  imports: [RouterLink, FormsModule],
  template: `
    <section class="auth-page">
      @switch (state()) {
        @case ('verifying') {
          <h1 class="page-title">Đang kích hoạt tài khoản…</h1>
          <p class="confirm-msg">Vui lòng chờ trong giây lát.</p>
        }
        @case ('success') {
          <h1 class="page-title">Kích hoạt thành công!</h1>
          <p class="confirm-msg">Tài khoản của bạn đã được kích hoạt.</p>
          <a routerLink="/" class="btn btn-primary">Vào ứng dụng</a>
        }
        @case ('error') {
          <h1 class="page-title">Liên kết không hợp lệ</h1>
          <p class="confirm-msg">
            Liên kết kích hoạt không hợp lệ hoặc đã hết hạn. Lưu ý: chỉ liên kết kích hoạt gần nhất
            còn hiệu lực. Nhập email để nhận liên kết mới.
          </p>

          <form class="auth-form" (ngSubmit)="resend()" novalidate>
            <input
              class="field-input"
              type="email"
              name="email"
              [(ngModel)]="resendEmail"
              required
              autocomplete="email"
              placeholder="ban@example.com"
            />
            <button type="submit" class="btn btn-primary" [disabled]="resending()">
              {{ resending() ? 'Đang gửi…' : 'Gửi lại email kích hoạt' }}
            </button>
          </form>

          @if (resendMessage()) {
            <p class="hint-msg">{{ resendMessage() }}</p>
          }

          <p class="switch-link">
            <a routerLink="/login">Về trang đăng nhập</a>
          </p>
        }
      }
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
        text-align: center;
      }

      .page-title {
        font-size: 1.5rem;
        font-weight: 700;
        color: #1a1a2e;
        margin: 0 0 1rem;
      }

      .confirm-msg {
        color: #374151;
        font-size: 0.95rem;
        line-height: 1.5;
        margin: 0 0 1.5rem;
      }

      .hint-msg {
        color: #64748b;
        font-size: 0.875rem;
        margin: 0.75rem 0 0;
      }

      .auth-form {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }

      .field-input {
        padding: 0.55rem 0.75rem;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font: inherit;
        font-size: 0.95rem;
        outline: none;
      }

      .btn {
        display: inline-block;
        padding: 0.6rem 1rem;
        border: none;
        border-radius: 6px;
        font: inherit;
        font-weight: 600;
        font-size: 0.95rem;
        cursor: pointer;
        text-decoration: none;
      }

      .btn:disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }

      .btn-primary {
        background: #4f46e5;
        color: #fff;
      }

      .switch-link {
        margin-top: 1.25rem;
        font-size: 0.875rem;
      }

      .switch-link a {
        color: #4f46e5;
        font-weight: 600;
        text-decoration: none;
      }
    `,
  ],
})
export class VerifyEmailPageComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly meta = inject(Meta);

  readonly state = signal<VerifyState>('verifying');
  readonly resending = signal(false);
  readonly resendMessage = signal<string | null>(null);
  resendEmail = '';

  private started = false;

  ngOnInit(): void {
    this.meta.addTag({ name: 'referrer', content: 'no-referrer' });

    const token = this.route.snapshot.queryParamMap.get('token');
    this.stripTokenFromUrl();

    if (this.started) return;
    this.started = true;

    if (!token) {
      this.state.set('error');
      return;
    }

    void this.auth.ensureLoaded().then(() => {
      if (this.auth.isAuthenticated()) {
        // Already logged in (e.g. page refresh after a successful activation) — do not re-POST an
        // already-consumed token, which would false-flash "expired" and re-hits the CSRF-exempt,
        // session-creating endpoint for no reason.
        this.state.set('success');
        return;
      }
      this.auth.verifyEmail(token).subscribe({
        next: () => this.state.set('success'),
        error: () => this.state.set('error'),
      });
    });
  }

  ngOnDestroy(): void {
    this.meta.removeTag('name="referrer"');
  }

  resend(): void {
    if (!this.resendEmail) return;
    this.resending.set(true);
    this.resendMessage.set(null);
    this.auth.resendVerification(this.resendEmail).subscribe({
      next: () => {
        this.resending.set(false);
        this.resendMessage.set('Nếu email hợp lệ, chúng tôi đã gửi lại liên kết kích hoạt.');
      },
      error: () => {
        this.resending.set(false);
        this.resendMessage.set('Nếu email hợp lệ, chúng tôi đã gửi lại liên kết kích hoạt.');
      },
    });
  }

  /** Removes the raw token from the address bar/history so it never leaks via Referer or history. */
  private stripTokenFromUrl(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {},
      replaceUrl: true,
    });
  }
}
