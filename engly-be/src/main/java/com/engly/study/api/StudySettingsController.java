package com.engly.study.api;

import com.engly.study.api.dto.StudySettingsRequest;
import com.engly.study.api.dto.StudySettingsResponse;
import com.engly.study.application.StudySettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Study settings endpoints — requires session authentication (Chain 2, /api/study/**).
 * PUT is a state-changing request; SPA must send X-XSRF-TOKEN header.
 */
@RestController
@RequestMapping("/api/study/settings")
public class StudySettingsController {

    private final StudySettingsService settingsService;

    public StudySettingsController(StudySettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * GET /api/study/settings
     * Returns current user settings; lazy-creates defaults on first call.
     * Response: { newCardsPerDay: int, maxReviewsPerDay: int|null }
     */
    @GetMapping
    public ResponseEntity<StudySettingsResponse> getSettings(
            @AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(settingsService.getOrCreate(user.getSubject()));
    }

    /**
     * PUT /api/study/settings
     * Body: { newCardsPerDay: int (0-500), maxReviewsPerDay: int|null (0-9999) }
     * Returns updated settings.
     */
    @PutMapping
    public ResponseEntity<StudySettingsResponse> updateSettings(
            @Valid @RequestBody StudySettingsRequest req,
            @AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok(settingsService.update(user.getSubject(), req));
    }
}
