import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AdminVocabWord, VocabWordPageResponse, VocabWordRequest } from './admin-vocab-word.model';

/**
 * HTTP client for the admin vocab-word CRUD API ({@code /api/admin/vocab-words}).
 * All calls send credentials; adminBearerInterceptor auto-attaches Bearer token.
 */
@Injectable({ providedIn: 'root' })
export class AdminVocabWordApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/vocab-words';

  list(q = '', page = 0, size = 20, deckId?: number): Observable<VocabWordPageResponse> {
    let params = new HttpParams()
      .set('q', q)
      .set('page', page.toString())
      .set('size', size.toString());
    if (deckId != null) {
      params = params.set('deckId', deckId.toString());
    }
    return this.http.get<VocabWordPageResponse>(this.baseUrl, { params, withCredentials: true });
  }

  get(id: number): Observable<AdminVocabWord> {
    return this.http.get<AdminVocabWord>(`${this.baseUrl}/${id}`, { withCredentials: true });
  }

  create(req: VocabWordRequest): Observable<AdminVocabWord> {
    return this.http.post<AdminVocabWord>(this.baseUrl, req, { withCredentials: true });
  }

  update(id: number, req: VocabWordRequest): Observable<AdminVocabWord> {
    return this.http.put<AdminVocabWord>(`${this.baseUrl}/${id}`, req, { withCredentials: true });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`, { withCredentials: true });
  }

  toggleVerified(id: number, verified: boolean): Observable<AdminVocabWord> {
    return this.http.patch<AdminVocabWord>(
      `${this.baseUrl}/${id}/verified`,
      { verified },
      { withCredentials: true },
    );
  }
}
