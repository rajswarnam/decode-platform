# Vector Search Investigation - Root Cause Found

## Problem
Workers are finding 0 files even though 44,229 vectors exist in Qdrant.

## Investigation Results

### ✅ Vector Store is Working
- **Qdrant**: 44,229 points in `symbols` collection
- **Embedding Model**: TransformersEmbeddingModel loaded successfully (384 dimensions)
- **Vector Search**: Test query "order procurement purchase" returns 10 relevant documents
- **Example Results**:
  - `C_PurchaseCandiate_Create_PurchaseOrders.java`
  - `C_PurchaseCandidates_GeneratePurchaseOrders.java`
  - `PMM_PurchaseCandidate_OrderLine.java`

### ❓ Suspected Root Cause
The issue is in `retrieveContext()` method in `AgentOrchestrator.java`.

**Flow**:
1. Vector search returns documents ✅
2. Extract `symbol_id` from document metadata ✅
3. Look up symbol in `symbolRepository` ❓
4. Get `sourceFile` from symbol ❓
5. Fetch file content from MinIO ❓

**Hypothesis**: One of these steps is failing:
- Symbols in Qdrant don't exist in Postgres `symbolRepository`
- Symbols exist but have `sourceFile = null`
- Source files exist but MinIO fetch is failing

## Enhanced Logging Deployed

Added detailed logging to `retrieveContext()`:
```java
log.info("Vector search returned {} documents for worker {}", docs.size(), task.getPersona());
// ... processing ...
log.info("Retrieved {} unique files for worker {} (symbols found: {}, with source file: {})", 
    processedFiles.size(), task.getPersona(), symbolsFound.get(), symbolsWithSourceFile.get());
```

Also logs warnings for:
- `Symbol {} not found in repository` - symbol_id from Qdrant doesn't exist in Postgres
- `Symbol {} has no sourceFile attached` - symbol exists but sourceFile is null
- `Document has no symbol_id in metadata` - Qdrant document missing symbol_id

## Next Steps

### Test with BRD Query
Run your BRD query again and check the logs:

```bash
docker logs -f decode-workspace-context-orchestrator-1 | grep "Vector search\|Retrieved.*unique files\|Symbol.*not found\|no sourceFile"
```

**Expected Output**:
```
Vector search returned 50 documents for worker BACKEND_JAVA
Symbol abc-123 not found in repository  <-- This would explain the problem
Symbol def-456 has no sourceFile attached  <-- Or this
Retrieved 0 unique files for worker BACKEND_JAVA (symbols found: 0, with source file: 0)
```

### Likely Fixes

**If "Symbol not found in repository"**:
- The Qdrant vectors were created but the corresponding Postgres records were deleted/not created
- **Fix**: Re-run ingestion to sync Qdrant and Postgres

**If "Symbol has no sourceFile attached"**:
- Symbols exist but the relationship to source_files table is broken
- **Fix**: Update ingestion to properly link symbols to source files

**If MinIO fetch fails**:
- Files are referenced but not actually stored in MinIO
- **Fix**: Re-upload files to MinIO during ingestion

## Debug Endpoint Available

Test vector search directly:
```bash
curl "http://localhost:8082/api/debug/test-vector-search?query=order+procurement"
```

This bypasses the symbol lookup and shows raw vector search results.

## Summary

The vector search IS working perfectly. The problem is in the **symbol-to-file resolution** step. The enhanced logging will tell us exactly where it's breaking.

**Action**: Run a BRD query and share the logs to identify the exact failure point.
