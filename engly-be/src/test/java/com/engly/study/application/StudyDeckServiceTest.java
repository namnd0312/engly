package com.engly.study.application;

import com.engly.study.api.dto.StudyDeckSummary;
import com.engly.study.domain.CardLearningState;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import com.engly.vocab.domain.VocabDeck;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito unit tests for StudyDeckService — no Spring context, no DB.
 * Verifies per-deck count calculations (new/due/learned) and empty-list path.
 */
@ExtendWith(MockitoExtension.class)
class StudyDeckServiceTest {

    // Fixed clock: 2026-06-15T00:00:00Z = 2026-06-15 Saigon
    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-15T00:00:00Z");
    private static final ZoneId SAIGON = ZoneId.of("Asia/Saigon");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static final List<CardLearningState> ACTIVE_STATES =
            List.of(CardLearningState.LEARNING, CardLearningState.REVIEW);

    @Mock private VocabDeckJpaRepository deckRepo;
    @Mock private VocabWordJpaRepository wordRepo;
    @Mock private UserCardStateJpaRepository cardStateRepo;

    private StudyDeckService service;

    @BeforeEach
    void setUp() {
        StudyClock studyClock = new StudyClock(Clock.fixed(FIXED_INSTANT, SAIGON));
        service = new StudyDeckService(deckRepo, wordRepo, cardStateRepo, studyClock);
    }

    // -----------------------------------------------------------------------
    // Empty deck list → empty summaries
    // -----------------------------------------------------------------------

    @Test
    void listDecks_noDecks_returnsEmptyList() {
        when(deckRepo.findAll()).thenReturn(List.of());

        List<StudyDeckSummary> result = service.listDecks("user-1");

        assertThat(result).isEmpty();
        verifyNoInteractions(wordRepo, cardStateRepo);
    }

    // -----------------------------------------------------------------------
    // Single deck — fresh user (no states) → newCount = total, due/learned = 0
    // -----------------------------------------------------------------------

