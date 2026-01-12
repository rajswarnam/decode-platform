package com.decode.gateway.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class TokenGovernor {

    private static final int TPM_LIMIT = 200_000;
    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    private final AtomicInteger tokensUsedInCurrentMinute = new AtomicInteger(0);
    private long windowStartTimestamp = System.currentTimeMillis();

    public synchronized void acquireTokenBudget(String text) {
        int estimatedTokens = encoding.encode(text).size() + 100; // Buffer for overhead

        long now = System.currentTimeMillis();
        if (now - windowStartTimestamp > 60000) {
            resetTokenBucket();
        }

        if (tokensUsedInCurrentMinute.get() + estimatedTokens > TPM_LIMIT) {
            long sleepTime = 60000 - (now - windowStartTimestamp);
            log.warn("TPM Governance: Limit Reached ({}). Pausing for {} ms...", tokensUsedInCurrentMinute.get(),
                    sleepTime);
            sleep(sleepTime);
            resetTokenBucket();
        }

        tokensUsedInCurrentMinute.addAndGet(estimatedTokens);
        log.debug("Governor approved {} tokens. Current bucket: {}", estimatedTokens, tokensUsedInCurrentMinute.get());
    }

    private void resetTokenBucket() {
        tokensUsedInCurrentMinute.set(0);
        windowStartTimestamp = System.currentTimeMillis();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
        }
    }
}
