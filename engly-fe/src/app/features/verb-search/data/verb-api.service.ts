import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { VerbResponse, VerbSearchPageResponse } from './verb.model';

/**
 * HTTP service for irregular-verb lookup API.
 * All calls use withCredentials:true so session cookies flow once SSO lands (phase 05).
 * Relative /api URLs — proxied to :8081 in dev via proxy.conf.json.
 */
@Injectable({ providedIn: 'root' })
export class VerbApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/verbs';

  /** Search verbs with optional query string, supports pagination. */
  search(q: string, page = 0, size = 20): Observable<VerbSearchPageResponse> {
    const params = new HttpParams()
      .set('q', q)
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<VerbSearchPageResponse>(this.baseUrl, {
      params,
      withCredentials: true,
    });
  }

  /** Fetch a single verb by its base form. Returns 404 HttpErrorResponse if not found. */
  getByBaseForm(baseForm: string): Observable<VerbResponse> {
    return this.http.get<VerbResponse>(`${this.baseUrl}/${encodeURIComponent(baseForm)}`, {
      withCredentials: true,
    });
  }
}
