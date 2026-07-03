package com.engly.study.application;

import com.engly.study.api.dto.StudySettingsRequest;
import com.engly.study.api.dto.StudySettingsResponse;
import com.engly.study.domain.UserStudySettings;
import com.engly.study.infrastructure.UserStudySettingsJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mockito unit tests for StudySettingsService — no Spring context, no DB.
 * Covers lazy-create, existing-row, update-existing, and update-no-row paths.
 */
@ExtendWith(MockitoExtension.class)
class StudySettingsServiceTest {

    @Mock private UserStudySettingsJpaRepository settingsRepo;

    private StudySettingsService service;

    @BeforeEach
    void setUp() {
        service = new StudySettingsService(settingsRepo);
    }

    // -----------------------------------------------------------------------
    // getOrCreate: first time — no row → save defaults
    // -----------------------------------------------------------------------

    @Test
    void getOrCreate_noExistingRow_savesAndReturnsDefaults() {
        String userSub = "user-new";
        UserStudySettings defaults = UserStudySettings.defaults(userSub);

        when(settingsRepo.findById(userSub)).thenReturn(Optional.empty());
        when(settingsRepo.save(any(UserStudySettings.class))).thenReturn(defaults);

        StudySettingsResponse response = service.getOrCreate(userSub);

        verify(settingsRepo).save(any(UserStudySettings.class));
        assertThat(response.newCardsPerDay()).isEqualTo(20);
        assertThat(response.maxReviewsPerDay()).isNull();
    }

    // -----------------------------------------------------------------------
    // getOrCreate: existing row → return stored values, no save
    // -----------------------------------------------------------------------

    @Test
    void getOrCreate_existingRow_returnsStoredValuesWithoutSave() {
        String userSub = "user-existing";
        UserStudySettings stored = UserStudySettings.defaults(userSub);
        stored.update(5, 100);

        when(settingsRepo.findById(userSub)).thenReturn(Optional.of(stored));

        StudySettingsResponse response = service.getOrCreate(userSub);

        verify(settingsRepo, never()).save(any());
        assertThat(response.newCardsPerDay()).isEqualTo(5);
        assertThat(response.maxReviewsPerDay()).isEqualTo(100);
    }

    // -----------------------------------------------------------------------
    // update: existing row → update values + bumpUpdatedAt + save
    // -----------------------------------------------------------------------

    @Test
    void update_existingRow_updatesAndSaves() {
        String userSub = "user-update";
        UserStudySettings existing = UserStudySettings.defaults(userSub);

        when(settingsRepo.findById(userSub)).thenReturn(Optional.of(existing));
        when(settingsRepo.save(any(UserStudySettings.class))).thenReturn(existing);

        StudySettingsRequest req = new StudySettingsRequest(10, 200);
        StudySettingsResponse response = service.update(userSub, req);

        ArgumentCaptor<UserStudySettings> captor = ArgumentCaptor.forClass(UserStudySettings.class);
        verify(settingsRepo).save(captor.capture());
        UserStudySettings saved = captor.getValue();

        assertThat(saved.getNewCardsPerDay()).isEqualTo(10);
        assertThat(saved.getMaxReviewsPerDay()).isEqualTo(200);
        // bumpUpdatedAt should have been called (updatedAt non-null after update)
        assertThat(saved.getUpdatedAt()).isNotNull();

        assertThat(response.newCardsPerDay()).isEqualTo(10);
        assertThat(response.maxReviewsPerDay()).isEqualTo(200);
    }

    // -----------------------------------------------------------------------
    // update: no row → creates defaults then applies requested values
    // -----------------------------------------------------------------------

    @Test
    void update_noExistingRow_createsWithRequestedValues() {
        String userSub = "user-create-on-update";

        when(settingsRepo.findById(userSub)).thenReturn(Optional.empty());
        when(settingsRepo.save(any(UserStudySettings.class))).thenAnswer(inv -> inv.getArgument(0));

        StudySettingsRequest req = new StudySettingsRequest(30, null);
        StudySettingsResponse response = service.update(userSub, req);

        ArgumentCaptor<UserStudySettings> captor = ArgumentCaptor.forClass(UserStudySettings.class);
        verify(settingsRepo).save(captor.capture());
        UserStudySettings saved = captor.getValue();

        assertThat(saved.getNewCardsPerDay()).isEqualTo(30);
        assertThat(saved.getMaxReviewsPerDay()).isNull();
        assertThat(response.newCardsPerDay()).isEqualTo(30);
        assertThat(response.maxReviewsPerDay()).isNull();
    }

    // -----------------------------------------------------------------------
    // update: maxReviewsPerDay=null → unlimited (no NPE)
    // -----------------------------------------------------------------------

    @Test
    void update_maxReviewsPerDayNull_allowedAndPersisted() {
        String userSub = "user-unlimited";
        UserStudySettings existing = UserStudySettings.defaults(userSub);
        existing.update(20, 50); // existing has a limit

        when(settingsRepo.findById(userSub)).thenReturn(Optional.of(existing));
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StudySettingsResponse response = service.update(userSub, new StudySettingsRequest(20, null));

        assertThat(response.maxReviewsPerDay()).isNull();
    }
}
