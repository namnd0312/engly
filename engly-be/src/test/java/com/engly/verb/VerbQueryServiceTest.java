package com.engly.verb;

import com.engly.verb.application.VerbQueryService;
import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.api.dto.VerbSearchPageResponse;
import com.engly.verb.domain.IrregularVerb;
import com.engly.verb.domain.VerbNotFoundException;
import com.engly.verb.domain.VerbRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerbQueryServiceTest {

    @Mock
    private VerbRepository verbRepository;

    private VerbQueryService service;

    @BeforeEach
    void setUp() {
        service = new VerbQueryService(verbRepository);
    }

    // --- search ---

    @Test
    void search_returnsPagedDtos() {
        IrregularVerb verb = makeVerb("go", "went", "gone");
        Page<IrregularVerb> page = new PageImpl<>(List.of(verb), PageRequest.of(0, 20), 1);
        when(verbRepository.search(eq("go"), any(Pageable.class))).thenReturn(page);

        VerbSearchPageResponse result = service.search("go", 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).baseForm()).isEqualTo("go");
        assertThat(result.content().get(0).pastSimple()).isEqualTo("went");
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    void search_emptyQ_usesFindAll() {
        // M2: blank query now routes to findAll (not LIKE search) for index-friendly full scan
        Page<IrregularVerb> empty = Page.empty();
        when(verbRepository.findAll(any(Pageable.class))).thenReturn(empty);

        VerbSearchPageResponse result = service.search("", 0, 20);

        assertThat(result.content()).isEmpty();
        verify(verbRepository).findAll(any(Pageable.class));
        verify(verbRepository, never()).search(any(), any(Pageable.class));
    }

    @Test
    void search_nullQ_usesFindAll() {
        // M2: null query also routes to findAll
        Page<IrregularVerb> empty = Page.empty();
        when(verbRepository.findAll(any(Pageable.class))).thenReturn(empty);

        service.search(null, 0, 20);

        verify(verbRepository).findAll(any(Pageable.class));
        verify(verbRepository, never()).search(any(), any(Pageable.class));
    }

    // --- paging validation ---

    @Test
    void search_pageNegative_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.search("go", -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page must be >= 0");
    }

    @Test
    void search_sizeTooLarge_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.search("go", 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between");
    }

    @Test
    void search_sizeTooSmall_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.search("go", 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be between");
    }

    @Test
    void search_sizeAtMax_passes() {
        Page<IrregularVerb> empty = Page.empty();
        when(verbRepository.search(any(), any(Pageable.class))).thenReturn(empty);

        assertThatCode(() -> service.search("go", 0, 100)).doesNotThrowAnyException();
    }

    // --- getByBaseForm ---

    @Test
    void getByBaseForm_found_returnsMappedDto() {
        IrregularVerb verb = makeVerb("go", "went", "gone");
        when(verbRepository.findByBaseFormIgnoreCase("go")).thenReturn(Optional.of(verb));

        VerbResponse result = service.getByBaseForm("go");

        assertThat(result.baseForm()).isEqualTo("go");
        assertThat(result.pastSimple()).isEqualTo("went");
        assertThat(result.pastParticiple()).isEqualTo("gone");
    }

    @Test
    void getByBaseForm_notFound_throwsVerbNotFoundException() {
        when(verbRepository.findByBaseFormIgnoreCase("zzz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByBaseForm("zzz"))
                .isInstanceOf(VerbNotFoundException.class)
                .hasMessageContaining("zzz");
    }

    // --- helper ---

    private IrregularVerb makeVerb(String base, String pastSimple, String pastParticiple) {
        try {
            IrregularVerb v = new IrregularVerb();
            setField(v, "baseForm", base);
            setField(v, "pastSimple", pastSimple);
            setField(v, "pastParticiple", pastParticiple);
            setField(v, "verified", false);
            return v;
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
