package com.decode.gateway.repository;

import com.decode.gateway.domain.RateLimitEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for querying rate limit events from the database.
 */
@Repository
public interface RateLimitEventRepository extends JpaRepository<RateLimitEvent, Long> {

    /**
     * Find all events within a time range
     */
    List<RateLimitEvent> findByEventTimestampBetween(Instant start, Instant end);

    /**
     * Find all events of a specific type within a time range
     */
    List<RateLimitEvent> findByEventTypeAndEventTimestampBetween(
            RateLimitEvent.RateLimitEventType eventType, 
            Instant start, 
            Instant end
    );

    /**
     * Count events by type within a time range
     */
    long countByEventTypeAndEventTimestampBetween(
            RateLimitEvent.RateLimitEventType eventType,
            Instant start,
            Instant end
    );

    /**
     * Get total count of rate limit hits (pauses and limits reached) in last N hours
     */
    @Query("SELECT COUNT(e) FROM RateLimitEvent e WHERE " +
           "e.eventType IN (com.decode.gateway.domain.RateLimitEvent.RateLimitEventType.TPM_PAUSE_THRESHOLD, " +
           "com.decode.gateway.domain.RateLimitEvent.RateLimitEventType.TPM_LIMIT_REACHED, " +
           "com.decode.gateway.domain.RateLimitEvent.RateLimitEventType.RPM_LIMIT_REACHED) " +
           "AND e.eventTimestamp >= :since")
    long countRateLimitHitsSince(@Param("since") Instant since);

    /**
     * Get summary statistics grouped by event type
     */
    @Query("SELECT e.eventType, COUNT(e), AVG(e.pauseDurationMs), " +
           "MAX(e.tpmPercentage), MAX(e.rpmPercentage) " +
           "FROM RateLimitEvent e WHERE e.eventTimestamp >= :since " +
           "GROUP BY e.eventType")
    List<Object[]> getSummaryStatsByType(@Param("since") Instant since);

    /**
     * Get events ordered by timestamp (most recent first)
     */
    List<RateLimitEvent> findByOrderByEventTimestampDesc();

    /**
     * Get last N events (using Pageable for limit)
     */
    @Query("SELECT e FROM RateLimitEvent e ORDER BY e.eventTimestamp DESC")
    List<RateLimitEvent> findRecentEvents(org.springframework.data.domain.Pageable pageable);
}
