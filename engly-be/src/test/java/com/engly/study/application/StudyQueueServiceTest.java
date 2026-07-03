package com.engly.study.application;

import com.engly.study.api.dto.StudyCardResponse;
import com.engly.study.domain.CardLearningState;
import com.engly.study.domain.UserCardState;
import com.engly.study.domain.UserStudySettings;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import com.engly.study.infrastructure.UserStudySettingsJpaRepository;
import com.engly.vocab.domain.VocabDeck;
import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.domain.VocabWord;
import com.engly.vocab.infrastructure.VocabDeckJpaRepository;
import com.engly.vocab.infrastructure.VocabWordJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito unit tests for StudyQueueService — no Spring context, no DB.
 * Covers: deck-not-found, due-card mapping, allowance math (full/partial/zero),
 * settings lazy-create, empty deck, and null-word skip defense.
 */
@ExtendWith(MockitoExtension.class)
class StudyQueueServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-15T00:00:00Z");
    private static final ZoneId SAIGON = ZoneId.of("Asia/Saigon");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static final List<CardLearningState> ACTIVE_STATES =
            List.of(CardLearningState.LEARNING, CardLearningState.REVIEW);

    @Mock private VocabDeckJpaRepository deckRepo;
    @Mock private VocabWordJpaRepository wordRepo;
    @Mock private UserCardStateJpaRepository cardStateRepo;
    @Mock private UserStudySettingsJpaRepository settingsRepo;

    private StudyQueueService service;

    @BeforeEach
    void setUp() {
        StudyClock studyClock = new StudyClock(Clock.fixed(FIXED_INSTANT, SAIGON));
        service = new StudyQueueService(deckRepo, wordRepo, cardStateRepo, settingsRepo, studyClock);
    }

    // -----------------------------------------------------------------------
    // deck not found → VocabDeckNotFoundException
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_deckNotFound_throwsVocabDeckNotFoundException() {
        when(deckRepo.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.buildQueue("user-1", 99L))
                .isInstanceOf(VocabDeckNotFoundException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(wordRepo, cardStateRepo, settingsRepo);
    }

    // -----------------------------------------------------------------------
    // due cards mapped correctly (2 due states → 2 responses)
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_dueCards_returnedInOrder() {
        String userSub = "user-2";
        Long deckId = 1L;

        when(deckRepo.existsById(deckId)).thenReturn(true);

        // 2 due states
        UserCardState s1 = reviewStateWithDueOn(userSub, 101L, TODAY.minusDays(1));
        UserCardState s2 = reviewStateWithDueOn(userSub, 102L, TODAY);
        when(cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, TODAY))
                .thenReturn(List.of(s1, s2));

        // Batch fetch their words
        VocabWord w1 = makeWord(101L, "apple", "táo");
        VocabWord w2 = makeWord(102L, "book", "sách");
        when(wordRepo.findAllById(List.of(101L, 102L))).thenReturn(List.of(w1, w2));

        // Settings (no new cards needed just for this test — allowance would add more)
        stubSettingsWithLimit(userSub, 0);  // limit=0 → no new cards
        when(cardStateRepo.countIntroducedToday(userSub, TODAY)).thenReturn(0L);

        List<StudyCardResponse> result = service.buildQueue(userSub, deckId);

        // Due cards first (2 cards from due states, 0 new due to allowance=0)
        assertThat(result).hasSize(2);
        assertThat(result.get(0).wordId()).isEqualTo(101L);
        assertThat(result.get(0).term()).isEqualTo("apple");
        assertThat(result.get(1).wordId()).isEqualTo(102L);
        assertThat(result.get(1).term()).isEqualTo("book");
    }

    // -----------------------------------------------------------------------
    // allowance full: limit=20, introduced=0 → 3 new words created
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_allowanceFull_lazyCreatesNewCards() {
        String userSub = "user-3";
        Long deckId = 2L;

        when(deckRepo.existsById(deckId)).thenReturn(true);
        when(cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, TODAY))
                .thenReturn(List.of());

        stubSettingsWithLimit(userSub, 20);
        when(cardStateRepo.countIntroducedToday(userSub, TODAY)).thenReturn(0L);

        // 3 words in deck, none with existing state
        when(cardStateRepo.findWordIdsWithStateForDeck(userSub, deckId)).thenReturn(List.of());
        when(wordRepo.findIdsByDeckId(deckId)).thenReturn(List.of(201L, 202L, 203L));

        VocabWord w1 = makeWord(201L, "cat", "mèo");
        VocabWord w2 = makeWord(202L, "dog", "chó");
        VocabWord w3 = makeWord(203L, "fish", "cá");
        when(wordRepo.findAllById(List.of(201L, 202L, 203L))).thenReturn(List.of(w1, w2, w3));

        // save(newCard) returns each card (Mockito returns arg by default, but we stub explicitly)
        when(cardStateRepo.save(any(UserCardState.class))).thenAnswer(inv -> inv.getArgument(0));

        List<StudyCardResponse> result = service.buildQueue(userSub, deckId);

        assertThat(result).hasSize(3);
        // All 3 cards should have state=NEW
        assertThat(result).allMatch(c -> c.state() == CardLearningState.NEW);
        // save called 3 times (one per new card)
        verify(cardStateRepo, times(3)).save(any(UserCardState.class));
    }

    // -----------------------------------------------------------------------
    // allowance partial: introduced=15, limit=20 → at most 5 new
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_allowancePartial_limitsNewCards() {
        String userSub = "user-4";
        Long deckId = 3L;

        when(deckRepo.existsById(deckId)).thenReturn(true);
        when(cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, TODAY))
                .thenReturn(List.of());

        stubSettingsWithLimit(userSub, 20);
        when(cardStateRepo.countIntroducedToday(userSub, TODAY)).thenReturn(15L);

        // 10 potential new words in deck
        when(cardStateRepo.findWordIdsWithStateForDeck(userSub, deckId)).thenReturn(List.of());
        List<Long> ids = List.of(301L, 302L, 303L, 304L, 305L, 306L, 307L, 308L, 309L, 310L);
        when(wordRepo.findIdsByDeckId(deckId)).thenReturn(ids);

        // Only first 5 should be fetched (allowance = 20 - 15 = 5)
        List<Long> limitedIds = List.of(301L, 302L, 303L, 304L, 305L);
        List<VocabWord> words = limitedIds.stream()
                .map(id -> makeWord(id, "word-" + id, "meaning-" + id))
                .toList();
        when(wordRepo.findAllById(limitedIds)).thenReturn(words);
        when(cardStateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<StudyCardResponse> result = service.buildQueue(userSub, deckId);

        assertThat(result).hasSize(5);
    }

    // -----------------------------------------------------------------------
    // allowance zero: introduced >= limit → no new cards saved
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_allowanceZero_noNewCardsSaved() {
        String userSub = "user-5";
        Long deckId = 4L;

        when(deckRepo.existsById(deckId)).thenReturn(true);
        when(cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, TODAY))
                .thenReturn(List.of());

        stubSettingsWithLimit(userSub, 20);
        // User has already hit their daily limit
        when(cardStateRepo.countIntroducedToday(userSub, TODAY)).thenReturn(20L);

        List<StudyCardResponse> result = service.buildQueue(userSub, deckId);

        assertThat(result).isEmpty();
        verify(cardStateRepo, never()).save(any());
        verify(wordRepo, never()).findIdsByDeckId(any());
    }

    // -----------------------------------------------------------------------
    // settings lazy-create: no settings row → save defaults
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_noSettingsRow_lazyCreatesDefaults() {
        String userSub = "user-6";
        Long deckId = 5L;

        when(deckRepo.existsById(deckId)).thenReturn(true);
        when(cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, TODAY))
                .thenReturn(List.of());

        // No settings row → save defaults
        UserStudySettings defaults = UserStudySettings.defaults(userSub);
        when(settingsRepo.findById(userSub)).thenReturn(Optional.empty());
        when(settingsRepo.save(any(UserStudySettings.class))).thenReturn(defaults);

        // allowance = 20, no introduced yet
        when(cardStateRepo.countIntroducedToday(userSub, TODAY)).thenReturn(0L);
        when(cardStateRepo.findWordIdsWithStateForDeck(userSub, deckId)).thenReturn(List.of());
        when(wordRepo.findIdsByDeckId(deckId)).thenReturn(List.of());

        service.buildQueue(userSub, deckId);

        verify(settingsRepo).save(any(UserStudySettings.class));
    }

    // -----------------------------------------------------------------------
    // empty deck: no due, no new word ids → empty list
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_emptyDeck_returnsEmptyList() {
        String userSub = "user-7";
        Long deckId = 6L;

        when(deckRepo.existsById(deckId)).thenReturn(true);
        when(cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, TODAY))
                .thenReturn(List.of());

        stubSettingsWithLimit(userSub, 20);
        when(cardStateRepo.countIntroducedToday(userSub, TODAY)).thenReturn(0L);
        when(cardStateRepo.findWordIdsWithStateForDeck(userSub, deckId)).thenReturn(List.of());
        when(wordRepo.findIdsByDeckId(deckId)).thenReturn(List.of());

        List<StudyCardResponse> result = service.buildQueue(userSub, deckId);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // due word missing from findAllById (null map entry) → skipped, no NPE
    // -----------------------------------------------------------------------

    @Test
    void buildQueue_dueWordMissingFromBatchFetch_isSkippedSafely() {
        String userSub = "user-8";
        Long deckId = 7L;

        when(deckRepo.existsById(deckId)).thenReturn(true);

        // 2 due states; word for s2 will be missing from batch result
        UserCardState s1 = reviewStateWithDueOn(userSub, 401L, TODAY);
        UserCardState s2 = reviewStateWithDueOn(userSub, 402L, TODAY.minusDays(1));
        when(cardStateRepo.findDueCards(userSub, deckId, ACTIVE_STATES, TODAY))
                .thenReturn(List.of(s1, s2));

        // Only word 401 returned (402 is "missing")
        when(wordRepo.findAllById(List.of(401L, 402L)))
                .thenReturn(List.of(makeWord(401L, "sun", "mặt trời")));

        stubSettingsWithLimit(userSub, 0); // no new cards
        when(cardStateRepo.countIntroducedToday(userSub, TODAY)).thenReturn(0L);

        List<StudyCardResponse> result = service.buildQueue(userSub, deckId);

        // Only 1 card returned (s2 skipped because word was null in map)
        assertThat(result).hasSize(1);
        assertThat(result.get(0).wordId()).isEqualTo(401L);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubSettingsWithLimit(String userSub, int limit) {
        UserStudySettings settings = UserStudySettings.defaults(userSub);
        settings.update(limit, null);
        when(settingsRepo.findById(userSub)).thenReturn(Optional.of(settings));
    }

    private UserCardState reviewStateWithDueOn(String userSub, Long wordId, LocalDate dueOn) {
        UserCardState s = UserCardState.newCard(userSub, wordId);
        s.applyScheduling(CardLearningState.REVIEW, 2.5, 1, 1, 0, dueOn, FIXED_INSTANT);
        return s;
    }

    private VocabWord makeWord(Long id, String term, String meaningVi) {
        VocabWord w = new VocabWord();
        setField(w, "id", id);
        setField(w, "term", term);
        setField(w, "meaningVi", meaningVi);
        setField(w, "ipa", null);
        setField(w, "exampleEn", null);
        setField(w, "exampleVi", null);
        setField(w, "partOfSpeech", null);
        return w;
    }

    private void setField(Object obj, String fieldName, Object value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
