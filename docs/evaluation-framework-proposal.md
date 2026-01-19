# Evaluation Framework Proposal for Decode.AI

## Overview

This document outlines a comprehensive evaluation framework for measuring and improving the quality of Decode.AI's code analysis and semantic exploration capabilities.

## Why Evals Are Critical

1. **DictionaryService Quality**: Ensure business name mappings are accurate and useful
2. **Vector Retrieval Quality**: Validate that relevant files are retrieved for queries (RAG evaluation)
3. **Symbol Parsing Accuracy**: Verify code parsing correctness across languages
4. **Semantic Analysis Quality**: Measure worker report and synthesis quality
5. **End-to-End Query Quality**: Assess overall user experience and response relevance

## Evaluation Categories

### 1. Dictionary Service Evals

**Purpose**: Evaluate the accuracy and quality of business name mappings

**Metrics**:
- **Accuracy**: Percentage of correct business name mappings
- **Coverage**: Percentage of symbols that received mappings
- **Quality Score**: Semantic relevance score (0-1) of mappings
- **Consistency**: Same technical names map to same business names

**Test Cases**:
```java
// Example test cases
{
  "symbolName": "HelloWorldApplication",
  "expectedMapping": "Hello World Application",
  "minQualityScore": 0.8
},
{
  "symbolName": "sendGreetings",
  "expectedMapping": "Send Greetings",
  "minQualityScore": 0.7
}
```

**Implementation**:
- Create `DictionaryEvalService` that runs against golden dataset
- Compare actual vs expected mappings
- Use LLM-as-judge for semantic quality scoring

### 2. Vector Retrieval Evals (RAG Evaluation)

**Purpose**: Validate that semantic search retrieves relevant files for queries

**Metrics**:
- **Precision@K**: Percentage of relevant files in top K results
- **Recall@K**: Percentage of relevant files retrieved in top K
- **MRR (Mean Reciprocal Rank)**: Average of 1/rank of first relevant result
- **NDCG@K**: Normalized Discounted Cumulative Gain

**Test Cases**:
```java
{
  "query": "How is user authentication handled?",
  "expectedFiles": ["AuthService.java", "LoginController.java"],
  "minPrecision@5": 0.8
},
{
  "query": "Where is the payment processing logic?",
  "expectedFiles": ["PaymentService.java", "PaymentController.java"],
  "minRecall@10": 0.7
}
```

**Implementation**:
- Create `RetrievalEvalService` using golden query-file pairs
- Test against vector store with known projects
- Measure retrieval quality metrics

### 3. Symbol Parsing Evals

**Purpose**: Verify code parsing extracts correct symbols and relationships

**Metrics**:
- **Symbol Extraction Accuracy**: Percentage of correctly identified symbols
- **Relationship Extraction Accuracy**: Percentage of correct relationships
- **Coverage**: Percentage of symbols captured vs expected
- **False Positive Rate**: Incorrect symbols identified

**Test Cases**:
```java
{
  "sourceCode": "public class CustomerService { ... }",
  "expectedSymbols": [
    {"name": "CustomerService", "type": "CLASS", "line": 1}
  ],
  "expectedRelationships": []
}
```

**Implementation**:
- Use test projects with known structure
- Compare parsed symbols vs expected symbols
- Validate relationships (inheritance, calls, etc.)

### 4. Semantic Analysis Evals (Worker Reports) ⚠️ **Catches LLM Drift**

**Purpose**: Measure quality of worker reports and synthesis

**Why Critical**: This eval specifically catches LLM response quality changes

**Metrics**:
- **Relevance Score**: How relevant is the analysis to the query?
- **Completeness Score**: Does it cover all important aspects?
- **Accuracy Score**: Are the technical details correct?
- **Clarity Score**: Is the explanation clear and understandable?
- **Consistency Score**: Same query → similar quality? (detects variance)

**Test Cases**:
```java
{
  "query": "What are the business use cases for this project?",
  "expectedTopics": ["User Management", "Payment Processing"],
  "minRelevanceScore": 0.8,
  "minCompletenessScore": 0.7,
  "consistencyCheck": true  // Run same query multiple times, check variance
}
```

**Implementation**:
- Create `SemanticAnalysisEvalService`
- Use LLM-as-judge for scoring (GPT-4, Claude, etc.)
- Evaluate against golden queries and expected outputs
- **Run same queries multiple times** to detect variance/consistency issues
- **Track scores over time** to detect gradual degradation

**Drift Detection**:
```java
// Run evaluation 3 times with same query, check variance
List<Double> scores = evalService.runMultipleTimes(query, 3);
double variance = calculateVariance(scores);
if (variance > 0.1) {  // High variance = inconsistent responses
    alert("High variance detected: LLM responses are inconsistent");
}
```

