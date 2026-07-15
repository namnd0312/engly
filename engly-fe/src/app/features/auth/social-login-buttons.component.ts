import { Component, inject } from '@angular/core';
import { AuthService } from '../../core/auth.service';

/**
 * "Sign in with Google / Facebook" buttons, shared by the login and register pages. Each button is a
 * full-page navigation to the backend OAuth2 entry point (via AuthService.loginWithProvider) — no
 * token handling in JS; cookies are set by the backend, same as password login.
 *
 * Brand marks are inline SVG (no external/CDN assets, offline-safe).
 */
@Component({
  selector: 'app-social-login-buttons',
  standalone: true,
  template: `
    <div class="social-divider"><span>hoặc</span></div>

    <button type="button" class="btn-social btn-google" (click)="auth.loginWithProvider('google')">
      <svg viewBox="0 0 18 18" width="18" height="18" aria-hidden="true">
        <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92a8.78 8.78 0 0 0 2.68-6.62Z"/>
        <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.02-3.7H.94v2.34A9 9 0 0 0 9 18Z"/>
        <path fill="#FBBC05" d="M3.98 10.72a5.4 5.4 0 0 1 0-3.44V4.94H.94a9 9 0 0 0 0 8.12l3.04-2.34Z"/>
        <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.46 3.44 1.35l2.58-2.58A9 9 0 0 0 .94 4.94l3.04 2.34C4.68 5.16 6.66 3.58 9 3.58Z"/>
      </svg>
      <span>Đăng nhập với Google</span>
    </button>

    <button type="button" class="btn-social btn-facebook" (click)="auth.loginWithProvider('facebook')">
      <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
        <path fill="#fff" d="M24 12c0-6.63-5.37-12-12-12S0 5.37 0 12c0 5.99 4.39 10.95 10.13 11.85v-8.38H7.08V12h3.05V9.36c0-3 1.79-4.67 4.53-4.67 1.31 0 2.68.24 2.68.24v2.95h-1.51c-1.49 0-1.96.93-1.96 1.87V12h3.33l-.53 3.47h-2.8v8.38C19.61 22.95 24 17.99 24 12Z"/>
      </svg>
      <span>Đăng nhập với Facebook</span>
    </button>
  `,
  styles: [
    `
      .social-divider {
        display: flex;
        align-items: center;
        text-align: center;
        color: #9ca3af;
        font-size: 0.8rem;
        margin: 1.25rem 0 0.75rem;
      }
      .social-divider::before,
      .social-divider::after {
        content: '';
        flex: 1;
        border-bottom: 1px solid #e5e7eb;
      }
      .social-divider span {
        padding: 0 0.75rem;
      }

      .btn-social {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 0.6rem;
        width: 100%;
        margin-top: 0.5rem;
        padding: 0.6rem 1rem;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font: inherit;
        font-weight: 600;
        font-size: 0.95rem;
        cursor: pointer;
        background: #fff;
        color: #374151;
        transition: background 0.15s, border-color 0.15s;
      }
      .btn-social:hover {
        background: #f9fafb;
      }
      .btn-social:focus-visible {
        outline: 2px solid #4f46e5;
        outline-offset: 2px;
      }

      .btn-facebook {
        background: #1877f2;
        border-color: #1877f2;
        color: #fff;
      }
      .btn-facebook:hover {
        background: #166fe0;
      }
    `,
  ],
})
export class SocialLoginButtonsComponent {
  readonly auth = inject(AuthService);
}
