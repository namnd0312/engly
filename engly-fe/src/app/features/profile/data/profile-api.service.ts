import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpEvent } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UserInfo } from '../../../core/auth.service';
import { AvatarUploadUrl } from './profile.model';

/**
 * Self-service profile API. Cookie-auth'd calls go to the relative /api/profile endpoints;
 * the avatar file itself is PUT straight to S3 with the presigned URL (never through the API).
 */
@Injectable({ providedIn: 'root' })
export class ProfileApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/profile';

  /** PATCH the display name; response is the fresh identity projection. */
  updateProfile(displayName: string): Observable<UserInfo> {
    return this.http.patch<UserInfo>(this.baseUrl, { displayName }, { withCredentials: true });
  }

  /** Ask the backend for a presigned PUT matching the file's content type. */
  requestAvatarUploadUrl(contentType: string): Observable<AvatarUploadUrl> {
    return this.http.post<AvatarUploadUrl>(
      `${this.baseUrl}/avatar/upload-url`,
      { contentType },
      { withCredentials: true },
    );
  }

  /**
   * Upload the raw file directly to S3 with progress events.
   *
   * Absolute cross-origin URL on purpose: HttpClient defaults to withCredentials=false (S3 must
   * not receive our cookies) and Angular's XSRF interceptor skips absolute URLs — both required.
   * Content-Type must equal the type the URL was signed for, or S3 rejects the PUT.
   */
  uploadToS3(uploadUrl: string, file: File): Observable<HttpEvent<unknown>> {
    return this.http.put(uploadUrl, file, {
      headers: { 'Content-Type': file.type },
      reportProgress: true,
      observe: 'events',
    });
  }

  /** Confirm the completed upload; backend validates ownership + persists the avatar key. */
  confirmAvatar(objectKey: string): Observable<UserInfo> {
    return this.http.post<UserInfo>(
      `${this.baseUrl}/avatar/confirm`,
      { objectKey },
      { withCredentials: true },
    );
  }
}
