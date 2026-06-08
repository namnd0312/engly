package com.engly.vocab.admin;

import com.engly.vocab.api.dto.DeckPageResponse;
import com.engly.vocab.api.dto.DeckRequest;
import com.engly.vocab.api.dto.DeckResponse;
import com.engly.vocab.domain.VocabDeck;
import com.engly.vocab.domain.VocabDeckNotFoundException;
import com.engly.vocab.infrastructure.VocabDeckJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD for vocabulary decks. Writes are transactional; reads use readOnly=true.
 * Duplicate slug bubbles a DataIntegrityViolationException → 409 (GlobalExceptionHandler).
 * All routes gated by /api/admin/** → ROLE_ADMIN security chain.
 */
@Service
@Transactional
public class DeckAdminService {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final VocabDeckJpaRepository repository;

    public DeckAdminService(VocabDeckJpaRepository repository) {
        this.repository = repository;
    }

    /** Paginated search (blank q = all). Escapes LIKE wildcards before query. */
    @Transactional(readOnly = true)
    public DeckPageResponse search(String q, int page, int size) {
        validatePagingParams(page, size);
        Pageable pageable = PageRequest.of(page, size);

        Page<VocabDeck> result = (q == null || q.isBlank())
                ? repository.findAll(pageable)
                : repository.search(escapeLike(q.trim()), pageable);

        return new DeckPageResponse(
                result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** Fetch one deck by id (used by the edit page). */
    @Transactional(readOnly = true)
    public DeckResponse getById(Long id) {
        return toDto(findOrThrow(id));
    }

    /**
     * Create a new deck. slug is set at creation and never changed after.
     * Duplicate slug → DataIntegrityViolationException → 409.
     */
    public DeckResponse create(DeckRequest req) {
        VocabDeck deck = new VocabDeck();
        applyCreate(deck, req);
        return toDto(repository.save(deck));
    }

    /** Full update — slug is immutable, only name/description/topic change. */
    public DeckResponse update(Long id, DeckRequest req) {
        VocabDeck deck = findOrThrow(id);
        deck.update(req.name(), req.description(), req.topic());
        return toDto(repository.save(deck));
    }

    /** Hard delete. Cascade removes all child vocab_words (DB ON DELETE CASCADE). */
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new VocabDeckNotFoundException(id);
        }
        repository.deleteById(id);
    }

    // --- helpers ---

    private VocabDeck findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new VocabDeckNotFoundException(id));
    }

    /** Sets all fields including slug (create-only; update() skips slug). */
    private void applyCreate(VocabDeck deck, DeckRequest req) {
        deck.setSlug(req.slug());
        deck.update(req.name(), req.description(), req.topic());
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

    private DeckResponse toDto(VocabDeck d) {
        return new DeckResponse(d.getId(), d.getName(), d.getSlug(), d.getDescription(), d.getTopic());
    }
}
