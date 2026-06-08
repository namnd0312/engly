import { Injectable } from '@angular/core';
import * as tus from 'tus-js-client';

/** Options passed to the tus upload wrapper. */
export interface TusUploadOptions {
  /** Admin bearer token for Authorization header (tus uses XHR directly, not Angular HttpClient). */
  token: string | null;
  onProgress: (pct: number) => void;
  onSuccess: (uploadId: string) => void;
  onError: (err: Error) => void;
}

/** Handle returned by {@link TusUploaderService.upload} to allow aborting. */
export interface TusUploadHandle {
  abort(): void;
}

/**
 * Thin injectable wrapper around tus-js-client {@code Upload}.
 * Isolating this behind a service makes the page component unit-testable
 * without triggering real XHR — tests can provide a stub via DI.
 */
@Injectable({ providedIn: 'root' })
export class TusUploaderService {
  /** Starts a resumable upload and returns a handle to abort it. */
  upload(file: File, opts: TusUploadOptions): TusUploadHandle {
    const headers: Record<string, string> = opts.token
      ? { Authorization: `Bearer ${opts.token}` }
      : {};

    const upload = new tus.Upload(file, {
      endpoint: '/api/admin/vocab-imports/upload',
      chunkSize: 5 * 1024 * 1024,
      retryDelays: [0, 1000, 3000, 5000],
      headers,
      onProgress: (sent, total) => {
        opts.onProgress(total > 0 ? Math.round((sent / total) * 100) : 0);
      },
      onError: opts.onError,
      onSuccess: () => {
        const url = upload.url ?? '';
        const uploadId = url.substring(url.lastIndexOf('/') + 1);
        opts.onSuccess(uploadId);
      },
    });

    upload.start();

    return {
      abort: () => {
        void upload.abort(false);
      },
    };
  }
}
