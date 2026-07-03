import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';

/**
 * Email/password login page. Also provides an OpenGate SSO button.
 * Route: /login (public — no guard).
 * On success navigates to returnUrl query param or '/'.
 */
@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="auth-page">
      <h1 class="page-title">Đăng nhập</h1>

      <form class="auth-form" (ngSubmit)="submit()" #f="ngForm" novalidate>
        <label class="field-label" for="email">Email</label>
        <input
          id="email"
          class="field-input"
          type="email"
          name="email"
          [(ngModel)]="email"
          required
          autocomplete="email"
          placeholder="ban@example.com"
        />

        <label class="field-label" for="password">Mật khẩu</label>
        <input
          id="password"
          class="field-input"
          type="password"
          name="password"
          [(ngModel)]="password"
          required
          autocomplete="current-password"
          placeholder="••••••••"
        />

        @if (errorMessage()) {
          <p class="error-msg" role="alert">{{ errorMessage() }}</p>
        }

        <button type="submit" class="btn btn-primary" [disabled]="loading()">
          {{ loading() ? 'Đang đăng nhập…' : 'Đăng nhập' }}
        </button>
      </form>

      <div class="divider"><span>hoặc</span></div>

      <button type="button" class="btn btn-sso" (click)="loginWithOpenGate()" [disabled]="loading()">
        Đăng nhập với OpenGate
      </button>

      <p class="switch-link">
        Chưa có tài khoản? <a routerLink="/register">Đăng ký</a>
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

      .page-title {
        font-size: 1.5rem;
        font-weight: 700;
        color: #1a1a2e;
        margin: 0 0 1.5rem;
        text-align: center;
      }

      .auth-form {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }

      .field-label {
        font-size: 0.875rem;
        font-weight: 600;
        color: #374151;
        margin-top: 0.5rem;
      }

      .field-input {
        padding: 0.55rem 0.75rem;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font: inherit;
        font-size: 0.95rem;
        outline: none;
        transition: border-color 0.15s;
      }

      .field-input:focus {
        border-color: #4f46e5;
        box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.15);
      }

      .error-msg {
        color: #b91c1c;
        font-size: 0.875rem;
        margin: 0.25rem 0 0;
      }

      .btn {
        margin-top: 1rem;
        padding: 0.6rem 1rem;
        border: none;
        border-radius: 6px;
        font: inherit;
        font-weight: 600;
        font-size: 0.95rem;
        cursor: pointer;
        transition: background 0.15s;
      }

      .btn:disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }

      .btn-primary {
        background: #4f46e5;
        color: #fff;
      }

      .btn-primary:hover:not(:disabled) {
        background: #4338ca;
      }

      .btn-sso {
        width: 100%;
        background: #f1f5f9;
        color: #1a1a2e;
        border: 1px solid #e2e8f0;
      }

      .btn-sso:hover:not(:disabled) {
        background: #e2e8f0;
      }

      .divider {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        margin: 1.25rem 0 0;
        color: #94a3b8;
        font-size: 0.85rem;
      }

      .divider::before,
      .divider::after {
        content: '';
        flex: 1;
        height: 1px;
        background: #e2e8f0;
      }

      .switch-link {
        margin-top: 1.25rem;
        text-align: center;
        font-size: 0.875rem;
        color: #64748b;
      }

      .switch-link a {
        color: #4f46e5;
        font-weight: 600;
        text-decoration: none;
      }

      .switch-link a:hover {
        text-decoration: underline;
      }
    `,
  ],
})
export class LoginPageComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  email = '';
  password = '';
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  submit(): void {
    this.errorMessage.set(null);
    if (!this.email || !this.password) {
      this.errorMessage.set('Vui lòng nhập email và mật khẩu.');
      return;
    }

    this.loading.set(true);
    this.auth.loginWithPassword(this.email, this.password).subscribe({
      next: () => {
        this.loading.set(false);
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
        void this.router.navigateByUrl(returnUrl);
      },
      error: (err: { status?: number }) => {
        this.loading.set(false);
        if (err?.status === 401) {
          this.errorMessage.set('Email hoặc mật khẩu không đúng.');
        } else {
          this.errorMessage.set('Đăng nhập thất bại. Vui lòng thử lại.');
        }
      },
    });
  }

  loginWithOpenGate(): void {
    this.auth.loginWithOpenGate();
  }
}
