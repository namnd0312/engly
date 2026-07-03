import { Component, OnInit, signal, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AdminDeckApiService } from './data/admin-deck-api.service';
import { DeckRequest } from './data/admin-deck.model';

/**
 * Create/edit form for a vocab deck. Presence of :id param switches to edit mode.
 * Catches 409 (duplicate slug) and surfaces message to user.
 */
@Component({
  selector: 'app-deck-admin-form-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './deck-admin-form-page.component.html',
})
export class DeckAdminFormPageComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(AdminDeckApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly editId = signal<number | null>(null);
  readonly submitting = signal(false);
  readonly serverError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    name:        ['', [Validators.required, Validators.maxLength(120)]],
    description: ['', [Validators.maxLength(500)]],
    topic:       ['', [Validators.maxLength(80)]],
  });

  ngOnInit(): void {
    const idParam = this.route.snapshot.paramMap.get('id');
    if (idParam) {
      const id = Number(idParam);
      this.editId.set(id);
      this.api.get(id).subscribe({
        next: (deck) =>
          this.form.patchValue({
            name:        deck.name,
            description: deck.description ?? '',
            topic:       deck.topic ?? '',
          }),
        error: (err) =>
          this.serverError.set(err?.error?.message ?? 'Failed to load deck.'),
      });
    }
  }

  onSubmit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.serverError.set(null);

    const payload = this.toPayload();
    const id = this.editId();
    const request$ = id !== null ? this.api.update(id, payload) : this.api.create(payload);

    request$.subscribe({
      next: () => this.router.navigate(['/admin/decks']),
      error: (err) => {
        this.submitting.set(false);
        if (err?.status === 409) {
          this.serverError.set('A deck with this slug already exists. Choose a different name.');
        } else {
          this.serverError.set(err?.error?.message ?? 'Save failed. Please try again.');
        }
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/admin/decks']);
  }

  private toPayload(): DeckRequest {
    const v = this.form.getRawValue();
    const orNull = (s: string): string | null => (s.trim() === '' ? null : s.trim());
    const name = v.name.trim();
    return {
      name,
      slug:        this.slugify(name),
      description: orNull(v.description),
      topic:       orNull(v.topic),
    };
  }

  /**
   * Derive a URL-safe lowercase-kebab slug from the deck name (backend requires @NotBlank,
   * pattern [a-z0-9-]+). Strips diacritics so Vietnamese names produce clean ASCII slugs.
   * On edit the backend ignores slug (immutable), so re-deriving from the name is harmless.
   */
  private slugify(name: string): string {
    const s = name
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .trim()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '');
    return s || 'deck';
  }
}
