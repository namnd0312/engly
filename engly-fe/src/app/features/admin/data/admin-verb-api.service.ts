import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { VerbAdminPageResponse, VerbAdminResponse, VerbRequest } from './admin-verb.model';

/**
 * HTTP client for the admin verb CRUD API ({@code /api/admin/verbs}). All calls send credentials;
 * X-XSRF-TOKEN is auto-attached to mutations by the global XSRF interceptor (app.config.ts).
 */
@Injectable({ providedIn: 'root' })
export class AdminVerbApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/verbs';

  list(q = '', page = 0, size = 20): Observable<VerbAdminPageResponse> {
    const params = new HttpParams()
      .set('q', q)
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<VerbAdminPageResponse>(this.baseUrl, { params, withCredentials: true });
  }

  get(id: number): Observable<VerbAdminResponse> {
    return this.http.get<VerbAdminResponse>(`${this.baseUrl}/${id}`, { withCredentials: true });
  }

  create(req: VerbRequest): Observable<VerbAdminResponse> {
    return this.http.post<VerbAdminResponse>(this.baseUrl, req, { withCredentials: true });
  }

  update(id: number, req: VerbRequest): Observable<VerbAdminResponse> {
    return this.http.put<VerbAdminResponse>(`${this.baseUrl}/${id}`, req, { withCredentials: true });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`, { withCredentials: true });
  }

  toggleVerified(id: number, verified: boolean): Observable<VerbAdminResponse> {
    return this.http.patch<VerbAdminResponse>(
      `${this.baseUrl}/${id}/verified`,
      { verified },
      { withCredentials: true },
    );
  }
}
