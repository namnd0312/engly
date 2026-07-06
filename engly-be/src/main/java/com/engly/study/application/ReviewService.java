package com.engly.study.application;

import com.engly.study.api.dto.ReviewRequest;
import com.engly.study.api.dto.ReviewResultResponse;
import com.engly.study.domain.CardLearningState;
import com.engly.study.domain.ReviewLog;
import com.engly.study.domain.UserCardState;
import com.engly.study.infrastructure.ReviewLogJpaRepository;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import com.engly.vocab.domain.VocabWord;
import com.engly.vocab.domain.VocabWordNotFoundException;
import com.engly.vocab.infrastructure.VocabWordJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Handles POST /api/study/review — loads (or lazy-creates) a card state,
 * runs the pure Sm2Scheduler, persists the result, and returns the updated state.
 * dueOn = studyClock.today() + result.intervalDays() (Saigon TZ, never LocalDate.now()).
 */
@Service
public class ReviewService {

    private final UserCardStateJpaRepository cardStateRepo;
    private final VocabWordJpaRepository wordRepo;
    private final ReviewLogJpaRepository reviewLogRepo;
    private final Sm2Scheduler sm2Scheduler;
    private final StudyClock studyClock;

    public ReviewService(UserCardStateJpaRepository cardStateRepo,
                         VocabWordJpaRepository wordRepo,
                         ReviewLogJpaRepository reviewLogRepo,
                         Sm2Scheduler sm2Scheduler,
                         StudyClock studyClock) {
        this.cardStateRepo = cardStateRepo;
        this.wordRepo = wordRepo;
        this.reviewLogRepo = reviewLogRepo;
        this.sm2Scheduler = sm2Scheduler;
        this.studyClock = studyClock;
    }

    /**
     * Process one review for the given user + word.
     * Lazy-creates a NEW state row if the word has never been queued for this user.
     *
     * @throws VocabWordNotFoundException if wordId does not exist in vocab_word table
     */
    @Transactional
    public ReviewResultResponse review(String userSub, ReviewRequest req) {
        Long wordId = req.wordId();

        // Load the word (404 if not) — also gives the deck id for the review-log row.
        VocabWord word = wordRepo.findById(wordId)
                .orElseThrow(() -> new VocabWordNotFoundException(wordId));

        // Load existing state or lazy-create
        UserCardState state = cardStateRepo
                .findByUserSubAndVocabWordId(userSub, wordId)
                .orElseGet(() -> cardStateRepo.save(UserCardState.newCard(userSub, wordId)));

        // Capture the state BEFORE scheduling mutates the managed entity (for the log's prev_state).
        CardLearningState prevState = state.getState();

        // Run pure scheduler — no I/O inside
        Sm2Input input = new Sm2Input(
                state.getEaseFactor(),
                state.getIntervalDays(),
                state.getRepetitions(),
                state.getLapses(),
                req.rating());
        Sm2Result result = sm2Scheduler.schedule(input);

        // Apply scheduling: dueOn = today(Saigon) + intervalDays
        Instant reviewedAt = studyClock.now();
        LocalDate dueOn = studyClock.today().plusDays(result.intervalDays());
        state.applyScheduling(
                result.state(),
                result.easeFactor(),
                result.intervalDays(),
                result.repetitions(),
                result.lapses(),
                dueOn,
                reviewedAt);
        cardStateRepo.save(state);

        // Append one immutable review-log row (forward-only history for the progress dashboard).
        reviewLogRepo.save(ReviewLog.of(
                userSub,
                wordId,
                word.getDeck().getId(),
                req.rating().name(),
                prevState,
                result.state(),
                result.intervalDays(),
                result.easeFactor(),
                reviewedAt));

        return new ReviewResultResponse(
                wordId,
                state.getState(),
                state.getIntervalDays(),
                state.getDueOn(),
                state.getRepetitions(),
                state.getEaseFactor(),
                state.getLapses());
    }
}
