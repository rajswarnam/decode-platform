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

    @Value("${llm.governor.tpm-pause-threshold:220000}")
    private int tpmPauseThreshold = 220_000; // Pause when reaching 220k tokens (88% of 250k limit)

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    private final AtomicInteger tokensUsedInCurrentMinute = new AtomicInteger(0);
    private final AtomicInteger requestsInCurrentMinute = new AtomicInteger(0);
    private long windowStartTimestamp = System.currentTimeMillis();
    private long lastRequestTimestamp = 0;

    /**
     * Optional callback for progress updates during rate limit pauses.
     * Set by InternalLlmClientService to send keep-alive messages to UI.
     */
    private java.util.function.Consumer<String> pauseProgressCallback = null;

    /**
     * Set callback for progress updates during rate limit pauses.
     * This allows the UI to receive keep-alive messages during long pauses.
     */
    public void setPauseProgressCallback(java.util.function.Consumer<String> callback) {
        this.pauseProgressCallback = callback;
    }

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

        // TPM limiting: Proactive pause when approaching limit (before hitting it)
        int currentTokens = tokensUsedInCurrentMinute.get();
        int projectedTokens = currentTokens + estimatedTokens;
        
        // PROACTIVE PAUSE: If we're approaching the limit (>= pause threshold), wait before making request
        if (projectedTokens >= tpmPauseThreshold && projectedTokens < tpmLimit) {
            long windowRemainingMs = 60000 - (now - windowStartTimestamp);
            double tpmPercent = (projectedTokens * 100.0) / tpmLimit;
            log.warn("⏸️ TPM PAUSE THRESHOLD: {}/{} tokens ({:.1f}%) approaching limit. Pausing for {} ms to prevent 429...", 
                    projectedTokens, tpmLimit, tpmPercent, windowRemainingMs);
            log.warn("📊 Rate Limit Stats - RPM: {}/{}, TPM: {}/{}, Window remaining: {} ms", 
                    currentRequests, rpmLimit,
                    projectedTokens, tpmLimit,
                    windowRemainingMs);
            
            // Send progress updates during pause to keep UI connection alive
            if (pauseProgressCallback != null) {
                long pauseSeconds = windowRemainingMs / 1000;
                pauseProgressCallback.accept(String.format("⏸️ Rate Limit: Pausing for %d seconds (TPM: %.1f%%). Resetting token window...", 
                        pauseSeconds, tpmPercent));
            }
            
            // Sleep in chunks and send periodic keep-alive messages
            long chunkSize = 5000; // 5 seconds
            long remaining = windowRemainingMs;
            int chunkCount = 0;
            while (remaining > 0) {
                long sleepTime = Math.min(chunkSize, remaining);
                sleep(sleepTime);
                remaining -= sleepTime;
                chunkCount++;
                
                // Send keep-alive every 5 seconds
                if (pauseProgressCallback != null && remaining > 0) {
                    long remainingSeconds = remaining / 1000;
                    pauseProgressCallback.accept(String.format("⏳ Waiting for rate limit window reset... %d seconds remaining", remainingSeconds));
                }
            }
            
            if (pauseProgressCallback != null) {
                pauseProgressCallback.accept("✅ Rate limit window reset. Resuming requests...");
            }
            
            resetTokenBucket();
            // Recalculate after reset
            now = System.currentTimeMillis();
            currentTokens = 0;
            projectedTokens = estimatedTokens;
        }
        
        // TPM limiting: Check if we've exceeded token rate limit (final check)
        if (projectedTokens > tpmLimit) {
            long sleepTime = 60000 - (now - windowStartTimestamp);
            double tpmPercent = (currentTokens * 100.0) / tpmLimit;
            log.warn("⚠️ TPM LIMIT REACHED: {}/{} tokens ({:.1f}%) in current minute window. Pausing for {} ms...", 
                    currentTokens, tpmLimit, tpmPercent, sleepTime);
            log.warn("📊 Rate Limit Stats - RPM: {}/{}, TPM: {}/{}, Window remaining: {} ms", 
                    currentRequests, rpmLimit,
                    currentTokens, tpmLimit,
                    sleepTime);
            
            // Send progress updates during pause to keep UI connection alive
            if (pauseProgressCallback != null) {
                long pauseSeconds = sleepTime / 1000;
                pauseProgressCallback.accept(String.format("⚠️ Rate Limit Exceeded: Pausing for %d seconds (TPM: %.1f%%). Resetting token window...", 
                        pauseSeconds, tpmPercent));
            }
            
            // Sleep in chunks and send periodic keep-alive messages
            long chunkSize = 5000; // 5 seconds
            long remaining = sleepTime;
            while (remaining > 0) {
                long sleepChunk = Math.min(chunkSize, remaining);
                sleep(sleepChunk);
                remaining -= sleepChunk;
                
                // Send keep-alive every 5 seconds
                if (pauseProgressCallback != null && remaining > 0) {
                    long remainingSeconds = remaining / 1000;
                    pauseProgressCallback.accept(String.format("⏳ Waiting for rate limit window reset... %d seconds remaining", remainingSeconds));
                }
            }
            
            if (pauseProgressCallback != null) {
                pauseProgressCallback.accept("✅ Rate limit window reset. Resuming requests...");
            }
            
            resetTokenBucket();
            // Recalculate after reset
            now = System.currentTimeMillis();
            currentTokens = 0;
            projectedTokens = estimatedTokens;
        }
        
        // Log consumption when hitting warning thresholds (80% and 90%)
        int newTokenCount = currentTokens + estimatedTokens;
        double tokenPercent = (newTokenCount * 100.0) / tpmLimit;
        double requestPercent = ((currentRequests + 1) * 100.0) / rpmLimit;
        
        // Update warning thresholds to be more conservative
        if (tokenPercent >= 90 && tokenPercent < 95) {
            log.warn("⚠️ TPM WARNING: {}/{} tokens ({:.1f}%) - Approaching limit! Consider pausing.", 
                    newTokenCount, tpmLimit, tokenPercent);
        } else if (tokenPercent >= 88 && tokenPercent < 90) {
            log.warn("⏸️ TPM APPROACHING PAUSE: {}/{} tokens ({:.1f}%) - Will pause at {} tokens", 
                    newTokenCount, tpmLimit, tokenPercent, tpmPauseThreshold);
        } else if (tokenPercent >= 80 && tokenPercent < 88) {
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
