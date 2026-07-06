import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { StudySummary, ActivityPoint, ForecastPoint, AccuracyPoint } from './progress.model';

/**
 * HTTP service for the study stats API (/api/study/stats/**).
 * All calls use withCredentials:true — the session cookie flows via the BFF, matching
 * StudyApiService. These are read-only GETs (no XSRF header needed).
 */
@Injectable({ providedIn: 'root' })
export class ProgressApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/study/stats';

  /** GET /api/study/stats/summary → KPI snapshot + streak. */
  getSummary(): Observable<StudySummary> {
    return this.http.get<StudySummary>(`${this.base}/summary`, { withCredentials: true });
  }

  /** GET /api/study/stats/activity?days=… → reviews vs new cards per day. */
  getActivity(days = 30): Observable<ActivityPoint[]> {
    return this.http.get<ActivityPoint[]>(`${this.base}/activity`, {
      params: new HttpParams().set('days', days),
      withCredentials: true,
    });
  }

  /** GET /api/study/stats/forecast?days=… → due cards per upcoming day. */
  getForecast(days = 14): Observable<ForecastPoint[]> {
    return this.http.get<ForecastPoint[]>(`${this.base}/forecast`, {
      params: new HttpParams().set('days', days),
      withCredentials: true,
    });
  }

  /** GET /api/study/stats/accuracy?days=… → per-day rating breakdown. */
  getAccuracy(days = 30): Observable<AccuracyPoint[]> {
    return this.http.get<AccuracyPoint[]>(`${this.base}/accuracy`, {
      params: new HttpParams().set('days', days),
      withCredentials: true,
    });
  }
}
