package com.decode.gateway.controller;

import com.decode.gateway.service.TokenGovernor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ratelimit")
@RequiredArgsConstructor
@Slf4j
public class RateLimitController {

    private final TokenGovernor tokenGovernor;

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getRateLimitMetrics() {
        Map<String, Object> metrics = tokenGovernor.getCurrentMetrics();
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getRateLimitStats() {
        Map<String, Object> metrics = tokenGovernor.getCurrentMetrics();
        
        @SuppressWarnings("unchecked")
        Map<String, Object> tpm = (Map<String, Object>) metrics.get("tpm");
        @SuppressWarnings("unchecked")
        Map<String, Object> rpm = (Map<String, Object>) metrics.get("rpm");
        
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("tpmUsage", String.format("%d/%d tokens (%.1f%%)", 
                tpm.get("used"), tpm.get("limit"), tpm.get("percentage")));
        stats.put("rpmUsage", String.format("%d/%d requests (%.1f%%)", 
                rpm.get("used"), rpm.get("limit"), rpm.get("percentage")));
        stats.put("tpmRemaining", tpm.get("remaining"));
        stats.put("rpmRemaining", rpm.get("remaining"));
        stats.put("metrics", metrics);
        
        return ResponseEntity.ok(stats);
    }
}
