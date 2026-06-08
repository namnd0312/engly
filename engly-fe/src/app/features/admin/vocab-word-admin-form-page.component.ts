import { Component, OnInit, signal, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AdminVocabWordApiService } from './data/admin-vocab-word-api.service';
import { AdminDeckApiService } from './data/admin-deck-api.service';
import { AdminDeck } from './data/admin-deck.model';
import { VocabWordRequest } from './data/admin-vocab-word.model';

/**
 * Create/edit form for a vocab word. Deck selector populated from GET /api/admin/decks.
 * Presence of :id param switches to edit mode. Catches 404 (missing deck).
 */
@Component({
  selector: 'app-vocab-word-admin-form-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './vocab-word-admin-form-page.component.html',
})
export class VocabWordAdminFormPageComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(AdminVocabWordApiService);
  private readonly deckApi = inject(AdminDeckApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly editId = signal<number | null>(null);
  readonly submitting = signal(false);
  readonly serverError = signal<string | null>(null);
  readonly decks = signal<AdminDeck[]>([]);

  readonly form = this.fb.nonNullable.group({
    deckId:       [0, [Validators.required, Validators.min(1)]],
    term:         ['', [Validators.required, Validators.maxLength(120)]],
    meaningVi:    ['', [Validators.required, Validators.maxLength(255)]],
    ipa:          ['', [Validators.maxLength(100)]],
    exampleEn:    ['', [Validators.maxLength(500)]],
    exampleVi:    ['', [Validators.maxLength(500)]],
    partOfSpeech: ['', [Validators.maxLength(50)]],
  });

  ngOnInit(): void {
    // Load deck options first.
    this.deckApi.list('', 0, 200).subscribe({
      next: (res) => this.decks.set(res.content),
    });

    const idParam = this.route.snapshot.paramMap.get('id');
    if (idParam) {
      const id = Number(idParam);
      this.editId.set(id);
      this.api.get(id).subscribe({
        next: (word) =>
          this.form.patchValue({
            deckId:       word.deckId,
            term:         word.term,
            meaningVi:    word.meaningVi,
            ipa:          word.ipa ?? '',
            exampleEn:    word.exampleEn ?? '',
            exampleVi:    word.exampleVi ?? '',
            partOfSpeech: word.partOfSpeech ?? '',
          }),
        error: (err) =>
          this.serverError.set(err?.error?.message ?? 'Failed to load word.'),
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
      next: () => this.router.navigate(['/admin/vocab-words']),
      error: (err) => {
        this.submitting.set(false);
        if (err?.status === 404) {
          this.serverError.set('Selected deck not found. Please choose a valid deck.');
        } else {
          this.serverError.set(err?.error?.message ?? 'Save failed. Please try again.');
        }
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/admin/vocab-words']);
  }

  private toPayload(): VocabWordRequest {
    const v = this.form.getRawValue();
    const orNull = (s: string): string | null => (s.trim() === '' ? null : s.trim());
    return {
      deckId:       Number(v.deckId),
      term:         v.term.trim(),
      meaningVi:    v.meaningVi.trim(),
      ipa:          orNull(v.ipa),
      exampleEn:    orNull(v.exampleEn),
      exampleVi:    orNull(v.exampleVi),
      partOfSpeech: orNull(v.partOfSpeech),
    };
  }
}
