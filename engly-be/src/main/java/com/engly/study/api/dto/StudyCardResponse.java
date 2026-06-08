package com.engly.study.api.dto;

import com.engly.study.domain.CardLearningState;

/**
 * One card in the study queue — returned by GET /api/study/decks/{id}/queue.
 * Contains card state metadata + word content needed to render the flashcard.
 */
public record StudyCardResponse(
        Long cardStateId,
        Long wordId,
        String term,
        String meaningVi,
        String ipa,
        String exampleEn,
        String exampleVi,
        String partOfSpeech,
        CardLearningState state
) {}
