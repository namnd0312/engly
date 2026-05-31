# Frontend Admin CRUD Research – Irregular Verbs

## 1. Role-Based Guard (CanActivateFn)

**Pattern:** Angular 19 inject()-based functional guard, aligns with auth.service signals.

```typescript
// src/app/core/admin.guard.ts
import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';

export const adminGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // Non-blocking: if loadMe() not called yet or incomplete, reject
  if (!auth.isAuthenticated()) {
    auth.login(); // Full-page nav to OAuth2
    return false;
  }

  // TODO: extend auth.service to expose isAdmin signal
  // Placeholder: assume UserInfo.roles or roles claim from /api/me
  if (!auth.isAdmin()) {
    return router.parseUrl('/verbs'); // Redirect to home
  }

  return true;
};
```

**Key issue:** `loadMe()` is fire-and-forget (subscribes, no await). Guard executes synchronously. **Solution:** extend AuthService with:
- `ensureLoaded(): Promise<void>` – returns promise resolving when /api/me completes (or 401)
- `isAdmin: Signal<boolean>` – computed from user.roles (extend UserInfo interface to include roles: string[])

Guard revised:
```typescript
export const adminGuard: CanActivateFn = async (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  
  await auth.ensureLoaded();
  
  if (!auth.isAuthenticated() || !auth.isAdmin()) {
    return router.parseUrl('/verbs');
  }
  return true;
};
```

## 2. CSRF/XSRF for Angular 19 + Spring BFF

**Current state:** `app.config.ts` has `provideHttpClient(withFetch())` but **NO** XSRF config.

**Spring default:** CookieCsrfTokenRepository sets XSRF-TOKEN cookie; expects X-XSRF-TOKEN header on mutating calls.

**Angular 19 withFetch issue:** `withFetch()` uses browser fetch API. XSRF interception requires explicit `withXsrfConfiguration()`.

**Required fix in app.config.ts:**
```typescript
import { withXsrfConfiguration } from '@angular/common/http';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({
        cookieName: 'XSRF-TOKEN',
        headerName: 'X-XSRF-TOKEN'
      })
    ),
  ],
};
```

**Dev proxy implication:** `proxy.conf.json` routes `/api/*` → `:8081` (BFF). Same-origin ✓ so XSRF cookie/header flow works.

**Mutating calls:** POST/PUT/DELETE to `/api/verbs/*` with `withCredentials: true` auto-attach X-XSRF-TOKEN from cookie.

## 3. Admin API Service

**Pattern:** Mirror verb-api.service.ts structure.

```typescript
// src/app/features/admin/data/admin-verb-api.service.ts
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface VerbRequest {
  baseForm: string;        // 64 chars
  alternates?: string;     // 64 chars
  ipa?: string;           // 96 chars (pronunciation)
  description?: string;   // 255 chars
  verified: boolean;
}

@Injectable({ providedIn: 'root' })
export class AdminVerbApiService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/verbs';

  create(req: VerbRequest) {
    return this.http.post(this.apiUrl, req, { withCredentials: true });
  }

  update(id: string, req: VerbRequest) {
    return this.http.put(`${this.apiUrl}/${id}`, req, { withCredentials: true });
  }

  delete(id: string) {
    return this.http.delete(`${this.apiUrl}/${id}`, { withCredentials: true });
  }

  toggleVerified(id: string, verified: boolean) {
    return this.http.patch(`${this.apiUrl}/${id}`, { verified }, { withCredentials: true });
  }

  list(search?: string, limit = 20) {
    let url = `${this.apiUrl}?limit=${limit}`;
    if (search) url += `&q=${encodeURIComponent(search)}`;
    return this.http.get(url, { withCredentials: true });
  }
}
```

## 4. Reactive Form (Create/Edit)

```typescript
// src/app/features/admin/pages/verb-form.component.ts
import { Component, inject, Input, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { AdminVerbApiService, VerbRequest } from '../data/admin-verb-api.service';

@Component({
  selector: 'app-verb-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="onSubmit()">
      <div>
        <label>Base Form *</label>
        <input type="text" formControlName="baseForm" />
        @if (form.get('baseForm')?.errors) {
          <span class="error">{{ errors('baseForm') }}</span>
        }
      </div>

      <div>
        <label>Alternates</label>
        <input type="text" formControlName="alternates" />
      </div>

      <div>
        <label>IPA</label>
        <input type="text" formControlName="ipa" />
      </div>

      <div>
        <label>Description</label>
        <textarea formControlName="description"></textarea>
      </div>

      <div>
        <label>
          <input type="checkbox" formControlName="verified" />
          Verified
        </label>
      </div>

      @if (submitError) {
        <div class="error">{{ submitError }}</div>
      }

      <button type="submit" [disabled]="form.invalid || submitting">
        {{ isEdit ? 'Update' : 'Create' }}
      </button>
    </form>
  `,
  styles: [`
    .error { color: red; font-size: 0.9em; }
    input, textarea { width: 100%; padding: 8px; }
  `],
})
export class VerbFormComponent implements OnInit {
  @Input() verbId?: string; // null = create mode
  @Input() initialData?: VerbRequest;

