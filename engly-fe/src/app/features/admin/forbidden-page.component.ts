import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';

/**
 * 403 page shown when an authenticated, non-admin user reaches a guarded admin route.
 * Standalone + scoped CSS, consistent with the rest of the app (no UI lib).
 */
@Component({
  selector: 'app-forbidden-page',
  standalone: true,
  template: `
    <section class="forbidden-page">
      <h1 class="code">403</h1>
      <p class="title">Không có quyền</p>
      <p class="desc">Bạn không có quyền truy cập trang quản trị.</p>
      <button type="button" class="back-btn" (click)="goHome()">Về trang chủ</button>
    </section>
  `,
  styles: [
    `
      .forbidden-page {
        max-width: 480px;
        margin: 4rem auto;
        padding: 2rem 1rem;
        text-align: center;
        font-family: system-ui, sans-serif;
      }
      .code {
        font-size: 3.5rem;
        font-weight: 700;
        color: #b91c1c;
        margin: 0;
      }
      .title {
        font-size: 1.25rem;
        font-weight: 600;
        color: #1a1a2e;
        margin: 0.5rem 0 0.25rem;
      }
      .desc {
        color: #64748b;
        margin-bottom: 1.5rem;
      }
      .back-btn {
        background: #4f46e5;
        color: #fff;
        border: none;
        border-radius: 6px;
        padding: 0.5rem 1.1rem;
        font: inherit;
        font-weight: 600;
        cursor: pointer;
      }
      .back-btn:hover {
        background: #4338ca;
      }
    `,
  ],
})
export class ForbiddenPageComponent {
  private readonly router = inject(Router);

  goHome(): void {
    this.router.navigate(['/verbs']);
  }
}
