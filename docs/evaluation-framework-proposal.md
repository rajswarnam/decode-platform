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

### 4. Semantic Analysis Evals (Worker Reports)

**Purpose**: Measure quality of worker reports and synthesis

**Metrics**:
- **Relevance Score**: How relevant is the analysis to the query?
- **Completeness Score**: Does it cover all important aspects?
- **Accuracy Score**: Are the technical details correct?
- **Clarity Score**: Is the explanation clear and understandable?

**Test Cases**:
```java
{
  "query": "What are the business use cases for this project?",
  "expectedTopics": ["User Management", "Payment Processing"],
  "minRelevanceScore": 0.8,
  "minCompletenessScore": 0.7
}
```

**Implementation**:
- Create `SemanticAnalysisEvalService`
- Use LLM-as-judge for scoring (GPT-4, Claude, etc.)
- Evaluate against golden queries and expected outputs

### 5. End-to-End Query Evals

**Purpose**: Assess overall system quality from user's perspective

**Metrics**:
- **Response Relevance**: How relevant is the answer?
- **Response Accuracy**: Is the information correct?
- **Response Completeness**: Does it fully answer the query?
- **User Satisfaction**: Would a user find this helpful?

**Test Cases**:
```java
{
  "query": "How does authentication work in this system?",
  "expectedAnswers": [
    "Uses OAuth2",
    "JWT tokens",
    "AuthenticationService handles login"
  ],
  "minRelevanceScore": 0.85
}
```

**Implementation**:
- Create `QueryEvalService` for end-to-end evaluation
- Use real user queries or synthetic queries
- LLM-as-judge for scoring

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

## Next Steps

1. **Create evaluation infrastructure** (this sprint)
2. **Build golden datasets** for test projects (piggymetrics, etc.)
3. **Implement first eval** (Dictionary Service)
4. **Run baseline evaluation**
5. **Iterate and improve**

## Benefits

- **Quality Assurance**: Catch regressions early
- **Continuous Improvement**: Measure impact of changes
- **User Trust**: Demonstrate system quality
- **Performance Tracking**: Monitor system improvements
- **Research**: Understand what works and what doesn't
