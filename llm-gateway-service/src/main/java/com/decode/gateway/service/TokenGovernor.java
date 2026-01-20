package com.decode.gateway.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class TokenGovernor {

    @Value("${llm.governor.tpm-limit:250000}")
    private int tpmLimit = 250_000; // User has 250k TPM quota

    @Value("${llm.governor.rpm-limit:3000}")
    private int rpmLimit = 3_000; // Conservative RPM limit (3000 requests/min = 50 requests/sec)

    @Value("${llm.governor.min-request-delay-ms:200}")
    private long minRequestDelayMs = 200; // Minimum 200ms between requests (5 req/sec max)

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    private final AtomicInteger tokensUsedInCurrentMinute = new AtomicInteger(0);
    private final AtomicInteger requestsInCurrentMinute = new AtomicInteger(0);
    private long windowStartTimestamp = System.currentTimeMillis();
    private long lastRequestTimestamp = 0;

    public synchronized void acquireTokenBudget(String text) {
        int estimatedTokens = encoding.encode(text).size() + 100; // Buffer for overhead

        long now = System.currentTimeMillis();
        
        // Reset counters if window expired
        if (now - windowStartTimestamp > 60000) {
            resetTokenBucket();
        }

        // RPM limiting: Check if we've exceeded request rate limit
        int currentRequests = requestsInCurrentMinute.get();
        if (currentRequests >= rpmLimit) {
            long sleepTime = 60000 - (now - windowStartTimestamp);
            double rpmPercent = (currentRequests * 100.0) / rpmLimit;
            log.warn("⚠️ RPM LIMIT REACHED: {}/{} requests ({:.1f}%) in current minute window. Pausing for {} ms...", 
                    currentRequests, rpmLimit, rpmPercent, sleepTime);
            log.warn("📊 Rate Limit Stats - RPM: {}/{}, TPM: {}/{}, Window remaining: {} ms", 
                    currentRequests, rpmLimit, 
                    tokensUsedInCurrentMinute.get(), tpmLimit,
                    sleepTime);
            sleep(sleepTime);
            resetTokenBucket();
        }

        // TPM limiting: Check if we've exceeded token rate limit
        int currentTokens = tokensUsedInCurrentMinute.get();
        if (currentTokens + estimatedTokens > tpmLimit) {
            long sleepTime = 60000 - (now - windowStartTimestamp);
            double tpmPercent = (currentTokens * 100.0) / tpmLimit;
            log.warn("⚠️ TPM LIMIT REACHED: {}/{} tokens ({:.1f}%) in current minute window. Pausing for {} ms...", 
                    currentTokens, tpmLimit, tpmPercent, sleepTime);
            log.warn("📊 Rate Limit Stats - RPM: {}/{}, TPM: {}/{}, Window remaining: {} ms", 
                    currentRequests, rpmLimit,
                    currentTokens, tpmLimit,
                    sleepTime);
            sleep(sleepTime);
            resetTokenBucket();
        }
        
        // Log consumption when hitting warning thresholds (80% and 90%)
        int newTokenCount = currentTokens + estimatedTokens;
        double tokenPercent = (newTokenCount * 100.0) / tpmLimit;
        double requestPercent = ((currentRequests + 1) * 100.0) / rpmLimit;
        
        if (tokenPercent >= 90 && tokenPercent < 95) {
            log.warn("⚠️ TPM WARNING: {}/{} tokens ({:.1f}%) - Approaching limit!", 
                    newTokenCount, tpmLimit, tokenPercent);
        } else if (tokenPercent >= 80 && tokenPercent < 90) {
            log.info("ℹ️ TPM WARNING: {}/{} tokens ({:.1f}%) - High consumption", 
                    newTokenCount, tpmLimit, tokenPercent);
        }
        
        if (requestPercent >= 90 && requestPercent < 100) {
            log.warn("⚠️ RPM WARNING: {}/{} requests ({:.1f}%) - Approaching limit!", 
                    (currentRequests + 1), rpmLimit, requestPercent);
        } else if (requestPercent >= 80 && requestPercent < 90) {
            log.info("ℹ️ RPM WARNING: {}/{} requests ({:.1f}%) - High consumption", 
                    (currentRequests + 1), rpmLimit, requestPercent);
        }

        // Rate limiting: Enforce minimum delay between requests
        long timeSinceLastRequest = now - lastRequestTimestamp;
        if (timeSinceLastRequest < minRequestDelayMs && lastRequestTimestamp > 0) {
            long delayNeeded = minRequestDelayMs - timeSinceLastRequest;
            log.debug("RPM Governance: Enforcing {} ms delay between requests", delayNeeded);
            sleep(delayNeeded);
            now = System.currentTimeMillis();
        }

        // Update counters
        tokensUsedInCurrentMinute.addAndGet(estimatedTokens);
        requestsInCurrentMinute.incrementAndGet();
        lastRequestTimestamp = now;

        int finalTokenCount = tokensUsedInCurrentMinute.get();
        int finalRequestCount = requestsInCurrentMinute.get();
        double finalTokenPercent = (finalTokenCount * 100.0) / tpmLimit;
        double finalRequestPercent = (finalRequestCount * 100.0) / rpmLimit;
        
        log.debug("✅ Governor approved: {} tokens (Total: {}/{} = {:.1f}%), {} requests/min (Total: {}/{} = {:.1f}%)", 
                estimatedTokens, finalTokenCount, tpmLimit, finalTokenPercent,
                finalRequestCount, finalRequestCount, rpmLimit, finalRequestPercent);
    }
    
    /**
     * Get current consumption metrics
     * @return Map with current token and request consumption
     */
    public synchronized java.util.Map<String, Object> getCurrentMetrics() {
        long now = System.currentTimeMillis();
        long windowRemainingMs = 60000 - (now - windowStartTimestamp);
        
        int tokens = tokensUsedInCurrentMinute.get();
        int requests = requestsInCurrentMinute.get();
        double tokenPercent = (tokens * 100.0) / tpmLimit;
        double requestPercent = (requests * 100.0) / rpmLimit;
        
        java.util.Map<String, Object> metrics = new java.util.HashMap<>();
        
        java.util.Map<String, Object> tpm = new java.util.HashMap<>();
        tpm.put("used", tokens);
        tpm.put("limit", tpmLimit);
        tpm.put("percentage", Math.round(tokenPercent * 100.0) / 100.0);
        tpm.put("remaining", tpmLimit - tokens);
        metrics.put("tpm", tpm);
        
        java.util.Map<String, Object> rpm = new java.util.HashMap<>();
        rpm.put("used", requests);
        rpm.put("limit", rpmLimit);
        rpm.put("percentage", Math.round(requestPercent * 100.0) / 100.0);
        rpm.put("remaining", rpmLimit - requests);
        metrics.put("rpm", rpm);
        
        java.util.Map<String, Object> window = new java.util.HashMap<>();
        window.put("startTimestamp", windowStartTimestamp);
        window.put("remainingMs", Math.max(0, windowRemainingMs));
        window.put("elapsedMs", now - windowStartTimestamp);
        metrics.put("window", window);
        
        metrics.put("lastRequestTimestamp", lastRequestTimestamp);
        
        return metrics;
    }

    private void resetTokenBucket() {
        tokensUsedInCurrentMinute.set(0);
        requestsInCurrentMinute.set(0);
        windowStartTimestamp = System.currentTimeMillis();
        lastRequestTimestamp = 0;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
