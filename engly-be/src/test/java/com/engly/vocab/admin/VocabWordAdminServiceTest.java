package com.engly.vocab.admin;

import com.engly.vocab.api.dto.VocabWordPageResponse;
import com.engly.vocab.api.dto.VocabWordRequest;
import com.engly.vocab.api.dto.VocabWordResponse;
import com.engly.vocab.domain.VocabDeck;
import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.domain.VocabWord;
import com.engly.vocab.domain.VocabWordNotFoundException;
import com.engly.vocab.infrastructure.VocabDeckJpaRepository;
import com.engly.vocab.infrastructure.VocabWordJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VocabWordAdminServiceTest {

    @Mock
    private VocabWordJpaRepository wordRepository;

    @Mock
    private VocabDeckJpaRepository deckRepository;

    private VocabWordAdminService service;

    @BeforeEach
    void setUp() {
        service = new VocabWordAdminService(wordRepository, deckRepository);
    }

    // --- 4-branch search router ---

    @Test
    void search_bothDeckIdAndQ_callsSearchByDeck() {
        Page<VocabWord> page = new PageImpl<>(List.of(makeWord(1L, makeDeck(1L))),
                PageRequest.of(0, 20), 1);
        when(wordRepository.searchByDeck(eq(1L), eq("apple"), any(Pageable.class))).thenReturn(page);

        service.search("apple", 1L, 0, 20);

        verify(wordRepository).searchByDeck(eq(1L), eq("apple"), any(Pageable.class));
        verify(wordRepository, never()).findByDeck_Id(any(), any(Pageable.class));
        verify(wordRepository, never()).search(any(), any(Pageable.class));
        verify(wordRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void search_deckIdOnlyNullQ_callsFindByDeckId() {
        when(wordRepository.findByDeck_Id(eq(1L), any(Pageable.class))).thenReturn(Page.empty());

        service.search(null, 1L, 0, 20);

        verify(wordRepository).findByDeck_Id(eq(1L), any(Pageable.class));
        verify(wordRepository, never()).searchByDeck(any(), any(), any(Pageable.class));
        verify(wordRepository, never()).search(any(), any(Pageable.class));
        verify(wordRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void search_deckIdOnlyBlankQ_callsFindByDeckId() {
        // blank q → hasQ=false → routes to findByDeck_Id branch
        when(wordRepository.findByDeck_Id(eq(1L), any(Pageable.class))).thenReturn(Page.empty());

        service.search("   ", 1L, 0, 20);

        verify(wordRepository).findByDeck_Id(eq(1L), any(Pageable.class));
    }

    @Test
    void search_qOnlyNullDeckId_callsSearch() {
        when(wordRepository.search(eq("apple"), any(Pageable.class))).thenReturn(Page.empty());

        service.search("apple", null, 0, 20);

        verify(wordRepository).search(eq("apple"), any(Pageable.class));
        verify(wordRepository, never()).searchByDeck(any(), any(), any(Pageable.class));
        verify(wordRepository, never()).findByDeck_Id(any(), any(Pageable.class));
        verify(wordRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void search_nullQAndNullDeckId_callsFindAll() {
        when(wordRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        service.search(null, null, 0, 20);

        verify(wordRepository).findAll(any(Pageable.class));
        verify(wordRepository, never()).searchByDeck(any(), any(), any(Pageable.class));
        verify(wordRepository, never()).findByDeck_Id(any(), any(Pageable.class));
        verify(wordRepository, never()).search(any(), any(Pageable.class));
    }

    // --- LIKE escaping ---

    /**
     * Escape order: '!' → '!!' FIRST, then '%' → '!%', then '_' → '!_'.
     * Input "a%_!" → "a!%!_!!"
     */
    @Test
    void search_likeEscaping_allSpecialChars_globalSearch() {
        when(wordRepository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("a%_!", null, 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(wordRepository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("a!%!_!!");
    }

    @Test
    void search_likeEscaping_percentOnly_searchByDeck() {
        when(wordRepository.searchByDeck(any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("test%wildcard", 1L, 0, 20);

        ArgumentCaptor<String> qCaptor = ArgumentCaptor.forClass(String.class);
        verify(wordRepository).searchByDeck(eq(1L), qCaptor.capture(), any(Pageable.class));
        assertThat(qCaptor.getValue()).isEqualTo("test!%wildcard");
    }

    @Test
    void search_likeEscaping_underscoreOnly_globalSearch() {
        when(wordRepository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("test_underscore", null, 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(wordRepository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("test!_underscore");
    }

    @Test
    void search_likeEscaping_exclamationMark_globalSearch() {
        when(wordRepository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("test!exclaim", null, 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(wordRepository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("test!!exclaim");
    }

    // --- paging validation ---

    @Test
    void search_pageNegative_throwsIllegalArgument_noRepoCall() {
        assertThatThrownBy(() -> service.search("apple", null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page must be >= 0");
        verifyNoInteractions(wordRepository, deckRepository);
    }

    @Test
    void search_sizeZero_throwsIllegalArgument_noRepoCall() {
        assertThatThrownBy(() -> service.search("apple", null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between");
        verifyNoInteractions(wordRepository, deckRepository);
    }

    @Test
    void search_sizeTooLarge_throwsIllegalArgument_noRepoCall() {
        assertThatThrownBy(() -> service.search("apple", null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between");
        verifyNoInteractions(wordRepository, deckRepository);
    }

    @Test
    void search_sizeAtMax_passes() {
        when(wordRepository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        assertThatCode(() -> service.search("apple", null, 0, 100)).doesNotThrowAnyException();
    }

    @Test
    void search_returnsPagedDtos() {
        VocabDeck deck = makeDeck(1L);
        VocabWord word = makeWord(10L, deck);
        Page<VocabWord> page = new PageImpl<>(List.of(word), PageRequest.of(0, 20), 1);
        when(wordRepository.findAll(any(Pageable.class))).thenReturn(page);

        VocabWordPageResponse result = service.search(null, null, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).id()).isEqualTo(10L);
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(0);
    }

    // --- getById ---

    @Test
    void getById_found_returnsMappedDto() {
        VocabDeck deck = makeDeck(1L);
        VocabWord word = makeWord(5L, deck);
        when(wordRepository.findById(5L)).thenReturn(Optional.of(word));

        VocabWordResponse result = service.getById(5L);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.deckId()).isEqualTo(1L);
    }

    @Test
    void getById_notFound_throwsVocabWordNotFoundException() {
        when(wordRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(VocabWordNotFoundException.class)
                .hasMessageContaining("99");
    }

    // --- create ---

    @Test
    void create_deckExists_savesAndReturnsDto() {
        VocabDeck deck = makeDeck(1L);
        when(deckRepository.findById(1L)).thenReturn(Optional.of(deck));
        VocabWord saved = makeWord(20L, deck);
        when(wordRepository.save(any(VocabWord.class))).thenReturn(saved);

        VocabWordRequest req = new VocabWordRequest(1L, "apple", "quả táo", "/ˈæp.əl/",
                "I eat an apple.", "Tôi ăn một quả táo.", "noun", true);
        VocabWordResponse result = service.create(req);

        verify(wordRepository).save(any(VocabWord.class));
        assertThat(result.id()).isEqualTo(20L);
        assertThat(result.deckId()).isEqualTo(1L);
    }

    @Test
    void create_deckNotFound_throwsVocabDeckNotFoundException() {
        when(deckRepository.findById(999L)).thenReturn(Optional.empty());

        VocabWordRequest req = new VocabWordRequest(999L, "apple", "quả táo", null,
                null, null, null, false);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(VocabDeckNotFoundException.class)
                .hasMessageContaining("999");
        verify(wordRepository, never()).save(any());
    }

    // --- update ---

    @Test
    void update_wordNotFound_throwsVocabWordNotFoundException() {
        when(wordRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L,
                new VocabWordRequest(1L, "apple", "quả táo", null, null, null, null, false)))
                .isInstanceOf(VocabWordNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void update_deckNotFound_throwsVocabDeckNotFoundException() {
        VocabDeck deck = makeDeck(1L);
        VocabWord word = makeWord(5L, deck);
        when(wordRepository.findById(5L)).thenReturn(Optional.of(word));
        when(deckRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(5L,
                new VocabWordRequest(999L, "apple", "quả táo", null, null, null, null, false)))
                .isInstanceOf(VocabDeckNotFoundException.class)
                .hasMessageContaining("999");
        verify(wordRepository, never()).save(any());
    }

    @Test
    void update_found_reassignsAndSaves() {
        VocabDeck deck1 = makeDeck(1L);
        VocabDeck deck2 = makeDeck(2L);
        VocabWord word = makeWord(5L, deck1);
        when(wordRepository.findById(5L)).thenReturn(Optional.of(word));
        when(deckRepository.findById(2L)).thenReturn(Optional.of(deck2));
        when(wordRepository.save(any(VocabWord.class))).thenAnswer(inv -> inv.getArgument(0));

        VocabWordResponse result = service.update(5L,
                new VocabWordRequest(2L, "orange", "quả cam", null, null, null, "noun", false));

        assertThat(result.deckId()).isEqualTo(2L);
        assertThat(result.term()).isEqualTo("orange");
        verify(wordRepository).save(word);
    }

    // --- toggleVerified ---

    @Test
    void toggleVerified_notFound_throwsVocabWordNotFoundException() {
        when(wordRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleVerified(99L, true))
                .isInstanceOf(VocabWordNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void toggleVerified_setsVerifiedTrueAndSaves() {
        VocabDeck deck = makeDeck(1L);
        VocabWord word = makeWord(5L, deck);
        // word starts unverified (default false)
        when(wordRepository.findById(5L)).thenReturn(Optional.of(word));
        when(wordRepository.save(any(VocabWord.class))).thenAnswer(inv -> inv.getArgument(0));

        VocabWordResponse result = service.toggleVerified(5L, true);

        assertThat(result.verified()).isTrue();
        // assert save was called with the same word object (no new instance)
        ArgumentCaptor<VocabWord> captor = ArgumentCaptor.forClass(VocabWord.class);
        verify(wordRepository).save(captor.capture());
        assertThat(captor.getValue().isVerified()).isTrue();
    }

    @Test
    void toggleVerified_setsVerifiedFalse() {
        VocabDeck deck = makeDeck(1L);
        VocabWord word = makeWordVerified(5L, deck, true);
        when(wordRepository.findById(5L)).thenReturn(Optional.of(word));
        when(wordRepository.save(any(VocabWord.class))).thenAnswer(inv -> inv.getArgument(0));

        VocabWordResponse result = service.toggleVerified(5L, false);

        assertThat(result.verified()).isFalse();
    }

    // --- delete ---

    @Test
    void delete_notFound_throwsVocabWordNotFoundException() {
        when(wordRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(VocabWordNotFoundException.class)
                .hasMessageContaining("99");
        verify(wordRepository, never()).deleteById(any());
    }

    @Test
    void delete_found_callsDeleteById() {
        when(wordRepository.existsById(7L)).thenReturn(true);

        service.delete(7L);

        verify(wordRepository).deleteById(7L);
    }

    // --- helpers ---

    private VocabDeck makeDeck(Long id) {
        try {
            VocabDeck d = new VocabDeck();
            setField(d, "id", id);
            d.setSlug("deck-" + id);
            d.update("Deck " + id, "desc", "topic");
            return d;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private VocabWord makeWord(Long id, VocabDeck deck) {
        return makeWordVerified(id, deck, false);
    }

    private VocabWord makeWordVerified(Long id, VocabDeck deck, boolean verified) {
        try {
            VocabWord w = new VocabWord();
            setField(w, "id", id);
            w.setDeck(deck);
            w.update("term-" + id, "nghĩa-" + id, null, null, null, "noun");
            w.setVerified(verified);
            return w;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
