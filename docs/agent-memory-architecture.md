# Agent Memory Architecture for Decode.AI

## Overview

This document proposes a comprehensive memory system for Decode.AI agents, enabling both **short-term** (conversation context) and **long-term** (learned patterns, project knowledge) memory capabilities.

**Note**: This architecture uses **PostgreSQL only** (no Redis required), with optimized indexes and scheduled cleanup for performance.

## Why Memory Matters

### Current Limitations

1. **No Conversation Context**: Each query is treated independently
2. **No Learning**: Agents don't learn from past successful patterns
3. **No User Preferences**: Can't remember user's preferred analysis style
4. **No Project-Specific Knowledge**: Doesn't build on previous analyses
5. **Redundant Work**: Re-analyzes same concepts repeatedly

### Benefits of Memory

1. **Contextual Conversations**: "What about the payment module?" (follow-up to previous query)
2. **Learning from Success**: Remember what worked well in past analyses
3. **Personalization**: Adapt to user's analysis preferences
4. **Efficiency**: Reuse previous insights instead of re-analyzing
5. **Consistency**: Maintain consistent terminology and mappings across sessions

## Memory Architecture

### Two-Tier Memory System

```
┌─────────────────────────────────────────────────────────┐
│                    Agent Memory System                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  SHORT-TERM MEMORY (Session-Based)                     │
│  ┌──────────────────────────────────────────────────┐  │
│  │ • Current conversation context                   │  │
│  │ • Recent queries and responses                   │  │
│  │ • Active analysis plan state                     │  │
│  │ • Temporary insights (current session)           │  │
│  │ • Storage: PostgreSQL (TTL: 24 hours, auto-clean)│  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
│  LONG-TERM MEMORY (Persistent)                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │ • Learned patterns & successful strategies       │  │
│  │ • Project-specific knowledge base                │  │
│  │ • User preferences & analysis style              │  │
│  │ • Historical query patterns                      │  │
│  │ • Cross-session insights                         │  │
│  │ • Storage: PostgreSQL + Vector Store (Qdrant)   │  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## Short-Term Memory (Session Memory)

### Purpose
- Maintain conversation context within a session
- Track current analysis state
- Enable follow-up questions
- Store temporary insights

### Data Model

```sql
CREATE TABLE conversation_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(255),  -- Optional: for multi-user support
    project_id UUID REFERENCES projects(id),
    domain VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,  -- TTL: 24 hours
    is_active BOOLEAN DEFAULT true,  -- For soft deletion
    metadata JSONB,  -- Flexible storage for session state
    INDEX idx_sessions_active (is_active, expires_at),  -- For cleanup queries
    INDEX idx_sessions_project_domain (project_id, domain, is_active)  -- For lookups
);

CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES conversation_sessions(id),
    role VARCHAR(50) NOT NULL,  -- 'user' | 'assistant' | 'system'
    content TEXT NOT NULL,
    query_type VARCHAR(100),  -- 'analysis' | 'clarification' | 'follow-up'
    context_snapshot JSONB,  -- State at time of message
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_created (session_id, created_at)
);