### 5. End-to-End Query Evals ⚠️ **Catches Full Pipeline Drift**

**Purpose**: Assess overall system quality from user's perspective

**Why Critical**: Catches drift across the entire pipeline (retrieval + LLM)

**Metrics**:
- **Response Relevance**: How relevant is the answer?
- **Response Accuracy**: Is the information correct?
- **Response Completeness**: Does it fully answer the query?
- **User Satisfaction**: Would a user find this helpful?
- **Consistency**: Same query → similar response quality?

**Test Cases**:
```java
{
  "query": "How does authentication work in this system?",
  "expectedAnswers": [
    "Uses OAuth2",
    "JWT tokens",
    "AuthenticationService handles login"
  ],
  "minRelevanceScore": 0.85,
  "runCount": 3  // Run multiple times to check consistency
}
```

**Implementation**:
- Create `QueryEvalService` for end-to-end evaluation
- Use real user queries or synthetic queries
- LLM-as-judge for scoring
- **Run same queries multiple times** to detect variance
- **Track scores over time** (trend analysis)
- **Alert on degradation** (threshold-based alerts)

**Drift Detection Example**:
```java
// Track score over time
Map<String, List<ScoreHistory>> history = loadScoreHistory();
for (String query : goldenQueries) {
    double currentScore = evaluateQuery(query);
    double previousScore = getLastScore(history, query);
    
    if (currentScore < previousScore - 0.1) {  // 10% degradation
        alert("Query quality degraded: " + query + 
              " (Previous: " + previousScore + ", Current: " + currentScore + ")");
    }
}
```

## Implementation Strategy

### Phase 1: Foundation (Week 1-2)

1. **Create Evaluation Infrastructure**
   - `eval/` directory structure
   - Base evaluation framework classes
   - Golden dataset storage

2. **Dictionary Service Evals**
   - Create test dataset with expected mappings
   - Implement `DictionaryEvalService`
   - Run baseline evaluation

3. **Vector Retrieval Evals**
   - Create query-file pairs for test projects
   - Implement `RetrievalEvalService`
   - Run baseline evaluation

### Phase 2: Parsing & Analysis (Week 3-4)

4. **Symbol Parsing Evals**
   - Create test projects with known structure
   - Implement `ParsingEvalService`
   - Validate parsing accuracy

5. **Semantic Analysis Evals**
   - Create test queries and expected outputs
   - Implement `SemanticAnalysisEvalService`
   - LLM-as-judge integration

### Phase 3: Integration & CI (Week 5-6)

6. **End-to-End Evals**
   - Implement `QueryEvalService`
   - End-to-end test suite

7. **CI/CD Integration**
   - Add evaluation runs to CI pipeline
   - Regression testing
   - Performance benchmarking

## Technical Implementation

### Directory Structure

```
eval/
├── framework/
│   ├── EvalService.java           # Base evaluation service
│   ├── EvalResult.java            # Evaluation result model
│   ├── EvalMetrics.java           # Metrics calculation
│   └── LLMJudgeService.java       # LLM-as-judge integration
├── dictionary/
│   ├── DictionaryEvalService.java
│   ├── golden-mappings.json       # Expected mappings
│   └── DictionaryEvalRunner.java
├── retrieval/
│   ├── RetrievalEvalService.java
│   ├── golden-queries.json        # Query-file pairs
│   └── RetrievalEvalRunner.java
├── parsing/
│   ├── ParsingEvalService.java
│   ├── test-projects/             # Known structure projects
│   └── ParsingEvalRunner.java
├── semantic/
│   ├── SemanticAnalysisEvalService.java
│   ├── golden-queries.json        # Test queries
│   └── SemanticAnalysisEvalRunner.java
└── e2e/
    ├── QueryEvalService.java
    ├── test-scenarios.json
    └── QueryEvalRunner.java
```

