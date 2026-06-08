import { Component, OnInit, OnDestroy, signal, computed, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { StudyApiService } from './data/study-api.service';
import { StudyCard, ReviewRating } from './data/study.model';

/**
 * Flashcard session page (/study/:deckId).
 * Fetches the study queue for a deck, shows one card at a time (term → meaning),
 * supports flip to reveal answer, 4 self-rating buttons, progress bar, and done state.
 * Mid-session 401: surfaces inline message + re-login button (no silent redirect).
 */
@Component({
  selector: 'app-flashcard-session-page',
  standalone: true,
  templateUrl: './flashcard-session-page.component.html',
  styleUrl: './flashcard-session-page.component.css',
})
export class FlashcardSessionPageComponent implements OnInit, OnDestroy {
  private readonly studyApi = inject(StudyApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly queue = signal<StudyCard[]>([]);
  readonly index = signal(0);
  readonly flipped = signal(false);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly ratingError = signal<string | null>(null);
  readonly sessionExpired = signal(false);
  readonly submitting = signal(false);

  readonly currentCard = computed(() => this.queue()[this.index()] ?? null);
  readonly done = computed(() => !this.loading() && this.queue().length > 0 && this.index() >= this.queue().length);
  readonly empty = computed(() => !this.loading() && this.queue().length === 0 && !this.loadError());
  readonly total = computed(() => this.queue().length);

  readonly ratings: ReviewRating[] = ['AGAIN', 'HARD', 'GOOD', 'EASY'];

  private deckId!: number;
  private sub?: Subscription;

  ngOnInit(): void {
    this.deckId = Number(this.route.snapshot.paramMap.get('deckId'));
    this.sub = this.studyApi.getQueue(this.deckId).subscribe({
      next: (cards) => {
        this.queue.set(cards);
        this.loading.set(false);
      },
      error: (err) => {
        if (err?.status === 401) {
          this.sessionExpired.set(true);
        } else {
          this.loadError.set(err?.error?.message ?? 'Failed to load study queue.');
        }
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  flip(): void {
    this.flipped.set(true);
  }

  rate(rating: ReviewRating): void {
    const card = this.currentCard();
    if (!card || this.submitting()) return;

    this.submitting.set(true);
    this.ratingError.set(null);

    this.studyApi.review(card.wordId, rating).subscribe({
      next: () => {
        this.submitting.set(false);
        this.index.set(this.index() + 1);
        this.flipped.set(false);
      },
      error: (err) => {
        this.submitting.set(false);
        if (err?.status === 401) {
          this.sessionExpired.set(true);
        } else {
          this.ratingError.set(err?.error?.message ?? 'Failed to submit rating. Please try again.');
        }
      },
    });
  }

  reLogin(): void {
    window.location.href = '/oauth2/authorization/opengate';
  }

  backToDecks(): void {
    this.router.navigate(['/study']);
  }
}
