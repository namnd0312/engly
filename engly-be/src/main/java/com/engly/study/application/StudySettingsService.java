package com.engly.study.application;

import com.engly.study.api.dto.StudySettingsRequest;
import com.engly.study.api.dto.StudySettingsResponse;
import com.engly.study.domain.UserStudySettings;
import com.engly.study.infrastructure.UserStudySettingsJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages per-user study settings (new-cards/day, max-reviews/day).
 * Lazy-creates defaults on first GET so no explicit registration step is needed.
 */
@Service
public class StudySettingsService {

    private final UserStudySettingsJpaRepository settingsRepo;

    public StudySettingsService(UserStudySettingsJpaRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    /**
     * Returns the user's settings, creating defaults (20 new/day, unlimited reviews)
     * on first access.
     */
    @Transactional
    public StudySettingsResponse getOrCreate(String userSub) {
        UserStudySettings settings = settingsRepo.findById(userSub)
                .orElseGet(() -> settingsRepo.save(UserStudySettings.defaults(userSub)));
        return toResponse(settings);
    }

    /**
     * Updates the user's settings and bumps updatedAt app-side.
     * If no settings row exists yet, creates one with the requested values.
     */
    @Transactional
    public StudySettingsResponse update(String userSub, StudySettingsRequest req) {
        UserStudySettings settings = settingsRepo.findById(userSub)
                .orElseGet(() -> UserStudySettings.defaults(userSub));
        settings.update(req.newCardsPerDay(), req.maxReviewsPerDay());
        settings.bumpUpdatedAt(Instant.now());
        settingsRepo.save(settings);
        return toResponse(settings);
    }

    private StudySettingsResponse toResponse(UserStudySettings s) {
        return new StudySettingsResponse(s.getNewCardsPerDay(), s.getMaxReviewsPerDay());
    }
}
