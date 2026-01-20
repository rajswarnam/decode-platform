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

    @Value("${llm.governor.rpm-limit:5000}")
    private int rpmLimit = 5_000; // Conservative RPM limit (50k RPM typical, but conservative for safety)

    @Value("${llm.governor.min-request-delay-ms:100}")
    private long minRequestDelayMs = 100; // Minimum 100ms between requests to prevent rapid-fire

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
        if (requestsInCurrentMinute.get() >= rpmLimit) {
            long sleepTime = 60000 - (now - windowStartTimestamp);
            log.warn("RPM Governance: Limit Reached ({} requests). Pausing for {} ms...", 
                    requestsInCurrentMinute.get(), sleepTime);
            sleep(sleepTime);
            resetTokenBucket();
        }

        // TPM limiting: Check if we've exceeded token rate limit
        if (tokensUsedInCurrentMinute.get() + estimatedTokens > tpmLimit) {
            long sleepTime = 60000 - (now - windowStartTimestamp);
            log.warn("TPM Governance: Limit Reached ({} tokens). Pausing for {} ms...", 
                    tokensUsedInCurrentMinute.get(), sleepTime);
            sleep(sleepTime);
            resetTokenBucket();
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

        log.debug("Governor approved {} tokens, {} requests/min, bucket: {}/{} tokens, {}/{} requests", 
                estimatedTokens, requestsInCurrentMinute.get(), 
                tokensUsedInCurrentMinute.get(), tpmLimit,
                requestsInCurrentMinute.get(), rpmLimit);
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