### Sample Code Structure

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DictionaryEvalService {
    
    private final DictionaryService dictionaryService;
    private final SymbolRepository symbolRepository;
    private final LLMJudgeService llmJudge;
    
    public EvalResult evaluateDictionary(String projectName) {
        // Load golden dataset
        List<GoldenMapping> goldenMappings = loadGoldenMappings(projectName);
        
        // Get actual mappings
        List<GlobalDictionary> actualMappings = dictionaryRepository.findByProject(projectName);
        
        // Calculate metrics
        double accuracy = calculateAccuracy(goldenMappings, actualMappings);
        double coverage = calculateCoverage(goldenMappings, actualMappings);
        double qualityScore = llmJudge.evaluateSemanticQuality(actualMappings);
        
        return EvalResult.builder()
            .evalType("DICTIONARY")
            .metrics(Map.of(
                "accuracy", accuracy,
                "coverage", coverage,
                "qualityScore", qualityScore
            ))
            .build();
    }
}
```

## Golden Datasets

### Dictionary Golden Dataset Example

```json
{
  "projectName": "piggymetrics",
  "mappings": [
    {
      "symbolName": "AccountService",
      "expectedBusinessName": "Account Service",
      "expectedDescription": "Service for managing user accounts",
      "minQualityScore": 0.8
    },
    {
      "symbolName": "createAccount",
      "expectedBusinessName": "Create Account",
      "expectedDescription": "Creates a new user account",
      "minQualityScore": 0.7
    }
  ]
}
```

### Retrieval Golden Dataset Example

```json
{
  "projectName": "piggymetrics",
  "queries": [
    {
      "query": "How is user authentication handled?",
      "relevantFiles": [
        {"file": "AuthService.java", "relevance": 1.0},
        {"file": "LoginController.java", "relevance": 0.9},
        {"file": "SecurityConfig.java", "relevance": 0.8}
      ]
    }
  ]
}
```

## Metrics Dashboard

Create a dashboard to visualize:
- Evaluation scores over time
- Performance trends
- Comparison across projects
- Regression alerts

## When Should Evaluations Run?

### ❌ NOT on Every Prompt Execution

**Evaluations should NOT run on every user query** because:
- **Performance Impact**: Evals (especially LLM-as-judge) are slow and would degrade user experience
- **Cost**: Running evals on every query would be prohibitively expensive
- **Resource Usage**: Unnecessary load on systems and LLM services
- **User Experience**: Users don't need evaluation metrics, they need fast responses

### ✅ Evaluation Run Patterns

#### 1. **CI/CD Pipeline** (Recommended)
- **When**: On every PR, merge, or deployment
- **Frequency**: Every code change
- **Purpose**: Catch regressions before production
- **Scope**: Full evaluation suite against golden datasets
- **Example**:
  ```bash
  # Run in CI pipeline
  ./scripts/run-evals.sh --suite full --golden-dataset tests/golden/
  ```

#### 2. **Scheduled Batch Evaluations** (Recommended)
- **When**: Daily or weekly on a schedule
- **Frequency**: Regular intervals (e.g., daily at 2 AM)
- **Purpose**: Monitor quality trends over time
- **Scope**: Full evaluation suite or specific categories
- **Example**:
  ```bash
  # Cron job: Run daily at 2 AM
  0 2 * * * /path/to/scripts/run-evals.sh --suite full
  ```

#### 3. **Sampling in Production** (Optional)
- **When**: Sample a small percentage of real queries
- **Frequency**: 1-5% of production queries
- **Purpose**: Monitor real-world quality
- **Scope**: End-to-end query evals only
- **Implementation**:
  ```java
  // In SemanticExplorerController
  if (shouldSampleForEval(query)) { // 1-5% random sampling
      evalService.evaluateQueryAsync(query, response);
  }
  ```
- **Note**: Run asynchronously, don't block user response

#### 4. **On-Demand / Manual** (Development/Testing)
- **When**: During development, testing, or debugging
- **Frequency**: As needed
- **Purpose**: Validate changes, debug issues, experiment
- **Scope**: Any evaluation category
- **Example**:
  ```bash
  # Manual run during development
  ./scripts/run-evals.sh --category dictionary --project piggymetrics
  ```

#### 5. **A/B Testing** (Model/Prompt Changes)
- **When**: Comparing different models, prompts, or configurations
- **Frequency**: Before deploying changes
- **Purpose**: Validate improvements
- **Scope**: Full suite comparing baseline vs new version
- **Example**:
  ```bash
  # Compare baseline vs new prompt
  ./scripts/run-evals.sh --compare baseline.json new-prompt.json
  ```

#### 6. **Regression Testing** (Before Releases)
- **When**: Before major releases or hotfixes
- **Frequency**: Release gates
- **Purpose**: Ensure no quality degradation
- **Scope**: Full evaluation suite
- **Example**:
  ```bash
  # Run before release
  ./scripts/run-evals.sh --suite full --threshold 0.85
  ```

## Evaluation Execution Strategy

### Architecture: Separate Evaluation Service

**Keep evaluations separate from production code:**

```
Production Flow (Fast, No Evals):
User Query → Context Orchestrator → LLM Gateway → Response ✅

