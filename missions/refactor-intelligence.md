Mission Briefing: Intelligence Layer Refactor & TPM Governance
1. Context & Objective
The current implementation of context-orchestrator (specifically DictionaryService.java) is using a Heuristic Fallback that bypasses the LLM. This is unacceptable for the Decode.AI production requirements.

Objective: Refactor the system to ensure deterministic GPT-4o analysis while strictly adhering to a 250,000 Tokens Per Minute (TPM) quota.

2. Phase 1: Documentation Lock-In
Before writing code, update the following files to prevent future "Ghost Integrations":

BRD.md: Add Section 5.6. "Heuristic fallbacks for code analysis are strictly forbidden. The system must prioritize accuracy over uptime."

master-architecture.md: Add "Throttled Batch Ingestion Pattern." Define a Token Governor service that sits between the AnalysisQueue and the GPT-4o API.

PROJECT-GOALS.MD: Add a new metric: "Intelligence Integrity - 100% of code summaries must be LLM-derived."

3. Phase 2: Technical Implementation (Code)
Refactor context-orchestrator with the following components:

Dependency Addition: Add com.knuddels:jtokkit to pom.xml for local token counting.

The Token Governor: Implement a TokenBucket or RateLimiter that tracks usage against a 200,000 TPM safety ceiling (leaving 50k for user overhead).

The Analysis Queue:

Modify the database schema to include status (PENDING, PROCESSING, COMPLETED, FAILED) for file metadata.

Create a background worker that pulls PENDING files and processes them only when token budget is available.

Deterministic Retries: Replace heuristicFallback with a RetryWithBackoff strategy that triggers on 429 errors or quota exhaustion.

4. Phase 3: Verification (The "Handshake")
Once implemented, perform a Live Handshake Test with GPT-4o.

Generate a summary for DictionaryService.java itself.

Output Requirement: Display the token count and the API response in the logs to verify the connection is active and governed.