  private fb = inject(FormBuilder);
  private api = inject(AdminVerbApiService);

  form!: FormGroup;
  submitting = false;
  submitError = '';
  isEdit = false;

  ngOnInit() {
    this.isEdit = !!this.verbId;
    this.form = this.fb.group({
      baseForm: ['', [Validators.required, Validators.maxLength(64)]],
      alternates: ['', Validators.maxLength(64)],
      ipa: ['', Validators.maxLength(96)],
      description: ['', Validators.maxLength(255)],
      verified: [false],
    });

    if (this.initialData) {
      this.form.patchValue(this.initialData);
    }
  }

  onSubmit() {
    if (this.form.invalid) return;
    this.submitting = true;
    this.submitError = '';

    const req = this.form.value as VerbRequest;
    const call$ = this.isEdit
      ? this.api.update(this.verbId!, req)
      : this.api.create(req);

    call$.subscribe({
      next: () => {
        // TODO: navigate back, show success
      },
      error: (err) => {
        this.submitting = false;
        if (err.status === 409) {
          this.submitError = 'Verb already exists';
        } else if (err.status === 400) {
          this.submitError = err.error?.message || 'Validation error';
        } else {
          this.submitError = 'Server error';
        }
      },
    });
  }

  errors(fieldName: string): string {
    const ctrl = this.form.get(fieldName);
    if (ctrl?.hasError('required')) return 'Required';
    if (ctrl?.hasError('maxlength')) return `Max ${ctrl.errors?.['maxlength'].requiredLength} chars`;
    return '';
  }
}
```

## 5. Admin List Page + Route

```typescript
// src/app/features/admin/pages/verb-list.component.ts (snippet)
export class VerbListComponent implements OnInit {
  verbs$ = this.api.list().pipe(map(res => res.data || []));
  isAdmin$ = this.auth.isAdmin();

  onDelete(id: string) {
    if (!confirm('Delete this verb?')) return;
    this.api.delete(id).subscribe(() => this.verbs$ = this.api.list());
  }
}
```

**Routes:**
```typescript
// src/app/features/admin/admin.routes.ts
import { Routes } from '@angular/router';
import { adminGuard } from '../../core/admin.guard';

export const adminRoutes: Routes = [
  {
    path: '',
    canActivate: [adminGuard],
    children: [
      { path: 'verbs', component: VerbListComponent },
      { path: 'verbs/new', component: VerbFormComponent },
      { path: 'verbs/:id/edit', component: VerbFormComponent },
    ],
  },
];
```

**App routes (add):**
```typescript
{
  path: 'admin',
  loadChildren: () =>
    import('./features/admin/admin.routes').then((m) => m.adminRoutes),
},
```

**Nav (conditional):**
```html
<!-- In app.component.html -->
@if (auth.isAdmin()) {
  <a href="/admin/verbs">Admin</a>
}
```

---

## Implementation Files To Create/Modify

| File | Action |
|------|--------|
| `src/app/core/auth.service.ts` | Extend: add roles to UserInfo; add ensureLoaded() + isAdmin signal |
| `src/app/core/admin.guard.ts` | CREATE – functional guard |
| `src/app/app.config.ts` | Modify – add withXsrfConfiguration |
| `src/app/app.routes.ts` | Modify – add admin route |
| `src/app/app.component.html` | Modify – conditional admin nav link |
| `src/app/features/admin/data/admin-verb-api.service.ts` | CREATE – API wrapper |
| `src/app/features/admin/pages/verb-form.component.ts` | CREATE – reactive form |
| `src/app/features/admin/pages/verb-list.component.ts` | CREATE – list + CRUD actions |
| `src/app/features/admin/admin.routes.ts` | CREATE – child routes |

---

## Unresolved Questions

1. Backend: Does `/api/me` return roles/permissions claim? Need to confirm UserInfo extension.
2. Verb API response model: What shape does list endpoint return? (e.g., `{ data: Verb[] }` or `Verb[]`?)
3. Edit endpoint: Is ID path param or body field?
4. Async guard: Does app already use `async` route guards? Confirm Angular config supports them.
