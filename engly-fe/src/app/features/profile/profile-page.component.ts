import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import { AuthService } from '../../core/auth.service';
import { ProfileApiService } from './data/profile-api.service';

/**
 * Editable profile: display-name form + avatar upload.
 *
 * Avatar flow: request presigned URL → PUT the file DIRECTLY to S3 (any size, with progress) →
 * confirm with the backend → identity signal refreshed from the response. The resized (256/64)
 * WebP variants are produced asynchronously by a Lambda; right after upload the original image
 * is served, and later /api/me calls upgrade to the thumbnails.
 */
@Component({
  selector: 'app-profile-page',
  imports: [ReactiveFormsModule],
  templateUrl: './profile-page.component.html',
})
export class ProfilePageComponent {
  private readonly auth = inject(AuthService);
  private readonly api = inject(ProfileApiService);
  private readonly fb = inject(FormBuilder);

  readonly user = this.auth.user;

  // Display-name form (route is auth-guarded, user() is present).
  readonly form = this.fb.nonNullable.group({
    displayName: [this.auth.user()?.name ?? '', [Validators.required, Validators.maxLength(255)]],
  });
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly saveSuccess = signal(false);

  // Avatar upload state.
  readonly uploading = signal(false);
  readonly uploadProgress = signal(0);
  readonly uploadError = signal<string | null>(null);

  saveName(): void {
    this.saveError.set(null);
    this.saveSuccess.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.api.updateProfile(this.form.getRawValue().displayName.trim()).subscribe({
      next: (user) => {
        this.auth.setUser(user);
        this.saving.set(false);
        this.saveSuccess.set(true);
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(err?.error?.message ?? 'Failed to save profile');
      },
    });
  }

  onFileChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = ''; // allow re-selecting the same file
    if (!file) return;

    this.uploadError.set(null);
    if (!file.type.startsWith('image/')) {
      this.uploadError.set('Please choose an image file (JPEG, PNG, WebP or GIF).');
      return;
    }
    this.uploadAvatar(file);
  }

  private uploadAvatar(file: File): void {
    this.uploading.set(true);
    this.uploadProgress.set(0);

    this.api.requestAvatarUploadUrl(file.type).subscribe({
      next: ({ uploadUrl, objectKey }) => this.putToS3(uploadUrl, objectKey, file),
      error: (err) => this.failUpload(err?.error?.message ?? 'Could not start the upload'),
    });
  }

  private putToS3(uploadUrl: string, objectKey: string, file: File): void {
    this.api.uploadToS3(uploadUrl, file).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.uploadProgress.set(Math.round((100 * event.loaded) / event.total));
        }
        if (event.type === HttpEventType.Response) {
          this.confirm(objectKey);
        }
      },
      error: () => this.failUpload('Upload to storage failed — please try again'),
    });
  }

  private confirm(objectKey: string): void {
    this.api.confirmAvatar(objectKey).subscribe({
      next: (user) => {
        this.auth.setUser(user);
        this.uploading.set(false);
      },
      error: (err) => this.failUpload(err?.error?.message ?? 'Could not confirm the upload'),
    });
  }

  private failUpload(message: string): void {
    this.uploading.set(false);
    this.uploadError.set(message);
  }

  /** First letter for the no-avatar placeholder circle. */
  initial(): string {
    const u = this.user();
    return (u?.name || u?.email || '?').charAt(0).toUpperCase();
  }
}
