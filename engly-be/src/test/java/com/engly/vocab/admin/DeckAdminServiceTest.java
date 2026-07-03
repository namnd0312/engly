package com.engly.vocab.admin;

import com.engly.vocab.api.dto.DeckPageResponse;
import com.engly.vocab.api.dto.DeckRequest;
import com.engly.vocab.api.dto.DeckResponse;
import com.engly.vocab.domain.VocabDeck;
import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.infrastructure.VocabDeckJpaRepository;
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
class DeckAdminServiceTest {

    @Mock
    private VocabDeckJpaRepository repository;

    private DeckAdminService service;

    @BeforeEach
    void setUp() {
        service = new DeckAdminService(repository);
    }

    // --- search: routing ---

    @Test
    void search_nullQ_usesFindAll() {
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        service.search(null, 0, 20);

        verify(repository).findAll(any(Pageable.class));
        verify(repository, never()).search(any(), any(Pageable.class));
    }

    @Test
    void search_blankQ_usesFindAll() {
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        service.search("   ", 0, 20);

        verify(repository).findAll(any(Pageable.class));
        verify(repository, never()).search(any(), any(Pageable.class));
    }

    @Test
    void search_nonBlankQ_usesSearch_withTrimmedTerm() {
        Page<VocabDeck> page = new PageImpl<>(List.of(makeDeck(1L, "Go", "go", "desc", "topic")),
                PageRequest.of(0, 20), 1);
        when(repository.search(eq("go"), any(Pageable.class))).thenReturn(page);

        DeckPageResponse result = service.search("go", 0, 20);

        verify(repository).search(eq("go"), any(Pageable.class));
        verify(repository, never()).findAll(any(Pageable.class));
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).name()).isEqualTo("Go");
    }

    // --- LIKE escaping ---

    /**
     * Escape order: '!' → '!!' FIRST, then '%' → '!%', then '_' → '!_'.
     * Input "a%_!" should become:
     *   step1: '!' → '!!'  → "a%_!!"
     *   step2: '%' → '!%'  → "a!%_!!"
     *   step3: '_' → '!_'  → "a!%!_!!"
     */
    @Test
    void search_likeEscaping_allSpecialChars() {
        when(repository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("a%_!", 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("a!%!_!!");
    }

    @Test
    void search_likeEscaping_percentOnly() {
        when(repository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("test%", 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("test!%");
    }

    @Test
    void search_likeEscaping_underscoreOnly() {
        when(repository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("test_deck", 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("test!_deck");
    }

    @Test
    void search_likeEscaping_escapeCharItself() {
        when(repository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        service.search("test!file", 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(repository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("test!!file");
    }

    // --- paging validation ---

    @Test
    void search_pageNegative_throwsIllegalArgument_noRepoCall() {
        assertThatThrownBy(() -> service.search("go", -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page must be >= 0");
        verifyNoInteractions(repository);
    }

    @Test
    void search_sizeZero_throwsIllegalArgument_noRepoCall() {
        assertThatThrownBy(() -> service.search("go", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between");
        verifyNoInteractions(repository);
    }

    @Test
    void search_sizeTooLarge_throwsIllegalArgument_noRepoCall() {
        assertThatThrownBy(() -> service.search("go", 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between");
        verifyNoInteractions(repository);
    }

    @Test
    void search_sizeAtMax_passes() {
        when(repository.search(any(), any(Pageable.class))).thenReturn(Page.empty());

        assertThatCode(() -> service.search("go", 0, 100)).doesNotThrowAnyException();
    }

    // --- getById ---

    @Test
    void getById_found_returnsMappedDto() {
        VocabDeck deck = makeDeck(42L, "Travel", "travel", "Travel vocabulary", "travel");
        when(repository.findById(42L)).thenReturn(Optional.of(deck));

        DeckResponse result = service.getById(42L);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.name()).isEqualTo("Travel");
        assertThat(result.slug()).isEqualTo("travel");
        assertThat(result.description()).isEqualTo("Travel vocabulary");
        assertThat(result.topic()).isEqualTo("travel");
    }

    @Test
    void getById_notFound_throwsVocabDeckNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(VocabDeckNotFoundException.class)
                .hasMessageContaining("99");
    }

    // --- create ---

    @Test
    void create_savesAndReturnsDto() {
        DeckRequest req = new DeckRequest("Business", "business", "Business vocab", "business");
        VocabDeck saved = makeDeck(10L, "Business", "business", "Business vocab", "business");
        when(repository.save(any(VocabDeck.class))).thenReturn(saved);

        DeckResponse result = service.create(req);

        verify(repository).save(any(VocabDeck.class));
        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.name()).isEqualTo("Business");
        assertThat(result.slug()).isEqualTo("business");
    }

    // --- update ---

    @Test
    void update_notFound_throwsVocabDeckNotFoundException() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L,
                new DeckRequest("Name", "slug", "Desc", "topic")))
                .isInstanceOf(VocabDeckNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void update_found_callsUpdateOnDeck_slugUnchanged() {
        VocabDeck deck = makeDeck(5L, "OldName", "old-slug", "OldDesc", "oldTopic");
        when(repository.findById(5L)).thenReturn(Optional.of(deck));
        when(repository.save(any(VocabDeck.class))).thenAnswer(inv -> inv.getArgument(0));

        DeckResponse result = service.update(5L,
                new DeckRequest("NewName", "ignored-slug", "NewDesc", "newTopic"));

        // slug must remain unchanged (update() on VocabDeck only sets name/description/topic)
        assertThat(result.slug()).isEqualTo("old-slug");
        assertThat(result.name()).isEqualTo("NewName");
        assertThat(result.description()).isEqualTo("NewDesc");
        assertThat(result.topic()).isEqualTo("newTopic");
    }

    // --- delete ---

    @Test
    void delete_notFound_throwsVocabDeckNotFoundException() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(VocabDeckNotFoundException.class)
                .hasMessageContaining("99");
        verify(repository, never()).deleteById(any());
    }

    @Test
    void delete_found_callsDeleteById() {
        when(repository.existsById(7L)).thenReturn(true);

        service.delete(7L);

        verify(repository).deleteById(7L);
    }

    // --- helper ---

    private VocabDeck makeDeck(Long id, String name, String slug, String description, String topic) {
        try {
            VocabDeck d = new VocabDeck();
            setField(d, "id", id);
            d.setSlug(slug);
            d.update(name, description, topic);
            return d;
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