CREATE TABLE session_insights (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES conversation_sessions(id),
    insight_type VARCHAR(100),  -- 'domain_discovery' | 'pattern' | 'mapping'
    content TEXT,
    confidence_score DOUBLE PRECISION,
    source_query_id UUID REFERENCES conversation_messages(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_insights (session_id, created_at)
);

-- Cleanup function for expired sessions (run via scheduled job)
CREATE OR REPLACE FUNCTION cleanup_expired_sessions() RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Mark expired sessions as inactive (soft delete)
    UPDATE conversation_sessions 
    SET is_active = false 
    WHERE is_active = true 
      AND expires_at < CURRENT_TIMESTAMP;
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Create index for fast lookups (already added above, but ensure it exists)
-- CREATE INDEX idx_sessions_active ON conversation_sessions(is_active, expires_at);
-- CREATE INDEX idx_sessions_project_domain ON conversation_sessions(project_id, domain, is_active);
```

### Implementation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ShortTermMemoryService {
    
    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final SessionInsightRepository insightRepository;
    
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final Duration SESSION_REUSE_WINDOW = Duration.ofHours(1);  // Reuse session if active within 1 hour
    
    /**
     * Get or create session for user query
     * Uses PostgreSQL with proper indexing for fast lookups
     */
    public ConversationSession getOrCreateSession(String projectId, String domain) {
        // Clean up expired sessions first (async, non-blocking)
        cleanupExpiredSessionsAsync();
        
        // Check database for recent active session (within reuse window)
        Optional<ConversationSession> recent = sessionRepository
            .findRecentActiveSession(
                UUID.fromString(projectId), 
                domain, 
                Instant.now().minus(SESSION_REUSE_WINDOW),
                Instant.now().plus(Duration.ofMinutes(30))  // Still has 30+ min before expiry
            );
        
        if (recent.isPresent()) {
            // Update last activity time
            ConversationSession session = recent.get();
            session.setLastActivityAt(Instant.now());
            session.setExpiresAt(Instant.now().plus(SESSION_TTL));  // Extend expiry
            return sessionRepository.save(session);
        }
        
        // Create new session
        ConversationSession session = ConversationSession.builder()
            .projectId(UUID.fromString(projectId))
            .domain(domain)
            .expiresAt(Instant.now().plus(SESSION_TTL))
            .lastActivityAt(Instant.now())
            .isActive(true)
            .build();
        
        return sessionRepository.save(session);
    }
    
    /**
     * Clean up expired sessions asynchronously
     * Marks them as inactive instead of deleting (soft delete)
     */
    @Async
    private void cleanupExpiredSessionsAsync() {
        try {
            int cleaned = sessionRepository.markExpiredSessionsInactive(Instant.now());
            if (cleaned > 0) {
                log.debug("Cleaned up {} expired sessions", cleaned);
            }
        } catch (Exception e) {
            log.warn("Error cleaning up expired sessions", e);
        }
    }
    
    /**
     * Store user query in conversation history
     */
    public ConversationMessage storeUserQuery(String sessionId, String query, String queryType) {
        ConversationMessage message = ConversationMessage.builder()
            .sessionId(UUID.fromString(sessionId))
            .role("user")
            .content(query)
            .queryType(queryType)
            .build();
        
        return messageRepository.save(message);
    }
    
    /**
     * Store assistant response
     */
    public ConversationMessage storeAssistantResponse(
            String sessionId, String response, Map<String, Object> contextSnapshot) {
        ConversationMessage message = ConversationMessage.builder()
            .sessionId(UUID.fromString(sessionId))
            .role("assistant")
            .content(response)
            .contextSnapshot(contextSnapshot)
            .build();
        
        return messageRepository.save(message);
    }
    
    /**
     * Get conversation history for context
     * Only retrieves messages from active sessions
     */
    public List<ConversationMessage> getConversationHistory(String sessionId, int limit) {
        // Verify session is still active
        Optional<ConversationSession> session = sessionRepository.findById(UUID.fromString(sessionId));
        if (session.isEmpty() || !session.get().isActive() || 
            session.get().getExpiresAt().isBefore(Instant.now())) {
            log.warn("Session {} is not active or expired", sessionId);
            return Collections.emptyList();
        }
        
        return messageRepository.findBySessionIdOrderByCreatedAtDesc(
            UUID.fromString(sessionId), limit);
    }
    
    /**
     * Store temporary insight from current session
     */
    public void storeSessionInsight(String sessionId, String type, String content, double confidence) {
        SessionInsight insight = SessionInsight.builder()
            .sessionId(UUID.fromString(sessionId))
            .insightType(type)
            .content(content)
            .confidenceScore(confidence)
            .build();
        
        insightRepository.save(insight);
    }
    
    /**
     * Get recent insights from session
     */
    public List<SessionInsight> getSessionInsights(String sessionId) {
        return insightRepository.findBySessionIdOrderByCreatedAtDesc(
            UUID.fromString(sessionId));
    }
}
```

### Usage in AgentOrchestrator

```java
public String executeSwarm(String userQuery, String projectContext, String domain, 
                          Consumer<String> progressConsumer, Consumer<String> resultConsumer) {
    
    // Get or create session for short-term memory
    ConversationSession session = shortTermMemoryService.getOrCreateSession(projectId, domain);
    
    // Get conversation history for context
    List<ConversationMessage> history = shortTermMemoryService.getConversationHistory(
        session.getId().toString(), 5);  // Last 5 messages
    
    // Detect if this is a follow-up question
    boolean isFollowUp = detectFollowUp(userQuery, history);
    
    // Enrich query with conversation context
    String enrichedQuery = isFollowUp 
        ? enrichWithContext(userQuery, history)
        : userQuery;
    
    // Store user query
    shortTermMemoryService.storeUserQuery(session.getId().toString(), userQuery, "analysis");
    
    // Execute analysis...
    String result = executeAnalysis(enrichedQuery, projectContext, domain, progressConsumer);
    
    // Store assistant response
    shortTermMemoryService.storeAssistantResponse(
        session.getId().toString(), result, getCurrentContextSnapshot());
    
    // Store insights
    shortTermMemoryService.storeSessionInsight(
        session.getId().toString(), "domain_discovery", domainMap.getSummary(), 0.9);
    
    return result;
}
```

## Long-Term Memory (Persistent Memory)

### Purpose
- Learn from successful patterns
- Build project-specific knowledge base
- Remember user preferences
- Store cross-session insights
- Enable pattern recognition

### Data Model

```sql
CREATE TABLE learned_patterns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id),
    pattern_type VARCHAR(100),  -- 'query_strategy' | 'mapping_pattern' | 'analysis_approach'
    pattern_key VARCHAR(255),  -- Unique identifier for pattern
    pattern_data JSONB,  -- Pattern details
    success_count INTEGER DEFAULT 0,
    failure_count INTEGER DEFAULT 0,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, pattern_type, pattern_key)
);

CREATE TABLE project_knowledge (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id),
    knowledge_type VARCHAR(100),  -- 'domain_concept' | 'business_rule' | 'architecture_pattern'
    content TEXT,
    source_session_id UUID,  -- Where this knowledge came from
    confidence_score DOUBLE PRECISION,
    verified BOOLEAN DEFAULT false,  -- Human-verified knowledge
    embedding VECTOR(384),  -- For semantic search
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_project_knowledge_embedding ON project_knowledge 
    USING ivfflat (embedding vector_cosine_ops);

CREATE TABLE user_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(255),  -- Optional: for multi-user
    project_id UUID REFERENCES projects(id),
    preference_type VARCHAR(100),  -- 'analysis_style' | 'detail_level' | 'focus_areas'
    preference_value JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, project_id, preference_type)
);

CREATE TABLE query_patterns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id),
    query_text TEXT,
    query_intent VARCHAR(100),  -- 'comprehensive' | 'focused' | 'clarification'
    successful_response_pattern TEXT,  -- What worked well
    retrieved_files JSONB,  -- Files that were relevant
    quality_score DOUBLE PRECISION,
    usage_count INTEGER DEFAULT 1,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Implementation

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class LongTermMemoryService {
    
    private final LearnedPatternRepository patternRepository;
    private final ProjectKnowledgeRepository knowledgeRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final QueryPatternRepository queryPatternRepository;
    private final EmbeddingModel embeddingModel;
    
    /**
     * Learn from successful analysis
     */
    public void learnFromSuccess(String projectId, String patternType, String patternKey, 
                                 Map<String, Object> patternData) {
        LearnedPattern pattern = patternRepository
            .findByProjectIdAndPatternTypeAndPatternKey(
                UUID.fromString(projectId), patternType, patternKey)
            .orElse(LearnedPattern.builder()
                .projectId(UUID.fromString(projectId))
                .patternType(patternType)
                .patternKey(patternKey)
                .patternData(patternData)
                .successCount(0)
                .failureCount(0)
                .build());
        
        pattern.setSuccessCount(pattern.getSuccessCount() + 1);
        pattern.setLastUsedAt(Instant.now());
        pattern.setUpdatedAt(Instant.now());
        
        patternRepository.save(pattern);
    }
    
    /**
     * Store project knowledge for future reference
     */
    public void storeProjectKnowledge(String projectId, String knowledgeType, 
                                     String content, double confidence, String sourceSessionId) {
        // Generate embedding for semantic search
        List<Double> embedding = embeddingModel.embed(content);
        
        ProjectKnowledge knowledge = ProjectKnowledge.builder()
            .projectId(UUID.fromString(projectId))
            .knowledgeType(knowledgeType)
            .content(content)
            .sourceSessionId(UUID.fromString(sourceSessionId))
            .confidenceScore(confidence)
            .embedding(embedding)
            .build();
        
        knowledgeRepository.save(knowledge);
    }
    
    /**
     * Retrieve relevant project knowledge for query
     */
    public List<ProjectKnowledge> retrieveRelevantKnowledge(String projectId, String query, int topK) {
        // Generate query embedding
        List<Double> queryEmbedding = embeddingModel.embed(query);
        
        // Semantic search in project knowledge
        return knowledgeRepository.findSimilarKnowledge(
            UUID.fromString(projectId), queryEmbedding, topK);
    }
    
    /**
     * Get learned patterns for project
     */
    public List<LearnedPattern> getLearnedPatterns(String projectId, String patternType) {
        return patternRepository.findByProjectIdAndPatternTypeOrderBySuccessCountDesc(
            UUID.fromString(projectId), patternType);
    }
    
    /**
     * Store user preference
     */
    public void storeUserPreference(String userId, String projectId, 
                                   String preferenceType, Map<String, Object> value) {
        UserPreference preference = preferenceRepository
            .findByUserIdAndProjectIdAndPreferenceType(userId, UUID.fromString(projectId), preferenceType)
            .orElse(UserPreference.builder()
                .userId(userId)
                .projectId(UUID.fromString(projectId))
                .preferenceType(preferenceType)
                .build());
        
        preference.setPreferenceValue(value);
        preference.setUpdatedAt(Instant.now());
        
        preferenceRepository.save(preference);
    }
    
    /**
     * Get user preferences
     */
    public Optional<UserPreference> getUserPreference(String userId, String projectId, String preferenceType) {
        return preferenceRepository.findByUserIdAndProjectIdAndPreferenceType(
            userId, UUID.fromString(projectId), preferenceType);
    }
}
```

### Integration with AgentOrchestrator

```java
public String executeSwarm(String userQuery, String projectContext, String domain, 
                          Consumer<String> progressConsumer, Consumer<String> resultConsumer) {
    
    // Retrieve relevant long-term knowledge
    List<ProjectKnowledge> relevantKnowledge = longTermMemoryService.retrieveRelevantKnowledge(
        projectId, userQuery, 5);
    
    // Get learned patterns
    List<LearnedPattern> patterns = longTermMemoryService.getLearnedPatterns(
        projectId, "query_strategy");
    
    // Get user preferences
    Optional<UserPreference> userPrefs = longTermMemoryService.getUserPreference(
        userId, projectId, "analysis_style");
    
    // Enrich context with long-term memory
    String enrichedContext = enrichWithLongTermMemory(
        projectContext, relevantKnowledge, patterns, userPrefs);
    
    // Execute analysis with enriched context...
    String result = executeAnalysis(userQuery, enrichedContext, domain, progressConsumer);
    
    // Learn from successful analysis
    if (isSuccessful(result)) {
        longTermMemoryService.learnFromSuccess(
            projectId, "query_strategy", generatePatternKey(userQuery), 
            extractPatternData(result));
        
        // Store new knowledge
        longTermMemoryService.storeProjectKnowledge(
            projectId, "domain_concept", extractKeyInsights(result), 
            0.85, sessionId);
    }
    
    return result;
}
```

## Memory Retrieval Strategies

### 1. Semantic Search (Vector Similarity)
- Use embeddings to find relevant knowledge
- Search in `project_knowledge` table
- Retrieve top-K most similar items

### 2. Pattern Matching
- Match current query to learned patterns
- Use successful strategies from past
- Adapt based on success/failure rates

### 3. Temporal Relevance
- Prioritize recent knowledge
- Weight by recency and success rate
- Decay old patterns that haven't been used

### 4. Context-Aware Retrieval
- Consider current session context
- Combine short-term + long-term memory
- Filter by project, domain, query type

## Implementation Phases

### Phase 1: Short-Term Memory (Week 1-2)
1. Create database tables with proper indexes
2. Implement `ShortTermMemoryService` (PostgreSQL only)
3. Add session cleanup job (scheduled task)
4. Integrate with `AgentOrchestrator`
5. Enable follow-up questions
6. Performance optimization with indexes

### Phase 2: Long-Term Memory - Patterns (Week 3-4)
1. Create `learned_patterns` table
2. Implement pattern learning
3. Pattern retrieval and application
4. Success/failure tracking

### Phase 3: Long-Term Memory - Knowledge Base (Week 5-6)
1. Create `project_knowledge` table
2. Implement knowledge storage
3. Semantic search integration
4. Knowledge retrieval in queries

### Phase 4: User Preferences (Week 7-8)
1. Create `user_preferences` table
2. Preference learning
3. Personalization in responses
4. Preference UI

## Benefits

1. **Contextual Conversations**: "What about X?" works as follow-up
2. **Learning**: Agents improve over time
3. **Efficiency**: Reuse insights instead of re-analyzing
4. **Consistency**: Maintain terminology across sessions
5. **Personalization**: Adapt to user preferences
6. **Knowledge Accumulation**: Build project knowledge base

## Example Use Cases

### Follow-Up Question
```
User: "What are the business use cases?"
Agent: [Analyzes and responds]

User: "What about the payment module?"  // Follow-up
Agent: [Uses short-term memory to understand context]
```

### Learning from Success
```
Session 1: Query "authentication" → Successful strategy: Focus on AuthService.java
Session 2: Query "login" → Uses learned pattern: Check AuthService.java first
```

### Knowledge Accumulation
```
Session 1: Discovers "OAuth2 is used for authentication"
Session 2: Query "How does auth work?" → Retrieves stored knowledge
```

## Next Steps

1. Review and approve architecture
2. Create database migration scripts
3. Implement Phase 1 (Short-Term Memory)
4. Test with real queries
5. Iterate and improve
