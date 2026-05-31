import { Component, OnInit, signal, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AdminVerbApiService } from './data/admin-verb-api.service';
import { VerbRequest } from './data/admin-verb.model';

/**
 * Create/edit form for an irregular verb. One component, two modes: presence of the {@code :id}
 * route param switches to edit (prefills via GET /{id}). Validators mirror the DB column lengths.
 */
@Component({
  selector: 'app-admin-form-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './admin-form-page.component.html',
})
export class AdminFormPageComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(AdminVerbApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly editId = signal<number | null>(null);
  readonly submitting = signal(false);
  readonly serverError = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    baseForm: ['', [Validators.required, Validators.maxLength(100)]],
    pastSimple: ['', [Validators.required, Validators.maxLength(100)]],
    pastParticiple: ['', [Validators.required, Validators.maxLength(100)]],
    pastSimpleAlt: ['', [Validators.maxLength(100)]],
    pastParticipleAlt: ['', [Validators.maxLength(100)]],
    ipaBase: ['', [Validators.maxLength(100)]],
    ipaPast: ['', [Validators.maxLength(100)]],
    ipaPp: ['', [Validators.maxLength(100)]],
    meaningVi: ['', [Validators.required, Validators.maxLength(255)]],
    verified: [false],
  });

  ngOnInit(): void {
    const idParam = this.route.snapshot.paramMap.get('id');
    if (idParam) {
      const id = Number(idParam);
      this.editId.set(id);
      this.api.get(id).subscribe({
        next: (verb) =>
          this.form.patchValue({
            baseForm: verb.baseForm,
            pastSimple: verb.pastSimple,
            pastParticiple: verb.pastParticiple,
            pastSimpleAlt: verb.pastSimpleAlt ?? '',
            pastParticipleAlt: verb.pastParticipleAlt ?? '',
            ipaBase: verb.ipaBase ?? '',
            ipaPast: verb.ipaPast ?? '',
            ipaPp: verb.ipaPp ?? '',
            meaningVi: verb.meaningVi,
            verified: verb.verified,
          }),
        error: (err) =>
          this.serverError.set(err?.error?.message ?? 'Failed to load the verb.'),
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
      next: () => this.router.navigate(['/admin']),
      error: (err) => {
        this.submitting.set(false);
        if (err?.status === 409) {
          this.serverError.set('base_form already exists');
        } else {
          this.serverError.set(err?.error?.message ?? 'Save failed. Please try again.');
        }
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/admin']);
  }

  /** Map form values → request body, collapsing blank optional strings to null. */
  private toPayload(): VerbRequest {
    const v = this.form.getRawValue();
    const orNull = (s: string): string | null => (s.trim() === '' ? null : s.trim());
    return {
      baseForm: v.baseForm.trim(),
      pastSimple: v.pastSimple.trim(),
      pastParticiple: v.pastParticiple.trim(),
      pastSimpleAlt: orNull(v.pastSimpleAlt),
      pastParticipleAlt: orNull(v.pastParticipleAlt),
      ipaBase: orNull(v.ipaBase),
      ipaPast: orNull(v.ipaPast),
      ipaPp: orNull(v.ipaPp),
      meaningVi: v.meaningVi.trim(),
      verified: v.verified,
    };
  }
}
