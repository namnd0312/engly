import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AdminDeck, DeckPageResponse, DeckRequest } from './admin-deck.model';

/**
 * HTTP client for the admin deck CRUD API ({@code /api/admin/decks}).
 * All calls send credentials; adminBearerInterceptor auto-attaches Bearer token.
 */
@Injectable({ providedIn: 'root' })
export class AdminDeckApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/admin/decks';

  list(q = '', page = 0, size = 20): Observable<DeckPageResponse> {
    const params = new HttpParams()
      .set('q', q)
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<DeckPageResponse>(this.baseUrl, { params, withCredentials: true });
  }

  get(id: number): Observable<AdminDeck> {
    return this.http.get<AdminDeck>(`${this.baseUrl}/${id}`, { withCredentials: true });
  }

  create(req: DeckRequest): Observable<AdminDeck> {
    return this.http.post<AdminDeck>(this.baseUrl, req, { withCredentials: true });
  }

  update(id: number, req: DeckRequest): Observable<AdminDeck> {
    return this.http.put<AdminDeck>(`${this.baseUrl}/${id}`, req, { withCredentials: true });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`, { withCredentials: true });
  }
}
