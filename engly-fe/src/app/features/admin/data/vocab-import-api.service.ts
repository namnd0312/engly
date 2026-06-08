import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  CreateImportRequest,
  CreateImportResponse,
  ImportErrorPage,
  ImportJob,
} from './vocab-import.model';

/**
 * HTTP client for the admin vocab-import API ({@code /api/admin/vocab-imports}).
 * All calls send credentials; adminBearerInterceptor auto-attaches Bearer token.
 */
@Injectable({ providedIn: 'root' })
export class VocabImportApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/vocab-imports';

  /** POST /api/admin/vocab-imports → 201 { jobId, status } */
  create(req: CreateImportRequest): Observable<CreateImportResponse> {
    return this.http.post<CreateImportResponse>(this.baseUrl, req, { withCredentials: true });
  }

  /** GET /api/admin/vocab-imports/{id} → ImportJob */
  getJob(id: number): Observable<ImportJob> {
    return this.http.get<ImportJob>(`${this.baseUrl}/${id}`, { withCredentials: true });
  }

  /** GET /api/admin/vocab-imports/{id}/errors?page=&size= → ImportErrorPage */
  getErrors(id: number, page = 0, size = 20): Observable<ImportErrorPage> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<ImportErrorPage>(`${this.baseUrl}/${id}/errors`, {
      params,
      withCredentials: true,
    });
  }

  /** POST /api/admin/vocab-imports/{id}/retry → { jobId, status } */
  retry(id: number): Observable<CreateImportResponse> {
    return this.http.post<CreateImportResponse>(`${this.baseUrl}/${id}/retry`, null, {
      withCredentials: true,
    });
  }
}
