package com.engly.study.application;

import com.engly.study.api.dto.StudyCardResponse;
import com.engly.study.domain.CardLearningState;
import com.engly.study.domain.UserCardState;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import com.engly.study.infrastructure.UserStudySettingsJpaRepository;
import com.engly.study.domain.UserStudySettings;
import com.engly.vocab.domain.VocabWord;
import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.infrastructure.VocabDeckJpaRepository;
import com.engly.vocab.infrastructure.VocabWordJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the study session queue for a given user + deck.
 * Order: due review/learning cards first, then new cards up to daily allowance.
 * New card rows (state=NEW) are lazily created here on first queue encounter.
 */
@Service
public class StudyQueueService {

    private static final List<CardLearningState> ACTIVE_STATES =
            List.of(CardLearningState.LEARNING, CardLearningState.REVIEW);

    private final VocabDeckJpaRepository deckRepo;
    private final VocabWordJpaRepository wordRepo;
    private final UserCardStateJpaRepository cardStateRepo;
    private final UserStudySettingsJpaRepository settingsRepo;
    private final StudyClock studyClock;

    public StudyQueueService(VocabDeckJpaRepository deckRepo,
                             VocabWordJpaRepository wordRepo,
                             UserCardStateJpaRepository cardStateRepo,
                             UserStudySettingsJpaRepository settingsRepo,
                             StudyClock studyClock) {
        this.deckRepo = deckRepo;
        this.wordRepo = wordRepo;
        this.cardStateRepo = cardStateRepo;
        this.settingsRepo = settingsRepo;
        this.studyClock = studyClock;
    }

    /**
     * Returns today's study queue for the user+deck.
     * 1. Due cards (LEARNING/REVIEW, due_on <= today) — all of them.
     * 2. New words — up to remainingNewAllowance, lazy-creating NEW state rows.
     */
    @Transactional
    public List<StudyCardResponse> buildQueue(String userSub, Long deckId) {
        // 404 guard — deck must exist before building a queue for it
        if (!deckRepo.existsById(deckId)) {
            throw new VocabDeckNotFoundException(deckId);
        }

        LocalDate today = studyClock.today();

        // --- 1. Due cards ---
        List<UserCardState> dueStates = cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, today);

        // --- 2. New cards: compute remaining allowance ---
        UserStudySettings settings = settingsRepo.findById(userSub)
                .orElseGet(() -> settingsRepo.save(UserStudySettings.defaults(userSub)));
        long introducedToday = cardStateRepo.countIntroducedToday(userSub, today);
        int allowance = Math.max(0, settings.getNewCardsPerDay() - (int) introducedToday);

        List<StudyCardResponse> result = new ArrayList<>();

        // Map due states to responses (need word data — batch fetch by IDs)
        if (!dueStates.isEmpty()) {
            List<Long> dueWordIds = dueStates.stream()
                    .map(UserCardState::getVocabWordId).toList();
            // Load word entities in one query to avoid N+1
            List<VocabWord> dueWords = wordRepo.findAllById(dueWordIds);
            Map<Long, VocabWord> wordById = dueWords.stream()
                    .collect(Collectors.toMap(VocabWord::getId, w -> w));

            for (UserCardState state : dueStates) {
                VocabWord word = wordById.get(state.getVocabWordId());
                if (word != null) {
                    result.add(toCardResponse(state, word));
                }
            }
        }

        // --- New words ---
        if (allowance > 0) {
            Set<Long> existingWordIds = cardStateRepo
                    .findWordIdsWithStateForDeck(userSub, deckId)
                    .stream().collect(Collectors.toSet());

            List<Long> allDeckWordIds = wordRepo.findIdsByDeckId(deckId);
            List<Long> newWordIds = allDeckWordIds.stream()
                    .filter(id -> !existingWordIds.contains(id))
                    .limit(allowance)
                    .toList();

            if (!newWordIds.isEmpty()) {
                List<VocabWord> newWords = wordRepo.findAllById(newWordIds);
                for (VocabWord word : newWords) {
                    // Lazy-create NEW state row — created_at set by DB default now()
                    UserCardState newState = UserCardState.newCard(userSub, word.getId());
                    newState = cardStateRepo.save(newState);
                    result.add(toCardResponse(newState, word));
                }
            }
        }

        return result;
    }

    private StudyCardResponse toCardResponse(UserCardState state, VocabWord word) {
        return new StudyCardResponse(
                state.getId(),
                word.getId(),
                word.getTerm(),
                word.getMeaningVi(),
                word.getIpa(),
                word.getExampleEn(),
                word.getExampleVi(),
                word.getPartOfSpeech(),
                state.getState());
    }
}
