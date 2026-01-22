# Querying Transaction Fields: LLM vs Direct Database Query

## Current Approach

**Yes, the current system uses LLM** for all queries, including "all fields related to a transaction type". Here's how it works:

### Current Flow (Uses LLM)

1. **User Query**: "What are all fields related to Transaction VKWFLOSA?"
2. **Vector Search**: System searches Qdrant vector store for relevant symbols
3. **Context Building**: Retrieves code snippets and symbol metadata
4. **LLM Synthesis**: LLM processes the context and generates a formatted response
5. **Response**: Returns LLM-generated answer with field list

**Why LLM is used:**
- Synthesizes information from multiple sources
- Formats the response in a readable way
- Handles natural language queries
- Can infer relationships between symbols

## When LLM is NOT Required

For simple, structured queries like "all fields related to a transaction", you can query the database **directly without LLM**:

### Option 1: Direct SQL Query (No LLM)

```sql
-- Find all fields related to a specific transaction
SELECT 
    s.name as field_name,
    s.category,
    s.type,
    sf.file_name as aclf_file,
    p.name as project_name
FROM symbols s
JOIN source_files sf ON s.file_id = sf.id
JOIN projects p ON sf.project_id = p.id
WHERE s.category IN ('ACLF_FIELD_REFERENCE', 'ACLF_DATAFIELD_REFERENCE')
  AND (
    -- Fields referenced in transaction blocks
    s.type LIKE '%VKWFLOSA%'
    -- OR fields in the same file as the transaction
    OR sf.id IN (
      SELECT sf2.id 
      FROM symbols s2
      JOIN source_files sf2 ON s2.file_id = sf2.id
      WHERE s2.category = 'ACLF_TRANSACTION'
        AND s2.name = 'VKWFLOSA'
    )
  )
ORDER BY s.name;
```

### Option 2: Direct Vector Search (No LLM Synthesis)

You can query the vector store directly and return raw results without LLM synthesis:

```java
// Direct vector search - no LLM needed
SearchRequest search = SearchRequest.builder()
    .query("Transaction VKWFLOSA fields")
    .topK(50)
    .filterExpression("category == 'ACLF_FIELD_REFERENCE' OR category == 'ACLF_DATAFIELD_REFERENCE'")
    .build();

List<Document> results = vectorStore.similaritySearch(search);
// Return raw results without LLM synthesis
```

### Option 3: Database Query by Transaction Name (No LLM)

```sql
-- Find transaction and all related fields from the same file
WITH transaction_file AS (
  SELECT sf.id as file_id, sf.file_name, p.name as project_name
  FROM symbols s
  JOIN source_files sf ON s.file_id = sf.id
  JOIN projects p ON sf.project_id = p.id
  WHERE s.category = 'ACLF_TRANSACTION'
    AND s.name = 'VKWFLOSA'
)
SELECT 
    s.name as field_name,
    s.category,
    s.type,
    tf.file_name,
    tf.project_name
FROM symbols s
JOIN transaction_file tf ON s.file_id = tf.file_id
WHERE s.category IN ('ACLF_FIELD_REFERENCE', 'ACLF_DATAFIELD_REFERENCE', 'ACLF_DATAFIELD')
ORDER BY s.name;
```

## Recommended Approach: Hybrid

For **transaction field queries**, you have two options:

### A. Simple List Query (No LLM - Fast, Structured)

**Use Case**: "List all fields for Transaction X"
- Query database directly
- Return structured JSON
- No LLM needed
- Fast response (< 100ms)

**Implementation**: Add a new endpoint:
```java
@GetMapping("/api/v1/explore/transaction/{transactionName}/fields")
public ResponseEntity<List<FieldInfo>> getTransactionFields(
    @PathVariable String transactionName,
    @RequestParam(required = false) String projectName) {
    
    // Direct database query - no LLM
    List<Symbol> fields = symbolRepository.findFieldsByTransaction(transactionName, projectName);
    return ResponseEntity.ok(fields);
}
```

### B. Complex Analysis Query (LLM - Comprehensive, Natural Language)

**Use Case**: "Explain how Transaction X uses its fields and what they represent"
- Use vector search + LLM
- Provides context and explanations
- Handles natural language
- Slower response (2-5 seconds)

**Current Implementation**: Uses `SemanticExplorerService.exploreStream()`

## Performance Comparison

| Approach | Speed | Accuracy | Cost | Use Case |
|----------|-------|----------|------|----------|
| **Direct SQL** | < 100ms | High (exact match) | Free | Simple list queries |
| **Vector Search (no LLM)** | 200-500ms | High (semantic) | Free | Similarity search |
| **Vector + LLM** | 2-5 seconds | High (with context) | Token cost | Complex analysis |

## When to Use Each Approach

### Use Direct Database Query (No LLM) When:
- ✅ Query is simple and structured ("list all fields for X")
- ✅ You need exact matches
- ✅ Speed is critical
- ✅ You want structured JSON response
- ✅ Query pattern is predictable

### Use LLM When:
- ✅ Query requires explanation or context
- ✅ Natural language understanding needed
- ✅ Need to synthesize information from multiple sources
- ✅ Query is complex or ambiguous
- ✅ User wants formatted, readable response

## Implementation Recommendation

**Add a new direct query endpoint** for transaction fields:

```java
@GetMapping("/api/v1/explore/transaction/{transactionName}/fields")
public ResponseEntity<Map<String, Object>> getTransactionFieldsDirect(
    @PathVariable String transactionName,
    @RequestParam(required = false) String projectName,
    @RequestParam(required = false) List<String> projects) {
    
    // Direct database query - no LLM, no vector search
    List<Symbol> transactionSymbols = symbolRepository
        .findByCategoryAndName("ACLF_TRANSACTION", transactionName);
    
    if (transactionSymbols.isEmpty()) {
        return ResponseEntity.notFound().build();
    }
    
    // Find all fields from same file(s) as the transaction
    Set<UUID> fileIds = transactionSymbols.stream()
        .map(s -> s.getSourceFile().getId())
        .collect(Collectors.toSet());
    
    List<Symbol> fields = symbolRepository
        .findByFileIdInAndCategoryIn(
            fileIds,
            Arrays.asList("ACLF_FIELD_REFERENCE", "ACLF_DATAFIELD_REFERENCE", "ACLF_DATAFIELD")
        );
    
    Map<String, Object> response = new HashMap<>();
    response.put("transaction", transactionName);
    response.put("fields", fields.stream().map(s -> Map.of(
        "name", s.getName(),
        "category", s.getCategory(),
        "type", s.getType()
    )).collect(Collectors.toList()));
    
    return ResponseEntity.ok(response);
}
```

This would:
- ✅ Return results in < 100ms
- ✅ No LLM token cost
- ✅ Structured JSON response
- ✅ Exact matches (no ambiguity)

## Summary

**Current System**: Uses LLM for all queries (including simple ones)

**Recommended**: 
- **Simple queries** → Direct database query (no LLM)
- **Complex queries** → Vector search + LLM (current approach)

For "all fields related to a transaction", you **don't need LLM** - a direct database query is faster, cheaper, and more accurate.
