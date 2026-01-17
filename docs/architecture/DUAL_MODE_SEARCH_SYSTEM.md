# Dual-Mode Search System Design

**Status**: ✅ Implemented  
**Date**: 2026-01-16

## Overview

The system now supports **two search modes** that automatically adapt based on query intent:

1. **COMPREHENSIVE Mode**: For queries like "all business use cases" - broad coverage (200-500 files)
2. **FOCUSED Mode**: For queries like "how does order processing work?" - deep, relevant analysis (50-100 files)
3. **HYBRID Mode**: Balanced approach for mixed queries (100-200 files)

---

## Query Intent Detection

### Patterns That Trigger Comprehensive Mode

- **All/Everything**: "all", "entire", "complete", "comprehensive", "full", "everything"
- **Overview Requests**: "what are", "list all", "show all", "all the", "all of the"
- **Summary Requests**: "summarize", "give me an overview", "provide overview"
- **Business Scope**: "all business", "all use case", "all functionality", "all features"

**Example Queries**:
- ✅ "I am new to the project, can you tell me what are the business usecases this project is addressing?"
- ✅ "What are all the business use cases?"
- ✅ "Give me a complete overview of the system"

### Patterns That Trigger Focused Mode

- **Specific Questions**: "how does", "how do", "explain", "describe", "detail", "specific"
- **Location Requests**: "show me", "find", "where is", "where does", "locate"
- **Implementation Details**: "implementation", "code for", "class", "method", "function"

**Example Queries**:
- ✅ "How does order processing work?"
- ✅ "Show me the payment gateway implementation"
- ✅ "Where is the authentication logic?"

### Patterns That Trigger Hybrid Mode

- **Mixed Intent**: Queries with both comprehensive and focused patterns
- **Ambiguous**: Queries that don't match either pattern strongly

**Example Queries**:
- ✅ "Tell me about order processing and all related use cases"
- ✅ "What does the system do and how does it work?"

---

## Search Strategies

### 1. FOCUSED Mode (Default)

**Strategy**: Single semantic search with standard topK

```java
SearchRequest request = SearchRequest.builder()
    .query(searchQuery)
    .topK(50)  // Standard relevance-based
    .build();
```

**Characteristics**:
- Fast and efficient
- High precision (most relevant results)
- Lower recall (may miss edge cases)
- Best for: Specific questions, debugging, focused analysis

**Coverage**: ~50-100 files per worker

---

### 2. COMPREHENSIVE Mode

**Strategy**: Multi-layered search with module sampling

#### A. Primary Semantic Search (40% of results)
```java
SearchRequest.builder()
    .query(searchQuery)
    .topK(80)  // 40% of 200
    .build();
```

#### B. Stratified Search by Business Domain (30% of results)
- Searches using top 5 business entities from Lexical Scout
- Example: "Order" + searchQuery, "Invoice" + searchQuery
- ~20 documents per domain

#### C. Module Sampling (30% of results)
- Ensures coverage from top 10 modules
- Example: "procurement-module" + searchQuery
- ~10 documents per module

#### D. Fallback Generic Search
- If not enough results, uses generic queries:
  - "service controller repository"
  - "business logic implementation"
  - "domain model entity"

**Characteristics**:
- Slower but more thorough
- High recall (covers most use cases)
- Lower precision (includes less relevant results)
- Best for: Overview queries, comprehensive analysis, discovery

**Coverage**: ~200-500 files per worker

---

### 3. HYBRID Mode

**Strategy**: Balanced approach

```java
// Primary semantic search (50% of results)
SearchRequest.builder()
    .query(searchQuery)
    .topK(50)
    .build();

// Light module sampling (50% of results)
// 1 file per top 5 modules
```

**Characteristics**:
- Moderate speed and thoroughness
- Balanced precision and recall
- Best for: Mixed queries, general exploration

**Coverage**: ~100-200 files per worker

---

## Implementation Details

### QueryIntentAnalyzer

**Location**: `com.decode.context.orchestrator.agent.QueryIntentAnalyzer`

**Responsibilities**:
1. Analyze query text using regex patterns
2. Determine mode (COMPREHENSIVE/FOCUSED/HYBRID)
3. Recommend search parameters (topK, module sampling, stratified search)
4. Provide reasoning for decision

**Usage**:
```java
QueryIntentAnalyzer.QueryIntent intent = queryIntentAnalyzer.analyzeIntent(userQuery, domainMap);
// intent.getMode() -> COMPREHENSIVE, FOCUSED, or HYBRID
// intent.getRecommendedTopK() -> 200, 50, or 100
// intent.isUseModuleSampling() -> true/false
```

### AgentOrchestrator Integration

**Changes**:
1. Query intent analyzed at swarm start (after Lexical Scout)
2. Intent stored in `CurrentExecutionPlan` for worker access
3. `retrieveContext()` method dispatches to mode-specific implementations:
   - `retrieveContextFocused()` - Single semantic search
   - `retrieveContextComprehensive()` - Multi-layered search
   - `retrieveContextHybrid()` - Balanced approach

**Flow**:
```
User Query
    ↓
Lexical Scout (Domain Discovery)
    ↓
Query Intent Analyzer → Mode Detection
    ↓
Agent Orchestrator → Pass intent to workers
    ↓
Workers → Use intent to select search strategy
    ↓
retrieveContext(mode) → Returns appropriate file set
```

---

## Performance Comparison

| Mode | Files Analyzed | Time | Use Case |
|------|----------------|------|----------|
| **FOCUSED** | 50-100 | ~10-15s | Specific questions, debugging |
| **HYBRID** | 100-200 | ~20-30s | General exploration |
| **COMPREHENSIVE** | 200-500 | ~45-60s | Overview, discovery, "all use cases" |

---

## Examples

### Example 1: Comprehensive Query

**Query**: "I am new to the project, can you tell me what are the business usecases this project is addressing?"

**Intent Detected**: COMPREHENSIVE
- Pattern matched: "what are" + "business usecases"
- Mode: COMPREHENSIVE
- TopK: 200
- Module Sampling: Yes
- Stratified Search: Yes

**Result**: ~200-500 files analyzed across all modules

---

### Example 2: Focused Query

**Query**: "How does the payment gateway process refunds?"

**Intent Detected**: FOCUSED
- Pattern matched: "how does"
- Mode: FOCUSED
- TopK: 50
- Module Sampling: No
- Stratified Search: No

**Result**: ~50-100 highly relevant files analyzed

---

### Example 3: Hybrid Query

**Query**: "Tell me about order processing and show me all related features"

**Intent Detected**: HYBRID
- Patterns matched: "tell me" (focused) + "all related" (comprehensive)
- Mode: HYBRID
- TopK: 100
- Module Sampling: Yes (light)
- Stratified Search: Yes (light)

**Result**: ~100-200 files analyzed with balanced coverage

---

## Benefits

1. **Automatic Optimization**: System chooses best strategy based on query
2. **Efficiency**: Focused queries don't waste time on comprehensive search
3. **Coverage**: Comprehensive queries ensure all modules are represented
4. **Flexibility**: Hybrid mode handles mixed intents gracefully
5. **Transparency**: Intent reasoning logged for debugging

---

## Future Enhancements

1. **Learning**: Track which mode produces better results for each query type
2. **User Override**: Allow users to force a specific mode via query syntax
3. **Adaptive TopK**: Adjust topK based on project size (small projects need less)
4. **Quality Feedback**: Use QA scores to refine intent detection patterns
5. **Module Priority**: Weight module sampling by importance (core modules first)

---

**Document Version**: 1.0  
**Last Updated**: 2026-01-16
