/** Models matching the backend study stats DTOs (GET /api/study/stats/**). */

/** KPI snapshot + streak (GET /api/study/stats/summary). */
export interface StudySummary {
  totalCards: number;
  newCount: number;
  learningCount: number;
  reviewCount: number;
  totalReviews: number;
  reviewedToday: number;
  dueToday: number;
  currentStreakDays: number;
}

/** One day of activity (GET /api/study/stats/activity). date = 'YYYY-MM-DD'. */
export interface ActivityPoint {
  date: string;
  reviews: number;
  newCards: number;
}

/** One upcoming day of due cards (GET /api/study/stats/forecast). */
export interface ForecastPoint {
  date: string;
  dueCount: number;
}

/** One day of rating breakdown (GET /api/study/stats/accuracy). */
export interface AccuracyPoint {
  date: string;
  again: number;
  hard: number;
  good: number;
  easy: number;
}
