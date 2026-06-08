import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { StudyApiService } from './data/study-api.service';
import { StudyDeckSummary } from './data/study.model';

/**
 * Deck list page (/study). Shows all available decks with new/due/learned counts.
 * Study button navigates to flashcard session. Settings link navigates to /study/settings.
 */
@Component({
  selector: 'app-deck-list-page',
  standalone: true,
  templateUrl: './deck-list-page.component.html',
  styleUrl: './deck-list-page.component.css',
})
export class DeckListPageComponent implements OnInit, OnDestroy {
  private readonly studyApi = inject(StudyApiService);
  private readonly router = inject(Router);

  readonly decks = signal<StudyDeckSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  private sub?: Subscription;

  ngOnInit(): void {
    this.sub = this.studyApi.getDecks().subscribe({
      next: (data) => {
        this.decks.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Failed to load decks. Please try again.');
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  studyDeck(deckId: number): void {
    this.router.navigate(['/study', deckId]);
  }

  goToSettings(): void {
    this.router.navigate(['/study', 'settings']);
  }
}
