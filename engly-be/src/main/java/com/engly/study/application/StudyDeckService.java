package com.engly.study.application;

import com.engly.study.api.dto.StudyDeckSummary;
import com.engly.study.domain.CardLearningState;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import com.engly.vocab.domain.VocabDeck;
import com.engly.vocab.infrastructure.VocabDeckJpaRepository;
import com.engly.vocab.infrastructure.VocabWordJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Provides per-deck count summaries for the study deck list (GET /api/study/decks).
 * newCount    = total words in deck - words already having a state row for this user.
 * dueCount    = state rows for user+deck where due_on <= today AND state IN (LEARNING, REVIEW).
 * learnedCount = state rows with state = REVIEW for user+deck.
 */
@Service
@Transactional(readOnly = true)
public class StudyDeckService {

    private static final List<CardLearningState> ACTIVE_STATES =
            List.of(CardLearningState.LEARNING, CardLearningState.REVIEW);

    private final VocabDeckJpaRepository deckRepo;
    private final VocabWordJpaRepository wordRepo;
    private final UserCardStateJpaRepository cardStateRepo;
    private final StudyClock studyClock;

    public StudyDeckService(VocabDeckJpaRepository deckRepo,
                            VocabWordJpaRepository wordRepo,
                            UserCardStateJpaRepository cardStateRepo,
                            StudyClock studyClock) {
        this.deckRepo = deckRepo;
        this.wordRepo = wordRepo;
        this.cardStateRepo = cardStateRepo;
        this.studyClock = studyClock;
    }

    /** Returns all decks with per-user SRS counts. */
    public List<StudyDeckSummary> listDecks(String userSub) {
        LocalDate today = studyClock.today();
        return deckRepo.findAll().stream()
                .map(deck -> buildSummary(deck, userSub, today))
                .toList();
    }

    private StudyDeckSummary buildSummary(VocabDeck deck, String userSub, LocalDate today) {
        long totalWords = wordRepo.countByDeck_Id(deck.getId());
        long wordsWithState = cardStateRepo.countByUserSubAndDeckId(userSub, deck.getId());
        long newCount = Math.max(0, totalWords - wordsWithState);

        long dueCount = cardStateRepo.countDueByUserSubAndDeckId(
                userSub, deck.getId(), ACTIVE_STATES, today);
        long learnedCount = cardStateRepo.countByUserSubAndDeckIdAndState(
                userSub, deck.getId(), CardLearningState.REVIEW);

        return new StudyDeckSummary(
                deck.getId(), deck.getName(), deck.getSlug(),
                deck.getTopic(), newCount, dueCount, learnedCount);
    }
}
