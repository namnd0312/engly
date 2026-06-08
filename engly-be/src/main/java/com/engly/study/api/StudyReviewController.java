package com.engly.study.api;

import com.engly.study.api.dto.ReviewRequest;
import com.engly.study.api.dto.ReviewResultResponse;
import com.engly.study.application.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review submission endpoint — requires session authentication (Chain 2, /api/study/**).
 * CSRF cookie (X-XSRF-TOKEN) must be included by the SPA interceptor for POST.
 */
@RestController
@RequestMapping("/api/study")
public class StudyReviewController {

    private final ReviewService reviewService;

    public StudyReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * POST /api/study/review
     * Body: { wordId: Long, rating: AGAIN|HARD|GOOD|EASY }
     * Returns updated SM-2 state: { wordId, state, intervalDays, dueOn, repetitions, easeFactor, lapses }
     */
    @PostMapping("/review")
    public ResponseEntity<ReviewResultResponse> submitReview(
            @Valid @RequestBody ReviewRequest req,
            @AuthenticationPrincipal OidcUser user) {
        return ResponseEntity.ok(reviewService.review(user.getSubject(), req));
    }
}
