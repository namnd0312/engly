package com.engly.study.application;

import com.engly.study.api.dto.ReviewRating;
import com.engly.study.api.dto.ReviewRequest;
import com.engly.study.api.dto.ReviewResultResponse;
import com.engly.study.domain.CardLearningState;
import com.engly.study.domain.ReviewLog;
import com.engly.study.domain.UserCardState;
import com.engly.study.infrastructure.ReviewLogJpaRepository;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import com.engly.vocab.domain.VocabDeck;
import com.engly.vocab.domain.VocabWord;
import com.engly.vocab.domain.VocabWordNotFoundException;
import com.engly.vocab.infrastructure.VocabWordJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito unit tests for ReviewService — no Spring context, no DB.
 * Sm2Scheduler is mocked so SM-2 algorithm math is NOT re-tested here (see Sm2SchedulerTest).
 * Focus: wiring (input→scheduler→persist), lazy-create path, dueOn math, exception path.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    // Fixed clock: 2026-06-15T00:00:00Z = 2026-06-15 in Saigon (UTC+7)
    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-15T00:00:00Z");
    private static final ZoneId SAIGON = ZoneId.of("Asia/Saigon");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Mock private UserCardStateJpaRepository cardStateRepo;
    @Mock private VocabWordJpaRepository wordRepo;
    @Mock private ReviewLogJpaRepository reviewLogRepo;
    @Mock private Sm2Scheduler sm2Scheduler;

    private ReviewService service;

    @BeforeEach
    void setUp() {
        StudyClock studyClock = new StudyClock(Clock.fixed(FIXED_INSTANT, SAIGON));
        service = new ReviewService(cardStateRepo, wordRepo, reviewLogRepo, sm2Scheduler, studyClock);
    }

    /** Stub wordRepo.findById to return a word belonging to the given deck. */
    private void mockWord(long wordId, long deckId) {
        VocabDeck deck = mock(VocabDeck.class);
        when(deck.getId()).thenReturn(deckId);
        VocabWord word = mock(VocabWord.class);
        when(word.getDeck()).thenReturn(deck);
        when(wordRepo.findById(wordId)).thenReturn(Optional.of(word));
    }

    // -----------------------------------------------------------------------
    // Error path: word does not exist
    // -----------------------------------------------------------------------

    @Test
    void review_unknownWordId_throwsVocabWordNotFoundException() {
        when(wordRepo.findById(999L)).thenReturn(Optional.empty());

        ReviewRequest req = new ReviewRequest(999L, ReviewRating.GOOD);

        assertThatThrownBy(() -> service.review("user-1", req))
                .isInstanceOf(VocabWordNotFoundException.class)
                .hasMessageContaining("999");

        verify(cardStateRepo, never()).findByUserSubAndVocabWordId(any(), any());
        verify(sm2Scheduler, never()).schedule(any());
        verify(reviewLogRepo, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Lazy-create: first review of a word (no existing state row)
    // -----------------------------------------------------------------------

    @Test
    void review_noExistingState_lazyCreatesNewCardAndSchedules() {
        long wordId = 42L;
        String userSub = "user-sub-1";

        mockWord(wordId, 100L);
        // No existing state → return empty
        when(cardStateRepo.findByUserSubAndVocabWordId(userSub, wordId))
                .thenReturn(Optional.empty());

        // save(newCard) returns the same card (simulates DB assign id)
        UserCardState savedNew = UserCardState.newCard(userSub, wordId);
        when(cardStateRepo.save(any(UserCardState.class))).thenReturn(savedNew);

        // Scheduler returns a canned result (intervalDays=6)
        Sm2Result schedulerResult = new Sm2Result(2.5, 6, 1, 0, CardLearningState.REVIEW);
        when(sm2Scheduler.schedule(any(Sm2Input.class))).thenReturn(schedulerResult);

        ReviewRequest req = new ReviewRequest(wordId, ReviewRating.GOOD);
        ReviewResultResponse response = service.review(userSub, req);

        // save is called at least once (lazy-create), and once more for the state update
        verify(cardStateRepo, atLeast(2)).save(any(UserCardState.class));
        verify(sm2Scheduler).schedule(any(Sm2Input.class));
        // exactly one review-log row written, carrying the resolved deck id
        ArgumentCaptor<ReviewLog> logCaptor = ArgumentCaptor.forClass(ReviewLog.class);
        verify(reviewLogRepo).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getDeckId()).isEqualTo(100L);
        assertThat(logCaptor.getValue().getPrevState()).isEqualTo(CardLearningState.NEW.name());
        assertThat(logCaptor.getValue().getNewState()).isEqualTo(CardLearningState.REVIEW.name());
        assertThat(logCaptor.getValue().getRating()).isEqualTo(ReviewRating.GOOD.name());

        // dueOn = today + 6 days
        assertThat(response.dueOn()).isEqualTo(TODAY.plusDays(6));
        assertThat(response.wordId()).isEqualTo(wordId);
    }

    // -----------------------------------------------------------------------
    // Existing state path: state row already present → update, not re-create
    // -----------------------------------------------------------------------

    @Test
    void review_existingState_updatesAndSavesOnce() {
        long wordId = 10L;
        String userSub = "user-sub-2";

        UserCardState existing = existingReviewCard(userSub, wordId, 6, 2.5, 2);

        mockWord(wordId, 100L);
        when(cardStateRepo.findByUserSubAndVocabWordId(userSub, wordId))
                .thenReturn(Optional.of(existing));
        when(cardStateRepo.save(any(UserCardState.class))).thenReturn(existing);

        Sm2Result result = new Sm2Result(2.5, 15, 3, 0, CardLearningState.REVIEW);
        when(sm2Scheduler.schedule(any(Sm2Input.class))).thenReturn(result);

        ReviewRequest req = new ReviewRequest(wordId, ReviewRating.GOOD);
        service.review(userSub, req);

        // Only one save (update), not two (no lazy-create)
        verify(cardStateRepo, times(1)).save(existing);
        // findByUserSubAndVocabWordId was called; save(newCard) was NOT called on a different object
        verify(cardStateRepo).findByUserSubAndVocabWordId(userSub, wordId);
        // one review-log row written per review
        verify(reviewLogRepo, times(1)).save(any(ReviewLog.class));
    }

    // -----------------------------------------------------------------------
    // dueOn wiring: scheduler returns intervalDays=6 → dueOn = today + 6
    // -----------------------------------------------------------------------

    @Test
    void review_dueOnIsClockTodayPlusIntervalDays() {
        long wordId = 5L;
        String userSub = "user-3";
        int interval = 6;

        UserCardState state = UserCardState.newCard(userSub, wordId);
        mockWord(wordId, 100L);
        when(cardStateRepo.findByUserSubAndVocabWordId(userSub, wordId))
                .thenReturn(Optional.of(state));
        when(cardStateRepo.save(any())).thenReturn(state);

        Sm2Result schedulerResult = new Sm2Result(2.5, interval, 1, 0, CardLearningState.REVIEW);
        when(sm2Scheduler.schedule(any(Sm2Input.class))).thenReturn(schedulerResult);

        ReviewResultResponse response = service.review(userSub, new ReviewRequest(wordId, ReviewRating.GOOD));

        // dueOn must be Saigon-today + interval, NOT UTC offset
        assertThat(response.dueOn()).isEqualTo(TODAY.plusDays(interval));
    }

    // -----------------------------------------------------------------------
    // Sm2Input wiring: correct current-state fields passed to scheduler
    // -----------------------------------------------------------------------

    @Test
    void review_passesCorrectInputToScheduler() {
        long wordId = 20L;
        String userSub = "user-4";
        double ef = 2.35;
        int intervalDays = 10;
        int reps = 3;
        int lapses = 1;

        UserCardState state = existingReviewCard(userSub, wordId, intervalDays, ef, reps);
        setLapses(state, lapses);

        mockWord(wordId, 100L);
        when(cardStateRepo.findByUserSubAndVocabWordId(userSub, wordId))
                .thenReturn(Optional.of(state));
        when(cardStateRepo.save(any())).thenReturn(state);

        Sm2Result schedulerResult = new Sm2Result(ef, intervalDays, reps + 1, lapses, CardLearningState.REVIEW);
        when(sm2Scheduler.schedule(any(Sm2Input.class))).thenReturn(schedulerResult);

        service.review(userSub, new ReviewRequest(wordId, ReviewRating.HARD));

        ArgumentCaptor<Sm2Input> captor = ArgumentCaptor.forClass(Sm2Input.class);
        verify(sm2Scheduler).schedule(captor.capture());
        Sm2Input captured = captor.getValue();
        assertThat(captured.easeFactor()).isEqualTo(ef);
        assertThat(captured.intervalDays()).isEqualTo(intervalDays);
        assertThat(captured.repetitions()).isEqualTo(reps);
        assertThat(captured.lapses()).isEqualTo(lapses);
        assertThat(captured.rating()).isEqualTo(ReviewRating.HARD);
    }

    // -----------------------------------------------------------------------
    // Response mapping: returned fields mirror state after applyScheduling
    // -----------------------------------------------------------------------

    @Test
    void review_responseFieldsMirrorAppliedState() {
        long wordId = 7L;
        String userSub = "user-5";

        UserCardState state = UserCardState.newCard(userSub, wordId);
        mockWord(wordId, 100L);
        when(cardStateRepo.findByUserSubAndVocabWordId(userSub, wordId))
                .thenReturn(Optional.of(state));
        when(cardStateRepo.save(any())).thenReturn(state);

        Sm2Result schedulerResult = new Sm2Result(2.3, 1, 0, 1, CardLearningState.LEARNING);
        when(sm2Scheduler.schedule(any(Sm2Input.class))).thenReturn(schedulerResult);

        ReviewResultResponse response = service.review(userSub, new ReviewRequest(wordId, ReviewRating.AGAIN));

        assertThat(response.wordId()).isEqualTo(wordId);
        assertThat(response.state()).isEqualTo(CardLearningState.LEARNING);
        assertThat(response.intervalDays()).isEqualTo(1);
        assertThat(response.repetitions()).isEqualTo(0);
        assertThat(response.lapses()).isEqualTo(1);
        assertThat(response.easeFactor()).isCloseTo(2.3, within(0.001));
        assertThat(response.dueOn()).isEqualTo(TODAY.plusDays(1));
    }

    // -----------------------------------------------------------------------
    // Helpers — build real entities (no reflection magic beyond what's needed)
    // -----------------------------------------------------------------------

    /** Create an existing REVIEW-state card with specified SM-2 values. */
    private UserCardState existingReviewCard(String userSub, Long wordId,
                                             int intervalDays, double ef, int reps) {
        UserCardState s = UserCardState.newCard(userSub, wordId);
        s.applyScheduling(CardLearningState.REVIEW, ef, intervalDays, reps, 0,
                TODAY.minusDays(intervalDays), FIXED_INSTANT.minusSeconds(86400));
        return s;
    }

    private void setLapses(UserCardState state, int lapses) {
        // applyScheduling sets lapses; re-apply with correct lapses
        state.applyScheduling(state.getState(), state.getEaseFactor(),
                state.getIntervalDays(), state.getRepetitions(), lapses,
                state.getDueOn(), state.getLastReviewedAt());
    }
}
