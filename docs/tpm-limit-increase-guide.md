# TPM Limit Increase Request Guide

## Overview

This guide helps you determine the appropriate TPM (Tokens Per Minute) limit to request from your internal LLM gateway provider and how to configure it after approval.

## Current Configuration

**Current Settings:**
- **TPM Limit**: 250,000 tokens per minute
- **Pause Threshold**: 220,000 tokens (88% of limit)
- **RPM Limit**: 3,000 requests per minute
- **Min Request Delay**: 200ms between requests

**Location**: `llm-gateway-service/src/main/resources/application.yaml`

## Understanding Your Usage

### Check Current Usage

You can monitor your current TPM usage via the rate limit metrics endpoint:

```bash
# Get detailed metrics
curl http://llm-gateway-service:8081/api/ratelimit/metrics

# Get human-readable stats
curl http://llm-gateway-service:8081/api/ratelimit/stats
```

**Response Example:**
```json
{
  "tpmUsage": "238731/250000 tokens (95.5%)",
  "rpmUsage": "45/3000 requests (1.5%)",
  "tpmRemaining": 11269,
  "rpmRemaining": 2955
}
```

### Warning Thresholds

The system logs warnings at:
- **80%**: `ℹ️ TPM WARNING: High consumption`
- **88%**: `⏸️ TPM APPROACHING PAUSE: Will pause at threshold`
- **90%**: `⚠️ TPM WARNING: Approaching limit!`
- **88%+**: Proactive pause to prevent 429 errors

### When to Request an Increase

Request a TPM increase if you see:
- ✅ Consistent usage above **80%** of current limit
- ✅ Frequent **429 Too Many Requests** errors
- ✅ Proactive pauses happening regularly (88% threshold)
- ✅ User complaints about slow responses or timeouts
- ✅ Need to support more concurrent users/analyses

## Recommended Request Amounts

### Option 1: Conservative (2x) - **Recommended**

**Request**: **500,000 TPM** (500k tokens per minute)

**When to use:**
- Current peak usage: 80-95% of limit
- Occasional 429 errors
- Need headroom for growth
- Budget-conscious approach

**New Configuration:**
```yaml
llm:
  governor:
    tpm-limit: 500000
    tpm-pause-threshold: 440000  # 88% of 500k
```

**Benefits:**
- 2x current capacity
- Reduces 429 errors significantly
- Provides growth headroom
- Reasonable cost/benefit

---

### Option 2: Moderate (3x) - For Growth

**Request**: **750,000 TPM** (750k tokens per minute)

**When to use:**
- Current peak usage: 90-100% of limit
- Frequent 429 errors
- Multiple concurrent users expected
- Active development/growth phase

**New Configuration:**
```yaml
llm:
  governor:
    tpm-limit: 750000
    tpm-pause-threshold: 660000  # 88% of 750k
```

**Benefits:**
- 3x current capacity
- Supports concurrent analysis sessions
- Handles batch processing (dictionary population)
- Future-proof for growth

---

### Option 3: Aggressive (4x) - For Scale

**Request**: **1,000,000 TPM** (1M tokens per minute)

**When to use:**
- Production-scale deployment
- Many concurrent users
- Large batch processing needs
- Enterprise-level requirements

**New Configuration:**
```yaml
llm:
  governor:
    tpm-limit: 1000000
    tpm-pause-threshold: 880000  # 88% of 1M
```

**Benefits:**
- 4x current capacity
- Handles large-scale deployments
- Supports enterprise workloads
- Maximum headroom

---

## Request Template

Use this template when requesting a TPM increase from your internal LLM gateway provider:

```
Subject: TPM Limit Increase Request - Decode.AI Platform

Current Situation:
- Current Limit: 250,000 TPM
- Peak Usage Observed: ~239,000 TPM (95% of limit)
- Issues: Frequent 429 errors and proactive pauses affecting user experience
- Pause Threshold: 220,000 TPM (88% of limit)

Requested Increase:
- New Limit: 500,000 TPM (2x current limit)
- New Pause Threshold: 440,000 TPM (88% of new limit)

Justification:
1. Current usage is consistently at 95%+ of limit
2. System proactively pauses at 88% to prevent 429 errors
3. Need headroom for:
   - Multiple concurrent analysis sessions
   - Dictionary population (batch processing)
   - Agent orchestration (multiple workers per query)
   - Future growth and scaling

Business Impact:
- Improved user experience (fewer pauses and timeouts)
- Reduced 429 errors and retry overhead
- Support for concurrent users
- Enables production-scale deployments

Usage Patterns:
- Dictionary Service: Processes symbols in batches (300ms delay between calls)
- Agent Orchestrator: Multiple workers per query (parallel execution)
- Semantic Analysis: Vector search + LLM calls per task
- User Queries: Real-time analysis with streaming responses

Timeline: [Your preferred timeline - e.g., "As soon as possible" or specific date]

Contact: [Your contact information]
```

