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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD for vocabulary words. Writes are transactional; reads use readOnly=true.
 * deckId is resolved to a VocabDeck on create/update — missing deck → VocabDeckNotFoundException (404).
 * All routes gated by /api/admin/** → ROLE_ADMIN security chain.
 */
@Service
@Transactional
public class VocabWordAdminService {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final VocabWordJpaRepository wordRepository;
    private final VocabDeckJpaRepository deckRepository;

    public VocabWordAdminService(VocabWordJpaRepository wordRepository,
                                  VocabDeckJpaRepository deckRepository) {
        this.wordRepository = wordRepository;
        this.deckRepository = deckRepository;
    }

    /**
     * Paginated search with four branches:
     *   deckId != null AND q non-blank → combined deck+term search (M3 fix)
     *   deckId != null only            → filter by deck
     *   q non-blank only               → global term/meaningVi search
     *   else                           → return all
     */
    @Transactional(readOnly = true)
    public VocabWordPageResponse search(String q, Long deckId, int page, int size) {
        validatePagingParams(page, size);
        Pageable pageable = PageRequest.of(page, size);

        boolean hasQ = q != null && !q.isBlank();
        Page<VocabWord> result;
        if (deckId != null && hasQ) {
            result = wordRepository.searchByDeck(deckId, escapeLike(q.trim()), pageable);
        } else if (deckId != null) {
            result = wordRepository.findByDeck_Id(deckId, pageable);
        } else if (hasQ) {
            result = wordRepository.search(escapeLike(q.trim()), pageable);
        } else {
            result = wordRepository.findAll(pageable);
        }

        return new VocabWordPageResponse(
                result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** Fetch one word by id. */
    @Transactional(readOnly = true)
    public VocabWordResponse getById(Long id) {
        return toDto(findOrThrow(id));
    }

    /** Create a new word. Resolves deckId → VocabDeck (404 if not found). */
    public VocabWordResponse create(VocabWordRequest req) {
        VocabWord word = new VocabWord();
        apply(word, req);
        return toDto(wordRepository.save(word));
    }

    /** Full update. deckId can change (reassigns word to another deck). */
    public VocabWordResponse update(Long id, VocabWordRequest req) {
        VocabWord word = findOrThrow(id);
        apply(word, req);
        return toDto(wordRepository.save(word));
    }

    /** Hard delete. Missing id → 404. */
    public void delete(Long id) {
        if (!wordRepository.existsById(id)) {
            throw new VocabWordNotFoundException(id);
        }
        wordRepository.deleteById(id);
    }

    /** Toggle/set the verified flag only. */
    public VocabWordResponse toggleVerified(Long id, boolean verified) {
        VocabWord word = findOrThrow(id);
        word.setVerified(verified);
        return toDto(wordRepository.save(word));
    }

    // --- helpers ---

    private VocabWord findOrThrow(Long id) {
        return wordRepository.findById(id)
                .orElseThrow(() -> new VocabWordNotFoundException(id));
    }

    private VocabDeck resolveDeck(Long deckId) {
        return deckRepository.findById(deckId)
                .orElseThrow(() -> new VocabDeckNotFoundException(deckId));
    }

    private void apply(VocabWord word, VocabWordRequest req) {
        word.setDeck(resolveDeck(req.deckId()));
        word.update(req.term(), req.meaningVi(), req.ipa(),
                req.exampleEn(), req.exampleVi(), req.partOfSpeech());
        word.setVerified(req.verified());
    }

    private void validatePagingParams(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0, got: " + page);
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between " + MIN_SIZE + " and " + MAX_SIZE + ", got: " + size);
        }
    }

    /** Escape LIKE wildcards (matches VerbAdminService; query declares ESCAPE '!'). */
    private String escapeLike(String raw) {
        return raw.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private VocabWordResponse toDto(VocabWord w) {
        return new VocabWordResponse(
                w.getId(),
                w.getDeck().getId(),
                w.getTerm(),
                w.getMeaningVi(),
                w.getIpa(),
                w.getExampleEn(),
                w.getExampleVi(),
                w.getPartOfSpeech(),
                w.isVerified());
    }
}