Evaluation Flow (Separate, Async):
Golden Dataset → Eval Service → Metrics → Dashboard 📊
```

### Implementation Pattern

```java
// Production code (no evals)
@RestController
public class SemanticExplorerController {
    
    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody QueryRequest request) {
        // Fast response, no evaluations
        String answer = agentOrchestrator.processQuery(request.getQuery());
        return ResponseEntity.ok(answer);
    }
}

// Evaluation service (separate, async)
@Service
public class EvaluationOrchestrator {
    
    public EvalResult runEvaluations(String projectName) {
        // Run evals against golden dataset
        // Not called during normal user queries
    }
}
```

### Evaluation Configuration

```yaml
# application.yaml
eval:
  enabled: false  # Disabled in production
  sampling:
    enabled: false  # Disabled by default
    rate: 0.01  # 1% if enabled
  scheduled:
    enabled: true  # Enable scheduled evals
    cron: "0 2 * * *"  # Daily at 2 AM
    suite: "full"  # Run full suite
```

## Why Evals Are Critical: Catching LLM Response Drift

### The Problem: Non-Deterministic LLM Responses

**Even without code changes, LLM responses can change** due to:
1. **Model Version Updates**: `gpt-4o-2024-11-20` → `gpt-4o-2024-12-15` (different behavior)
2. **Model Backend Changes**: Internal gateway updates, routing changes
3. **Non-Deterministic Outputs**: Same input can produce different outputs (temperature > 0)
4. **Prompt Drift**: Unintended prompt modifications
5. **Service Behavior Changes**: Internal gateway configuration changes
6. **Azure AD Token Issues**: Authentication changes affecting responses

### How Evals Catch This

**Evaluations detect quality degradation** even when:
- ✅ No code changes were made
- ✅ Same prompts are used
- ✅ Same models are configured
- ✅ Same queries are asked

**Example Scenario**:
```
Day 1: Query "What are business use cases?" 
       → Response: "User authentication, payment processing, account management" ✅
       → Eval Score: 0.92 (Excellent)

Day 30: Same query (no code changes)
        → Response: "The application has features" ❌ (Vague, degraded)
        → Eval Score: 0.58 (Failed threshold!)
        → Alert: LLM response quality degraded!
```

### Scheduled Evals Catch Drift

**Daily/Weekly scheduled evaluations** catch these issues:
- **Model version changes** (detected immediately)
- **Gradual quality degradation** (trends over time)
- **Response consistency issues** (variance metrics)
- **Backend service changes** (unexpected behavior changes)

## Recommended Evaluation Schedule

| Evaluation Type | Frequency | Trigger | Scope | Catches |
|----------------|-----------|---------|-------|---------|
| **Dictionary Service** | On PR, **Daily** | CI/CD, Cron | Full project | Model mapping changes |
| **Vector Retrieval** | On PR, **Daily** | CI/CD, Cron | Golden queries | Embedding model changes |
| **Symbol Parsing** | On PR, Weekly | CI/CD, Cron | Test projects | Parser stability |
| **Semantic Analysis** | On PR, **Daily** | CI/CD, Cron | Test queries | **LLM response drift** ⚠️ |
| **End-to-End Query** | On PR, **Daily** | CI/CD, Cron | Full suite | **Full pipeline drift** ⚠️ |
| **Production Sampling** | Optional | Async, 1-5% | Real queries | Real-world drift |

## Cost & Performance Considerations

### Evaluation Costs
- **LLM-as-Judge**: ~$0.01-0.10 per evaluation (depending on model)
- **Full Suite**: ~$10-50 per run (depending on dataset size)
- **Daily Runs**: ~$300-1500/month
- **CI/CD Runs**: Included in development overhead

### Performance Impact
- **Production**: Zero impact (evals don't run)
- **CI/CD**: 5-15 minutes per evaluation run (acceptable for PR checks)
- **Scheduled**: Run during off-peak hours (e.g., 2 AM)

### Recommendations
1. **Start with CI/CD only** (catch regressions)
2. **Add weekly scheduled runs** (monitor trends)
3. **Optional production sampling** (if budget allows)
4. **On-demand for debugging** (always available)

## Next Steps

1. **Create evaluation infrastructure** (this sprint)
2. **Build golden datasets** for test projects (piggymetrics, etc.)
3. **Implement first eval** (Dictionary Service)
4. **Add to CI/CD pipeline** (catch regressions)
5. **Set up scheduled runs** (monitor trends)
6. **Iterate and improve**

## Benefits

- **Quality Assurance**: Catch regressions early (CI/CD)
- **Continuous Improvement**: Measure impact of changes (scheduled)
- **User Trust**: Demonstrate system quality (dashboards)
- **Performance Tracking**: Monitor system improvements (trends)
- **Research**: Understand what works and what doesn't (experiments)
- **Zero Production Impact**: Evals don't affect user experience
