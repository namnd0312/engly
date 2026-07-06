package com.engly.study.application;

import com.engly.study.api.dto.AccuracyPointResponse;
import com.engly.study.api.dto.ActivityPointResponse;
import com.engly.study.api.dto.ForecastPointResponse;
import com.engly.study.api.dto.StudySummaryResponse;
import com.engly.study.domain.CardLearningState;
import com.engly.study.infrastructure.ReviewLogJpaRepository;
import com.engly.study.infrastructure.UserCardStateJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregation for the progress dashboard (GET /api/study/stats/**).
 * Snapshot stats come from user_card_state; time-series from review_log.
 * All day bucketing is Asia/Saigon (via {@link StudyClock}); native queries return
 * 'YYYY-MM-DD' strings which are parsed here, then gap-filled so every day in the
 * requested window is present (zero when there was no activity).
 */
@Service
@Transactional(readOnly = true)
public class StudyStatsService {

    private static final List<CardLearningState> ACTIVE_STATES =
            List.of(CardLearningState.LEARNING, CardLearningState.REVIEW);
    private static final List<String> ACTIVE_STATE_NAMES =
            List.of(CardLearningState.LEARNING.name(), CardLearningState.REVIEW.name());

    private final UserCardStateJpaRepository cardStateRepo;
    private final ReviewLogJpaRepository reviewLogRepo;
    private final StudyClock studyClock;

    public StudyStatsService(UserCardStateJpaRepository cardStateRepo,
                             ReviewLogJpaRepository reviewLogRepo,
                             StudyClock studyClock) {
        this.cardStateRepo = cardStateRepo;
        this.reviewLogRepo = reviewLogRepo;
        this.studyClock = studyClock;
    }

    /** KPI snapshot + streak. */
    public StudySummaryResponse summary(String userSub) {
        LocalDate today = studyClock.today();
        long total = cardStateRepo.countByUserSub(userSub);
        long newCount = cardStateRepo.countByUserSubAndState(userSub, CardLearningState.NEW);
        long learning = cardStateRepo.countByUserSubAndState(userSub, CardLearningState.LEARNING);
        long review = cardStateRepo.countByUserSubAndState(userSub, CardLearningState.REVIEW);
        long totalReviews = reviewLogRepo.countByUserSub(userSub);
        long reviewedToday = reviewLogRepo.countReviewsSince(userSub, startOfDay(today));
        long dueToday = cardStateRepo.countByUserSubAndStateInAndDueOnLessThanEqual(
                userSub, ACTIVE_STATES, today);
        int streak = currentStreak(reviewLogRepo.distinctReviewDatesDesc(userSub), today);
        return new StudySummaryResponse(
                total, newCount, learning, review, totalReviews, reviewedToday, dueToday, streak);
    }

    /** Reviews vs new cards per day for the last {@code days} days (inclusive of today). */
    public List<ActivityPointResponse> activity(String userSub, int days) {
        LocalDate today = studyClock.today();
        LocalDate start = today.minusDays(days - 1L);
        Instant from = startOfDay(start);
        Map<LocalDate, Long> reviews = toDayCountMap(reviewLogRepo.reviewsPerDay(userSub, from));
        Map<LocalDate, Long> newCards = toDayCountMap(cardStateRepo.newCardsPerDay(userSub, from));
        List<ActivityPointResponse> out = new ArrayList<>();
        for (LocalDate d : rangeInclusive(start, today)) {
            out.add(new ActivityPointResponse(
                    d, reviews.getOrDefault(d, 0L), newCards.getOrDefault(d, 0L)));
        }
        return out;
    }

    /** Cards due per day for the next {@code days} days (inclusive of today). */
    public List<ForecastPointResponse> forecast(String userSub, int days) {
        LocalDate today = studyClock.today();
        LocalDate end = today.plusDays(days - 1L);
        Map<LocalDate, Long> due = toDayCountMap(
                cardStateRepo.duePerDay(userSub, ACTIVE_STATE_NAMES, today, end));
        List<ForecastPointResponse> out = new ArrayList<>();
        for (LocalDate d : rangeInclusive(today, end)) {
            out.add(new ForecastPointResponse(d, due.getOrDefault(d, 0L)));
        }
        return out;
    }

    /** Per-day rating breakdown for the last {@code days} days (inclusive of today). */
    public List<AccuracyPointResponse> accuracy(String userSub, int days) {
        LocalDate today = studyClock.today();
        LocalDate start = today.minusDays(days - 1L);
        Instant from = startOfDay(start);
        Map<LocalDate, Map<String, Long>> byDay = new HashMap<>();
        for (Object[] row : reviewLogRepo.ratingsPerDay(userSub, from)) {
            LocalDate d = LocalDate.parse((String) row[0]);
            String rating = (String) row[1];
            long count = ((Number) row[2]).longValue();
            byDay.computeIfAbsent(d, k -> new HashMap<>()).put(rating, count);
        }
        List<AccuracyPointResponse> out = new ArrayList<>();
        for (LocalDate d : rangeInclusive(start, today)) {
            Map<String, Long> m = byDay.getOrDefault(d, Map.of());
            out.add(new AccuracyPointResponse(d,
                    m.getOrDefault("AGAIN", 0L),
                    m.getOrDefault("HARD", 0L),
                    m.getOrDefault("GOOD", 0L),
                    m.getOrDefault("EASY", 0L)));
        }
        return out;
    }

    // --- pure helpers (package-private static → unit-testable without Spring) ---

    /**
     * Consecutive-day streak ending today or yesterday. The streak stays "alive" if the user
     * reviewed today OR yesterday (so a not-yet-reviewed today doesn't zero a running streak);
     * it is counted back from the newest review day until the first gap.
     *
     * @param distinctDatesDesc distinct 'YYYY-MM-DD' review dates, newest first
     */
    static int currentStreak(List<String> distinctDatesDesc, LocalDate today) {
        if (distinctDatesDesc.isEmpty()) {
            return 0;
        }
        LocalDate newest = LocalDate.parse(distinctDatesDesc.get(0));
        // Broken if the most recent review was before yesterday.
        if (newest.isBefore(today.minusDays(1))) {
            return 0;
        }
        int streak = 0;
        LocalDate expected = newest;
        for (String s : distinctDatesDesc) {
            LocalDate d = LocalDate.parse(s);
            if (d.isEqual(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (d.isBefore(expected)) {
                break; // gap — streak ends
            }
            // d after expected is impossible (sorted desc, distinct)
        }
        return streak;
    }

    /** Inclusive list of dates from {@code from} to {@code to}. */
    static List<LocalDate> rangeInclusive(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    /** Maps [ 'YYYY-MM-DD', count ] rows into a date→count map. */
    private static Map<LocalDate, Long> toDayCountMap(List<Object[]> rows) {
        Map<LocalDate, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue());
        }
        return map;
    }

    private Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(StudyClock.SAIGON).toInstant();
    }
}
