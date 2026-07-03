package com.engly.study.api;

import com.engly.study.api.dto.StudyCardResponse;
import com.engly.study.api.dto.StudyDeckSummary;
import com.engly.study.application.StudyDeckService;
import com.engly.study.application.StudyQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Study deck endpoints — requires authentication (Chain 2, /api/study/**).
 * user sub is read server-side from the access JWT — never from request body (prevents IDOR).
 */
@RestController
@RequestMapping("/api/study")
public class StudyDeckController {

    private final StudyDeckService deckService;
    private final StudyQueueService queueService;

    public StudyDeckController(StudyDeckService deckService, StudyQueueService queueService) {
        this.deckService = deckService;
        this.queueService = queueService;
    }

    /**
     * GET /api/study/decks
     * Returns all decks with per-user SRS counts {newCount, dueCount, learnedCount}.
     */
    @GetMapping("/decks")
    public ResponseEntity<List<StudyDeckSummary>> listDecks(
            @AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(deckService.listDecks(user.getSubject()));
    }

    /**
     * GET /api/study/decks/{id}/queue
     * Returns today's study queue: due cards first, then new cards up to daily limit.
     * Lazy-creates NEW card-state rows for any new words served.
     */
    @GetMapping("/decks/{id}/queue")
    public ResponseEntity<List<StudyCardResponse>> getQueue(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(queueService.buildQueue(user.getSubject(), id));
    }
}