    @Test
    void listDecks_freshUser_allWordsNew() {
        String userSub = "user-2";
        VocabDeck deck = makeDeck(1L, "Deck A", "deck-a", "General");

        when(deckRepo.findAll()).thenReturn(List.of(deck));
        when(wordRepo.countByDeck_Id(1L)).thenReturn(10L);
        when(cardStateRepo.countByUserSubAndDeckId(userSub, 1L)).thenReturn(0L);
        when(cardStateRepo.countDueByUserSubAndDeckId(
                eq(userSub), eq(1L), eq(ACTIVE_STATES), eq(TODAY))).thenReturn(0L);
        when(cardStateRepo.countByUserSubAndDeckIdAndState(
                userSub, 1L, CardLearningState.REVIEW)).thenReturn(0L);

        List<StudyDeckSummary> summaries = service.listDecks(userSub);

        assertThat(summaries).hasSize(1);
        StudyDeckSummary s = summaries.get(0);
        assertThat(s.deckId()).isEqualTo(1L);
        assertThat(s.name()).isEqualTo("Deck A");
        assertThat(s.slug()).isEqualTo("deck-a");
        assertThat(s.topic()).isEqualTo("General");
        assertThat(s.newCount()).isEqualTo(10L);
        assertThat(s.dueCount()).isEqualTo(0L);
        assertThat(s.learnedCount()).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // newCount floor at 0 — wordsWithState > total (data anomaly) must not go negative
    // -----------------------------------------------------------------------

    @Test
    void listDecks_newCountFloorsAtZeroWhenStateExceedsTotal() {
        String userSub = "user-3";
        VocabDeck deck = makeDeck(2L, "Deck B", "deck-b", "Travel");

        when(deckRepo.findAll()).thenReturn(List.of(deck));
        when(wordRepo.countByDeck_Id(2L)).thenReturn(5L);
        // More state rows than total words (anomaly → floor at 0)
        when(cardStateRepo.countByUserSubAndDeckId(userSub, 2L)).thenReturn(7L);
        when(cardStateRepo.countDueByUserSubAndDeckId(
                eq(userSub), eq(2L), eq(ACTIVE_STATES), eq(TODAY))).thenReturn(3L);
        when(cardStateRepo.countByUserSubAndDeckIdAndState(
                userSub, 2L, CardLearningState.REVIEW)).thenReturn(4L);

        List<StudyDeckSummary> summaries = service.listDecks(userSub);

        assertThat(summaries.get(0).newCount()).isEqualTo(0L); // Math.max(0, 5-7)=0
        assertThat(summaries.get(0).dueCount()).isEqualTo(3L);
        assertThat(summaries.get(0).learnedCount()).isEqualTo(4L);
    }

    // -----------------------------------------------------------------------
    // dueCount uses Saigon today — verify the correct LocalDate is passed to repo
    // -----------------------------------------------------------------------

    @Test
    void listDecks_dueCountUsesTodaySaigon() {
        String userSub = "user-4";
        VocabDeck deck = makeDeck(3L, "Deck C", "deck-c", "Business");

        when(deckRepo.findAll()).thenReturn(List.of(deck));
        when(wordRepo.countByDeck_Id(3L)).thenReturn(8L);
        when(cardStateRepo.countByUserSubAndDeckId(userSub, 3L)).thenReturn(3L);
        when(cardStateRepo.countDueByUserSubAndDeckId(
                eq(userSub), eq(3L), eq(ACTIVE_STATES), eq(TODAY))).thenReturn(2L);
        when(cardStateRepo.countByUserSubAndDeckIdAndState(
                userSub, 3L, CardLearningState.REVIEW)).thenReturn(1L);

        service.listDecks(userSub);

        // Verify Saigon today (not LocalDate.now()) is passed to the due-count query
        verify(cardStateRepo).countDueByUserSubAndDeckId(
                eq(userSub), eq(3L), eq(ACTIVE_STATES), eq(TODAY));
    }

    // -----------------------------------------------------------------------
    // Multiple decks — counts are per-deck, not mixed
    // -----------------------------------------------------------------------

    @Test
    void listDecks_multipleDecks_eachSummaryIsIndependent() {
        String userSub = "user-5";
        VocabDeck deckA = makeDeck(10L, "A", "a", "TopicA");
        VocabDeck deckB = makeDeck(11L, "B", "b", "TopicB");

        when(deckRepo.findAll()).thenReturn(List.of(deckA, deckB));

        when(wordRepo.countByDeck_Id(10L)).thenReturn(20L);
        when(cardStateRepo.countByUserSubAndDeckId(userSub, 10L)).thenReturn(10L);
        when(cardStateRepo.countDueByUserSubAndDeckId(
                eq(userSub), eq(10L), eq(ACTIVE_STATES), eq(TODAY))).thenReturn(3L);
        when(cardStateRepo.countByUserSubAndDeckIdAndState(
                userSub, 10L, CardLearningState.REVIEW)).thenReturn(2L);

        when(wordRepo.countByDeck_Id(11L)).thenReturn(5L);
        when(cardStateRepo.countByUserSubAndDeckId(userSub, 11L)).thenReturn(0L);
        when(cardStateRepo.countDueByUserSubAndDeckId(
                eq(userSub), eq(11L), eq(ACTIVE_STATES), eq(TODAY))).thenReturn(0L);
        when(cardStateRepo.countByUserSubAndDeckIdAndState(
                userSub, 11L, CardLearningState.REVIEW)).thenReturn(0L);

        List<StudyDeckSummary> summaries = service.listDecks(userSub);

        assertThat(summaries).hasSize(2);
        StudyDeckSummary a = summaries.get(0);
        assertThat(a.deckId()).isEqualTo(10L);
        assertThat(a.newCount()).isEqualTo(10L); // 20 - 10
        assertThat(a.dueCount()).isEqualTo(3L);
        assertThat(a.learnedCount()).isEqualTo(2L);

        StudyDeckSummary b = summaries.get(1);
        assertThat(b.deckId()).isEqualTo(11L);
        assertThat(b.newCount()).isEqualTo(5L); // 5 - 0
        assertThat(b.dueCount()).isEqualTo(0L);
        assertThat(b.learnedCount()).isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private VocabDeck makeDeck(Long id, String name, String slug, String topic) {
        VocabDeck d = new VocabDeck();
        setField(d, "id", id);
        setField(d, "name", name);
        d.setSlug(slug);
        setField(d, "topic", topic);
        return d;
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
