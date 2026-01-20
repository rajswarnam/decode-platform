package com.decode.gateway.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity to track rate limit events (pauses, warnings, threshold hits).
 * This allows querying historical rate limit statistics.
 */
@Entity
@Table(name = "rate_limit_events", indexes = {
    @Index(name = "idx_rate_limit_event_timestamp", columnList = "eventTimestamp"),
    @Index(name = "idx_rate_limit_event_type", columnList = "eventType"),
    @Index(name = "idx_rate_limit_event_timestamp_type", columnList = "eventTimestamp,eventType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RateLimitEventType eventType;

    @Column(nullable = false)
    private Instant eventTimestamp;

    // TPM (Tokens Per Minute) metrics
    @Column(nullable = false)
    private Integer tpmUsed;

    @Column(nullable = false)
    private Integer tpmLimit;

    @Column(nullable = false)
    private Double tpmPercentage;

    // RPM (Requests Per Minute) metrics
    @Column(nullable = false)
    private Integer rpmUsed;

    @Column(nullable = false)
    private Integer rpmLimit;

    @Column(nullable = false)
    private Double rpmPercentage;

    // Pause/wait duration in milliseconds
    @Column
    private Long pauseDurationMs;

    // Window information
    @Column
    private Long windowRemainingMs;

    // Additional context
    @Column(length = 500)
    private String message;

    /**
     * Types of rate limit events to track
     */
    public enum RateLimitEventType {
        TPM_PAUSE_THRESHOLD,  // Proactive pause at threshold (88%)
        TPM_LIMIT_REACHED,    // Hard limit hit (100%)
        RPM_LIMIT_REACHED,    // Request limit hit
        TPM_WARNING_90,       // 90% warning
        TPM_WARNING_88,       // 88% approaching pause
        TPM_WARNING_80,       // 80% warning
        RPM_WARNING_90,       // 90% warning
        RPM_WARNING_80        // 80% warning
    }
}