## After Approval

### Step 1: Update Configuration

Edit `llm-gateway-service/src/main/resources/application.yaml`:

```yaml
llm:
  governor:
    tpm-limit: 500000  # Updated to new limit
    rpm-limit: 3000    # Keep existing RPM limit
    min-request-delay-ms: 200
    tpm-pause-threshold: 440000  # 88% of new limit (500k * 0.88)
```

### Step 2: Rebuild and Restart

```bash
# Rebuild the service
cd llm-gateway-service
mvn clean package

# Restart the service (via Docker Compose)
docker-compose restart llm-gateway-service

# Or if using Kubernetes
kubectl rollout restart deployment/llm-gateway-service
```

### Step 3: Verify Configuration

Check that the new limits are active:

```bash
# Check metrics endpoint
curl http://llm-gateway-service:8081/api/ratelimit/metrics | jq '.tpm.limit'

# Should show: 500000 (or your new limit)
```

### Step 4: Monitor Usage

Watch for:
- ✅ Usage staying below 80% (healthy)
- ✅ No 429 errors
- ✅ Fewer proactive pauses
- ✅ Improved response times

## Pause Threshold Calculation

The pause threshold is set to **88% of the TPM limit** to proactively pause before hitting the hard limit.

**Formula:**
```
tpm-pause-threshold = tpm-limit * 0.88
```

**Examples:**
- 250k TPM → 220k pause threshold
- 500k TPM → 440k pause threshold
- 750k TPM → 660k pause threshold
- 1M TPM → 880k pause threshold

## Monitoring and Alerts

### Check Current Usage

```bash
# Human-readable stats
curl http://llm-gateway-service:8081/api/ratelimit/stats

# Detailed metrics (JSON)
curl http://llm-gateway-service:8081/api/ratelimit/metrics
```

### Log Monitoring

Watch for these log messages:

**Healthy Usage (< 80%):**
```
✅ Governor approved: X tokens (Total: Y/500000 = Z%), ...
```

**Warning (80-88%):**
```
ℹ️ TPM WARNING: X/500000 tokens (Y%) - High consumption
```

**Approaching Pause (88-90%):**
```
⏸️ TPM APPROACHING PAUSE: X/500000 tokens (Y%) - Will pause at 440000 tokens
```

**Proactive Pause (88%+):**
```
⏸️ TPM PAUSE THRESHOLD: X/500000 tokens (Y%) approaching limit. Pausing for Z seconds...
```

**Limit Reached (100%):**
```
⚠️ TPM LIMIT REACHED: X/500000 tokens (Y%) in current minute window. Pausing for Z ms...
```

## Troubleshooting

### Still Getting 429 Errors After Increase

1. **Check if new limit is active:**
   ```bash
   curl http://llm-gateway-service:8081/api/ratelimit/metrics | jq '.tpm.limit'
   ```

2. **Verify configuration was updated:**
   ```bash
   grep tpm-limit llm-gateway-service/src/main/resources/application.yaml
   ```

3. **Check if service was restarted:**
   ```bash
   docker-compose ps llm-gateway-service
   # Or
   kubectl get pods -l app=llm-gateway-service
   ```

4. **Monitor actual usage:**
   ```bash
   curl http://llm-gateway-service:8081/api/ratelimit/stats
   ```

### Usage Still High After Increase

- Review which components consume the most tokens:
  - Dictionary Service (batch processing)
  - Agent Orchestrator (multiple workers)
  - Semantic Analysis (vector search + LLM)
- Consider optimizing:
  - Increase delays between batch operations
  - Reduce number of workers per query
  - Cache LLM responses where possible

### Need Another Increase

If usage consistently exceeds 80% of the new limit:
1. Document peak usage patterns
2. Use the request template again with updated numbers
3. Request 2x-3x of current limit

## Related Documentation

- [Rate Limiting Implementation](../llm-gateway-service/src/main/java/com/decode/gateway/service/TokenGovernor.java)
- [Rate Limit Metrics API](../llm-gateway-service/src/main/java/com/decode/gateway/controller/RateLimitController.java)
- [Data Privacy Filtering](./tpm-limit-increase-guide.md) (reduces token usage by filtering sensitive data)

## Summary

| Current Limit | Recommended Request | New Limit | Pause Threshold |
|--------------|-------------------|-----------|----------------|
| 250k TPM | 2x (Conservative) | 500k TPM | 440k TPM |
| 250k TPM | 3x (Moderate) | 750k TPM | 660k TPM |
| 250k TPM | 4x (Aggressive) | 1M TPM | 880k TPM |

**Recommendation**: Start with **500k TPM (2x)** for a good balance of headroom and cost. Request additional increases as needed based on actual usage patterns.
