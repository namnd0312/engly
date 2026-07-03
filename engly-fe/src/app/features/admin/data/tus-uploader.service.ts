import { Injectable } from '@angular/core';
import * as tus from 'tus-js-client';

/** Options passed to the tus upload wrapper. */
export interface TusUploadOptions {
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
 * Auth is handled by httpOnly cookies via withCredentials — no bearer token needed.
 */
@Injectable({ providedIn: 'root' })
export class TusUploaderService {
  /** Starts a resumable upload and returns a handle to abort it. */
  upload(file: File, opts: TusUploadOptions): TusUploadHandle {
    const upload = new tus.Upload(file, {
      endpoint: '/api/admin/vocab-imports/upload',
      chunkSize: 5 * 1024 * 1024,
      retryDelays: [0, 1000, 3000, 5000],
      // Same-origin XHR — the browser sends httpOnly cookies automatically.
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
