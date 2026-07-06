package com.engly.study.infrastructure;

import com.engly.study.domain.ReviewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for ReviewLog (append-only review history).
 *
 * <p>Insert (save) writes one row per review (P1). The aggregation queries below back the study
 * stats API: they bucket {@code reviewed_at} to a local calendar date using
 * {@code AT TIME ZONE 'Asia/Saigon'} (same precedent as UserCardStateJpaRepository), and return
 * the bucket as a {@code 'YYYY-MM-DD'} text so the mapping is JDBC-driver independent.
 */
@Repository
public interface ReviewLogJpaRepository extends JpaRepository<ReviewLog, Long> {

    /** Lifetime review count for a user (totalReviews KPI). */
    long countByUserSub(String userSub);

    /** Reviews at or after an instant (e.g. start-of-today) — reviewedToday KPI. */
    @Query(value = """
            SELECT COUNT(*) FROM review_log
            WHERE user_sub = :userSub AND reviewed_at >= :from
            """, nativeQuery = true)
    long countReviewsSince(@Param("userSub") String userSub, @Param("from") Instant from);

    /**
     * Reviews grouped by Saigon-local day, from :from onward.
     * Each row = [ 'YYYY-MM-DD' (String), count (Number) ].
     */
    @Query(value = """
            SELECT to_char((reviewed_at AT TIME ZONE 'Asia/Saigon')::date, 'YYYY-MM-DD') AS day,
                   COUNT(*) AS total
            FROM review_log
            WHERE user_sub = :userSub AND reviewed_at >= :from
            GROUP BY day
            """, nativeQuery = true)
    List<Object[]> reviewsPerDay(@Param("userSub") String userSub, @Param("from") Instant from);

    /**
     * Reviews grouped by Saigon-local day AND rating, from :from onward.
     * Each row = [ 'YYYY-MM-DD' (String), rating (String), count (Number) ].
     */
    @Query(value = """
            SELECT to_char((reviewed_at AT TIME ZONE 'Asia/Saigon')::date, 'YYYY-MM-DD') AS day,
                   rating AS rating,
                   COUNT(*) AS total
            FROM review_log
            WHERE user_sub = :userSub AND reviewed_at >= :from
            GROUP BY day, rating
            """, nativeQuery = true)
    List<Object[]> ratingsPerDay(@Param("userSub") String userSub, @Param("from") Instant from);

    /**
     * All distinct Saigon-local days the user reviewed on, newest first — for streak computation.
     * Returns 'YYYY-MM-DD' strings.
     */
    @Query(value = """
            SELECT DISTINCT to_char((reviewed_at AT TIME ZONE 'Asia/Saigon')::date, 'YYYY-MM-DD') AS day
            FROM review_log
            WHERE user_sub = :userSub
            ORDER BY day DESC
            """, nativeQuery = true)
    List<String> distinctReviewDatesDesc(@Param("userSub") String userSub);
}
