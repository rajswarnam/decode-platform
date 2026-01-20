package com.decode.gateway.controller;

import com.decode.gateway.domain.RateLimitEvent;
import com.decode.gateway.repository.RateLimitEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for querying rate limit event statistics from the database.
 */
@RestController
@RequestMapping("/api/ratelimit/stats")
@RequiredArgsConstructor
@Slf4j
public class RateLimitStatsController {

    private final RateLimitEventRepository rateLimitEventRepository;

    /**
     * Get total count of rate limit hits (pauses and limits reached) in last N hours
     * 
     * @param hours Number of hours to look back (default: 24)
     * @return JSON with count and breakdown by event type
     */
    @GetMapping("/hits/count")
    public ResponseEntity<Map<String, Object>> getRateLimitHitsCount(
            @RequestParam(defaultValue = "24") int hours) {
        
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        
        long totalHits = rateLimitEventRepository.countRateLimitHitsSince(since);
        
        // Breakdown by type
        Map<String, Long> breakdown = new HashMap<>();
        for (RateLimitEvent.RateLimitEventType type : new RateLimitEvent.RateLimitEventType[]{
                RateLimitEvent.RateLimitEventType.TPM_PAUSE_THRESHOLD,
                RateLimitEvent.RateLimitEventType.TPM_LIMIT_REACHED,
                RateLimitEvent.RateLimitEventType.RPM_LIMIT_REACHED}) {
            long count = rateLimitEventRepository.countByEventTypeAndEventTimestampBetween(type, since, Instant.now());
            breakdown.put(type.name(), count);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("totalHits", totalHits);
        response.put("timeWindowHours", hours);
        response.put("since", since);
        response.put("breakdown", breakdown);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get all rate limit events within a time range
     * 
     * @param hours Number of hours to look back (default: 24)
     * @param eventType Optional filter by event type
     * @return List of rate limit events
     */
    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> getRateLimitEvents(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) RateLimitEvent.RateLimitEventType eventType) {
        
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        
        List<RateLimitEvent> events;
        if (eventType != null) {
            events = rateLimitEventRepository.findByEventTypeAndEventTimestampBetween(eventType, since, Instant.now());
        } else {
            events = rateLimitEventRepository.findByEventTimestampBetween(since, Instant.now());
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("count", events.size());
        response.put("timeWindowHours", hours);
        response.put("since", since);
        response.put("events", events);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get summary statistics grouped by event type
     * 
     * @param hours Number of hours to look back (default: 24)
     * @return Summary statistics
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummaryStats(
            @RequestParam(defaultValue = "24") int hours) {
        
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        
        List<Object[]> stats = rateLimitEventRepository.getSummaryStatsByType(since);
        
        List<Map<String, Object>> summary = stats.stream().map(row -> {
            Map<String, Object> stat = new HashMap<>();
            stat.put("eventType", row[0]);
            stat.put("count", row[1]);
            stat.put("avgPauseDurationMs", row[2]);
            stat.put("maxTpmPercentage", row[3]);
            stat.put("maxRpmPercentage", row[4]);
            return stat;
        }).collect(Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("timeWindowHours", hours);
        response.put("since", since);
        response.put("summary", summary);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get recent rate limit events (last N events)
     * 
     * @param limit Number of recent events to return (default: 50)
     * @return List of recent rate limit events
     */
    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentEvents(
            @RequestParam(defaultValue = "50") int limit) {
        
        Pageable pageable = PageRequest.of(0, limit);
        List<RateLimitEvent> events = rateLimitEventRepository.findRecentEvents(pageable);
        
        Map<String, Object> response = new HashMap<>();
        response.put("count", events.size());
        response.put("limit", limit);
        response.put("events", events);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get rate limit hit frequency by hour
     * 
     * @param hours Number of hours to analyze (default: 24)
     * @return Hourly breakdown of rate limit hits
     */
    @GetMapping("/frequency")
    public ResponseEntity<Map<String, Object>> getHitFrequency(
            @RequestParam(defaultValue = "24") int hours) {
        
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        
        List<RateLimitEvent> events = rateLimitEventRepository
                .findByEventTypeAndEventTimestampBetween(
                        RateLimitEvent.RateLimitEventType.TPM_PAUSE_THRESHOLD, since, Instant.now());
        events.addAll(rateLimitEventRepository
                .findByEventTypeAndEventTimestampBetween(
                        RateLimitEvent.RateLimitEventType.TPM_LIMIT_REACHED, since, Instant.now()));
        events.addAll(rateLimitEventRepository
                .findByEventTypeAndEventTimestampBetween(
                        RateLimitEvent.RateLimitEventType.RPM_LIMIT_REACHED, since, Instant.now()));
        
        // Group by hour
        Map<String, Long> hourlyCount = events.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getEventTimestamp().truncatedTo(ChronoUnit.HOURS).toString(),
                        Collectors.counting()
                ));
        
        Map<String, Object> response = new HashMap<>();
        response.put("timeWindowHours", hours);
        response.put("since", since);
        response.put("hourlyFrequency", hourlyCount);
        response.put("totalHits", events.size());
        
        return ResponseEntity.ok(response);
    }
}
