package com.engly.verb.admin;

import com.engly.verb.api.dto.VerbAdminPageResponse;
import com.engly.verb.api.dto.VerbAdminResponse;
import com.engly.verb.api.dto.VerbRequest;
import com.engly.verb.domain.IrregularVerb;
import com.engly.verb.domain.VerbNotFoundException;
import com.engly.verb.infrastructure.VerbJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD for irregular verbs. Writes are transactional; reads use the same JPA repo as the
 * public side. Duplicate base_form bubbles a {@code DataIntegrityViolationException} → 409 (handled
 * by GlobalExceptionHandler). Reachability is gated by the {@code /api/admin/** → ROLE_ADMIN} rule.
 */
@Service
@Transactional
public class VerbAdminService {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final VerbJpaRepository repository;

    public VerbAdminService(VerbJpaRepository repository) {
        this.repository = repository;
    }

    /** Paginated search (blank q = all). Mirrors VerbQueryService paging guard + LIKE escaping. */
    @Transactional(readOnly = true)
    public VerbAdminPageResponse search(String q, int page, int size) {
        validatePagingParams(page, size);
        Pageable pageable = PageRequest.of(page, size);

        Page<IrregularVerb> result = (q == null || q.isBlank())
                ? repository.findAll(pageable)
                : repository.search(escapeLike(q.trim()), pageable);

        return new VerbAdminPageResponse(
                result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** Fetch one verb by numeric id (used by the edit page). */
    @Transactional(readOnly = true)
    public VerbAdminResponse getById(Long id) {
        return toDto(findOrThrow(id));
    }

    /** Create a new verb. Duplicate base_form → DataIntegrityViolationException → 409. */
    public VerbAdminResponse create(VerbRequest req) {
        IrregularVerb verb = new IrregularVerb();
        apply(verb, req);
        return toDto(repository.save(verb));
    }

    /** Full update of an existing verb. */
    public VerbAdminResponse update(Long id, VerbRequest req) {
        IrregularVerb verb = findOrThrow(id);
        apply(verb, req);
        return toDto(repository.save(verb));
    }

    /** Hard delete. Missing id → 404. */
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new VerbNotFoundException(String.valueOf(id));
        }
        repository.deleteById(id);
    }

    /** Toggle/set the verified flag only. */
    public VerbAdminResponse toggleVerified(Long id, boolean verified) {
        IrregularVerb verb = findOrThrow(id);
        verb.setVerified(verified);
        return toDto(repository.save(verb));
    }

    // --- helpers ---

    private IrregularVerb findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new VerbNotFoundException(String.valueOf(id)));
    }

    private void apply(IrregularVerb verb, VerbRequest req) {
        verb.update(req.baseForm(), req.pastSimple(), req.pastParticiple(),
                req.pastSimpleAlt(), req.pastParticipleAlt(),
                req.ipaBase(), req.ipaPast(), req.ipaPp(),
                req.meaningVi(), req.verified());
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

    /** Escape LIKE wildcards (matches VerbQueryService; query declares ESCAPE '!'). */
    private String escapeLike(String raw) {
        return raw.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private VerbAdminResponse toDto(IrregularVerb v) {
        return new VerbAdminResponse(
                v.getId(), v.getBaseForm(), v.getPastSimple(), v.getPastSimpleAlt(),
                v.getPastParticiple(), v.getPastParticipleAlt(),
                v.getIpaBase(), v.getIpaPast(), v.getIpaPp(),
                v.getMeaningVi(), v.isVerified());
    }
}
