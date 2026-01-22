# Parsing to Vectorization Flow: How Symbols Get to Qdrant

## Overview

Yes, **after parsing, symbols are automatically vectorized and added to Qdrant**, but this happens in a separate step through the vectorizer service.

## Complete Flow

```
1. Code Parser Service (code-parser)
   ↓
   Parses files from MinIO
   ↓
   Extracts symbols (functions, classes, fields, etc.)
   ↓
   Saves symbols to PostgreSQL database
   ↓
   Triggers Vectorizer Service (if symbols found)
   ↓
2. Vectorizer Service (vectorizer-service)
   ↓
   Reads symbols from PostgreSQL
   ↓
   Creates embeddings (vector representations)
   ↓
   Adds to Qdrant vector store
```

## Step-by-Step Process

### Step 1: Parsing (code-parser service)

**Location:** `ParserOrchestratorService.processProject()`

1. Reads files from MinIO
2. Parses files using appropriate parser (Java, C, COBOL, ACLF, etc.)
3. Extracts symbols (classes, functions, fields, etc.)
4. Saves symbols to PostgreSQL `symbols` table

**Code:**
```java
boolean symbolsFound = processProjectFromMinio(project);

// CHAIN: Trigger Vectorizer only if symbols were actually parsed
if (symbolsFound) {
    log.info("🔗 [PARSER SERVICE] Symbols found for {}. Triggering vectorizer...", project.getName());
    triggerVectorizer(project);
}
```

### Step 2: Vectorization Trigger (code-parser → vectorizer-service)

**Location:** `ParserOrchestratorService.triggerVectorizer()`

After parsing completes successfully, the parser service:
1. Checks if vectorizer service is ready/healthy
2. Makes HTTP POST call to vectorizer service:
   ```
   POST /api/vectorizer/trigger?projectId={projectId}
   ```
3. Returns immediately (vectorization runs asynchronously)

**Code:**
```java
String url = vectorizerUrl + "/api/vectorizer/trigger?projectId=" + project.getId();
ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
```

### Step 3: Vectorization (vectorizer-service)

**Location:** `VectorizerService.vectorizeProject()`

When triggered, the vectorizer service:
1. Reads all symbols for the project from PostgreSQL
2. Checks if symbols already exist in Qdrant (duplicate prevention)
3. Creates embeddings for new symbols
4. Adds documents to Qdrant vector store

**Code:**
```java
public void vectorizeProject(UUID projectId) {
    List<Symbol> symbols = symbolRepository.findAllBySourceFile_Project_Id(projectId);
    vectorizeSymbols(symbols);
}

private void vectorizeSymbols(List<Symbol> symbols) {
    // Check for duplicates in Qdrant
    // Create Document objects with metadata
    // Add to VectorStore (Qdrant)
    vectorStore.add(documentsToAdd);
}
```

## What Gets Stored in Qdrant

For each symbol, a `Document` is created with:

**Content (for embedding):**
```
Domain: {domain} | Project: {projectName} | Category: {category} | Symbol: {name}
```

**Metadata:**
- `symbol_id`: UUID of the symbol
- `project_id`: UUID of the project
- `domain`: Project domain/name
- `category`: Symbol category (e.g., "ACLF_TRANSACTION", "COBOL_FIELD")
- `name`: Symbol name

## Automatic vs Manual

### Automatic (Current Behavior)
- ✅ **Automatic**: After parsing completes, vectorizer is automatically triggered
- ✅ **Conditional**: Only triggers if symbols were found during parsing
- ✅ **Async**: Vectorization runs in background (doesn't block parsing)

### Manual Trigger (If Needed)
You can also manually trigger vectorization:
```bash
curl -X POST "http://localhost:8080/api/vectorizer/trigger?projectId={projectId}"
```

## Potential Issues

### 1. Vectorizer Service Not Ready
If the vectorizer service is not ready when parsing completes:
- ⚠️ Symbols are saved to PostgreSQL ✅
- ⚠️ Vectorization is skipped (not added to Qdrant) ❌
- ⚠️ Log message: "Vectorizer service is not ready. Skipping trigger..."

**Solution:** Vectorizer will process on next ingestion or you can manually trigger.

### 2. Vectorization Fails
If vectorization fails:
- ⚠️ Symbols remain in PostgreSQL ✅
- ⚠️ Symbols are NOT in Qdrant ❌
- ⚠️ You'll see error logs in vectorizer-service

**Solution:** Check vectorizer-service logs and retry manually.

### 3. Duplicate Prevention
The vectorizer checks for existing symbols in Qdrant before adding:
- Uses `symbol_id` metadata to check if symbol already exists
- Skips symbols that are already vectorized
- Prevents duplicate embeddings

## Verification

### Check if Symbols are in PostgreSQL
```sql
SELECT COUNT(*) 
FROM symbols 
WHERE source_file_id IN (
    SELECT id FROM source_files WHERE project_id = '{projectId}'
);
```

### Check if Symbols are in Qdrant
The vectorizer service has a status endpoint:
```bash
curl "http://localhost:8080/api/vectorizer/status?projectId={projectId}"
```

Or check Qdrant directly (if you have access):
- Filter by `project_id == '{projectId}'`
- Count documents with that project_id

## Summary

**Yes, files get updated in Qdrant after parsing**, but:

1. ✅ **Parsing happens first** → Symbols saved to PostgreSQL
2. ✅ **Vectorization happens second** → Symbols read from PostgreSQL and added to Qdrant
3. ✅ **Automatic trigger** → Parser automatically calls vectorizer after successful parsing
4. ⚠️ **Async process** → Vectorization runs in background, may take time
5. ⚠️ **Conditional** → Only triggers if symbols were found

If you see "127/333 projects completed" in parsing, those 127 projects have:
- ✅ Symbols extracted and saved to PostgreSQL
- ✅ Vectorizer triggered (if vectorizer service was ready)
- ⏳ Vectorization may still be in progress (runs async)

The remaining 206 projects are still being parsed, so they haven't reached the vectorization step yet.
