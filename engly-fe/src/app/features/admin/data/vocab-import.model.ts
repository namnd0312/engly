/** Status values for a vocab import job — mirrors backend ImportStatus enum. */
export type ImportStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

/** Import job response DTO — mirrors backend VocabImportResponse. */
export interface ImportJob {
  id: number;
  deckId: number;
  status: ImportStatus;
  fileSize: number | null;
  processedCount: number;
  insertedCount: number;
  skippedCount: number;
  errorCount: number;
  percent: number;
  createdAt: string;
}

/** A single row-level import error returned by GET /api/admin/vocab-imports/{id}/errors. */
export interface ImportErrorRow {
  lineNo: number;
  rawExcerpt: string;
  reason: string;
}

/** Pagination envelope for import errors. */
export interface ImportErrorPage {
  content: ImportErrorRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Request body for POST /api/admin/vocab-imports. */
export interface CreateImportRequest {
  deckId: number;
  uploadId: string;
}

/** Response body for POST /api/admin/vocab-imports and POST .../retry. */
export interface CreateImportResponse {
  jobId: number;
  status: ImportStatus;
}
