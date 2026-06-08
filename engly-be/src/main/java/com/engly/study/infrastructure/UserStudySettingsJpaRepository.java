package com.engly.study.infrastructure;

import com.engly.study.domain.UserStudySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for UserStudySettings.
 * PK is String (user_sub) — one settings row per user, created on first study session (P3).
 */
@Repository
public interface UserStudySettingsJpaRepository extends JpaRepository<UserStudySettings, String> {
    // Standard JpaRepository methods (findById, save, existsById) cover all P1 needs.
    // Additional query methods added in P3 as the study session queue builder requires them.
}
