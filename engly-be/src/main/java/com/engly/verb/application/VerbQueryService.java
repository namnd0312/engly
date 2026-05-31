package com.engly.verb.application;

import com.engly.verb.api.dto.VerbResponse;
import com.engly.verb.api.dto.VerbSearchPageResponse;
import com.engly.verb.domain.IrregularVerb;
import com.engly.verb.domain.VerbNotFoundException;
import com.engly.verb.domain.VerbRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for verb queries.
 * Validates paging params, delegates to repo, maps entities to DTOs.
 */
@Service
@Transactional(readOnly = true)
public class VerbQueryService {

    private static final int MAX_SIZE = 100;
    private static final int MIN_SIZE = 1;

    private final VerbRepository verbRepository;

    public VerbQueryService(VerbRepository verbRepository) {
        this.verbRepository = verbRepository;
    }

    /**
     * Paginated verb search. q=null/empty returns all verbs paged via findAll (efficient).
     * Non-blank q is LIKE-escaped before binding to prevent wildcard confusion.
     *
     * @throws IllegalArgumentException if page < 0 or size out of [1,100]
     */
    public VerbSearchPageResponse search(String q, int page, int size) {
        validatePagingParams(page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<IrregularVerb> resultPage;

        if (q == null || q.isBlank()) {
            // M2: use findAll to avoid LIKE '%' full scan and bypass functional indexes
            resultPage = verbRepository.findAll(pageable);
        } else {
            // M1: escape LIKE wildcards so user input like "%" or "_" is treated literally
            String escaped = escapeLike(q.trim());
            resultPage = verbRepository.search(escaped, pageable);
        }

        return new VerbSearchPageResponse(
                resultPage.getContent().stream().map(this::toDto).toList(),
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages()
        );
    }

    /**
     * M1: Escape LIKE special characters before binding into a JPQL LIKE clause.
     * Uses '!' as the escape char (the query declares ESCAPE '!'); a backslash
     * escape char is ambiguous inside JPQL string literals and Hibernate rejects it.
     * Escape the escape char itself first, then the wildcards.
     */
    private String escapeLike(String raw) {
        return raw.replace("!", "!!")
                  .replace("%", "!%")
                  .replace("_", "!_");
    }

    /**
     * Fetch a single verb by base form (case-insensitive).
     *
     * @throws VerbNotFoundException if not found
     */
    public VerbResponse getByBaseForm(String baseForm) {
        return verbRepository.findByBaseFormIgnoreCase(baseForm)
                .map(this::toDto)
                .orElseThrow(() -> new VerbNotFoundException(baseForm));
    }

    // --- private helpers ---

    private void validatePagingParams(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0, got: " + page);
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between " + MIN_SIZE + " and " + MAX_SIZE + ", got: " + size);
        }
    }

    private VerbResponse toDto(IrregularVerb v) {
        return new VerbResponse(
                v.getBaseForm(),
                v.getPastSimple(),
                v.getPastSimpleAlt(),
                v.getPastParticiple(),
                v.getPastParticipleAlt(),
                v.getIpaBase(),
                v.getIpaPast(),
                v.getIpaPp(),
                v.getMeaningVi(),
                v.isVerified()
        );
    }
}
