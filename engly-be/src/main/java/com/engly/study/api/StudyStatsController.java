package com.engly.study.api;

import com.engly.study.api.dto.AccuracyPointResponse;
import com.engly.study.api.dto.ActivityPointResponse;
import com.engly.study.api.dto.ForecastPointResponse;
import com.engly.study.api.dto.StudySummaryResponse;
import com.engly.study.application.StudyStatsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Progress dashboard stats — requires authentication (Chain 2, /api/study/**).
 * user sub is read server-side from the access JWT — never from request (prevents IDOR).
 * All endpoints are read-only GETs (no CSRF token needed). {@code days} is bounded 1..90;
 * out-of-range values yield 400 via ConstraintViolation handling.
 */
@RestController
@RequestMapping("/api/study/stats")
@Validated
public class StudyStatsController {

    private final StudyStatsService statsService;

    public StudyStatsController(StudyStatsService statsService) {
        this.statsService = statsService;
    }

    /** GET /api/study/stats/summary → KPI snapshot + current streak. */
    @GetMapping("/summary")
    public ResponseEntity<StudySummaryResponse> summary(@AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(statsService.summary(user.getSubject()));
    }

    /** GET /api/study/stats/activity?days=30 → reviews vs new cards per day. */
    @GetMapping("/activity")
    public ResponseEntity<List<ActivityPointResponse>> activity(
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days,
            @AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(statsService.activity(user.getSubject(), days));
    }

    /** GET /api/study/stats/forecast?days=14 → due cards per upcoming day. */
    @GetMapping("/forecast")
    public ResponseEntity<List<ForecastPointResponse>> forecast(
            @RequestParam(defaultValue = "14") @Min(1) @Max(90) int days,
            @AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(statsService.forecast(user.getSubject(), days));
    }

    /** GET /api/study/stats/accuracy?days=30 → per-day rating breakdown. */
    @GetMapping("/accuracy")
    public ResponseEntity<List<AccuracyPointResponse>> accuracy(
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days,
            @AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(statsService.accuracy(user.getSubject(), days));
    }
}
