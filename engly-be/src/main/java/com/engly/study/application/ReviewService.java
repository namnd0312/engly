package com.engly.study.application;

import com.engly.study.api.dto.ReviewRequest;
import com.engly.study.api.dto.ReviewResultResponse;
import com.engly.study.domain.UserCardState;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import com.engly.vocab.domain.VocabWordNotFoundException;
import com.engly.vocab.infrastructure.VocabWordJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final Sm2Scheduler sm2Scheduler;
    private final StudyClock studyClock;

    public ReviewService(UserCardStateJpaRepository cardStateRepo,
                         VocabWordJpaRepository wordRepo,
                         Sm2Scheduler sm2Scheduler,
                         StudyClock studyClock) {
        this.cardStateRepo = cardStateRepo;
        this.wordRepo = wordRepo;
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

        // Validate word exists (404 if not)
        if (!wordRepo.existsById(wordId)) {
            throw new VocabWordNotFoundException(wordId);
        }

        // Load existing state or lazy-create
        UserCardState state = cardStateRepo
                .findByUserSubAndVocabWordId(userSub, wordId)
                .orElseGet(() -> cardStateRepo.save(UserCardState.newCard(userSub, wordId)));

        // Run pure scheduler — no I/O inside
        Sm2Input input = new Sm2Input(
                state.getEaseFactor(),
                state.getIntervalDays(),
                state.getRepetitions(),
                state.getLapses(),
                req.rating());
        Sm2Result result = sm2Scheduler.schedule(input);

        // Apply scheduling: dueOn = today(Saigon) + intervalDays
        LocalDate dueOn = studyClock.today().plusDays(result.intervalDays());
        state.applyScheduling(
                result.state(),
                result.easeFactor(),
                result.intervalDays(),
                result.repetitions(),
                result.lapses(),
                dueOn,
                studyClock.now());
        cardStateRepo.save(state);

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
