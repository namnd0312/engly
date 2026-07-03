import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';

/**
 * New account registration page.
 * Route: /register (public — no guard).
 * On success navigates to '/'. Handles 409 (email taken) and 400 (validation) errors.
 */
@Component({
  selector: 'app-register-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <section class="auth-page">
      <h1 class="page-title">Đăng ký tài khoản</h1>

      <form class="auth-form" (ngSubmit)="submit()" #f="ngForm" novalidate>
        <label class="field-label" for="displayName">Tên hiển thị</label>
        <input
          id="displayName"
          class="field-input"
          type="text"
          name="displayName"
          [(ngModel)]="displayName"
          required
          autocomplete="name"
          placeholder="Nguyễn Văn A"
        />

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
          autocomplete="new-password"
          placeholder="••••••••"
        />

        @if (errorMessage()) {
          <p class="error-msg" role="alert">{{ errorMessage() }}</p>
        }

        <button type="submit" class="btn btn-primary" [disabled]="loading()">
          {{ loading() ? 'Đang đăng ký…' : 'Đăng ký' }}
        </button>
      </form>

      <p class="switch-link">
        Đã có tài khoản? <a routerLink="/login">Đăng nhập</a>
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
export class RegisterPageComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  displayName = '';
  email = '';
  password = '';
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  submit(): void {
    this.errorMessage.set(null);
    if (!this.displayName || !this.email || !this.password) {
      this.errorMessage.set('Vui lòng điền đầy đủ thông tin.');
      return;
    }

    this.loading.set(true);
    this.auth.register(this.email, this.password, this.displayName).subscribe({
      next: () => {
        this.loading.set(false);
        void this.router.navigate(['/']);
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.loading.set(false);
        if (err?.status === 409) {
          this.errorMessage.set('Email này đã được sử dụng. Vui lòng dùng email khác.');
        } else if (err?.status === 400) {
          const msg = err?.error?.message;
          this.errorMessage.set(msg ?? 'Thông tin không hợp lệ. Vui lòng kiểm tra lại.');
        } else {
          this.errorMessage.set('Đăng ký thất bại. Vui lòng thử lại.');
        }
      },
    });
  }
}
