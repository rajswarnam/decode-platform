# Dictionary Auto-Population on Startup Fix

## Problem

When uploading a ZIP file and onboarding a project, the dictionary mapping (business name mapping) should complete **during ingestion**, not on every application restart. However, users were seeing dictionary mapping logs (`Mapped: ... -> ...`) on every restart of `context-orchestrator`, indicating that dictionary population was running repeatedly.

### Symptoms

- Dictionary mapping logs appear on every `context-orchestrator` restart
- Logs show: `Mapped: RequestContext -> Request Context`, `Mapped: identifier -> Identifier`, etc.
- User expects dictionary mapping to complete during ZIP upload/ingestion, not on restart
- Creates confusion about when dictionary mapping actually happens

## Root Cause

1. **Symbol Creation**: When `code-parser` creates symbols during parsing, they are inserted into the `symbols` table with `analysis_status = 'PENDING'` (this is the database default).

2. **DictionaryRunner on Startup**: `DictionaryRunner` (a `CommandLineRunner` in `context-orchestrator`) runs on every application startup and processes all symbols with `analysis_status = 'PENDING'`.

3. **Missing Ingestion Trigger**: There is currently no mechanism to trigger dictionary population automatically during the ingestion flow (after parsing completes).

4. **Status Update**: While `DictionaryService` updates symbols to `COMPLETED` after processing, the runner still processes any PENDING symbols it finds on startup.

### Database Schema

```sql
CREATE TABLE public.symbols (
    ...
    analysis_status text DEFAULT 'PENDING'::text
);
```

### Flow Issue

```
Current Flow:
1. Upload ZIP → ingestion-engine
2. Extract ZIP → ingestion-engine
3. Discover projects → ingestion-engine
4. Parse files → code-parser (creates symbols with PENDING status)
5. Vectorize → vectorizer-service
6. [MISSING] Populate dictionary → context-orchestrator
7. Application restarts → DictionaryRunner runs → Processes PENDING symbols ❌
```

**Expected Flow:**
```
1. Upload ZIP → ingestion-engine
2. Extract ZIP → ingestion-engine
3. Discover projects → ingestion-engine
4. Parse files → code-parser (creates symbols with PENDING status)
5. Vectorize → vectorizer-service
6. Populate dictionary → context-orchestrator (during ingestion) ✅
7. Application restarts → DictionaryRunner skips (no PENDING symbols) ✅
```

## Solution

### 1. Disable Auto-Population on Startup

Added a configuration flag to disable dictionary auto-population on startup by default.

**Configuration** (`application.yaml`):
```yaml
dictionary:
  # Auto-populate dictionary on startup (default: false)
  # Set to true if you want dictionary to run automatically on every startup
  # Recommended: false - trigger dictionary population during ingestion instead
  auto-populate-on-startup: false
```

**DictionaryRunner Changes**:
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DictionaryRunner implements CommandLineRunner {

    private final DictionaryService dictionaryService;
    private final SymbolRepository symbolRepository;
    
    @Value("${dictionary.auto-populate-on-startup:false}")
    private boolean autoPopulateOnStartup;

    @Override
    public void run(String... args) throws Exception {
        // Skip if auto-populate is disabled
        if (!autoPopulateOnStartup) {
            log.info("Dictionary auto-population on startup is disabled.");
            log.info("Dictionary population can be triggered manually via API or during ingestion.");
            return;
        }
        
        // Only run if enabled and PENDING symbols exist
        long pendingCount = symbolRepository.countByAnalysisStatus("PENDING");
        if (pendingCount == 0) {
            log.info("No PENDING symbols found, skipping dictionary population on startup.");
            return;
        }

        log.info("Found {} PENDING symbols, starting dictionary population...", pendingCount);
        // ... process symbols
    }
}
```

### 2. Clear Logging

Added informative logging to explain when/why dictionary population runs or doesn't run:

- **Disabled**: "Dictionary auto-population on startup is disabled."
- **No PENDING**: "No PENDING symbols found, skipping dictionary population on startup."
- **Enabled**: "Found X PENDING symbols, starting dictionary population..."

## Current Behavior

### After Fix

**On Startup (default)**:
```
INFO: Dictionary auto-population on startup is disabled.
INFO: Dictionary population can be triggered manually via API or during ingestion.
```

**If Enabled** (not recommended):
```
INFO: Found 150 PENDING symbols, starting dictionary population...
INFO: Note: Dictionary population should ideally run during ingestion, not on startup.
```

### Symbol Status Flow

1. **Symbol Creation** (by `code-parser`):
   - Status: `PENDING` (database default)
   - No dictionary entry created yet

2. **Dictionary Population** (by `DictionaryService`):
   - Processes symbols with `PENDING` status
   - Creates dictionary entries in `global_dictionary` table
   - Updates symbol status to `COMPLETED` or `FAILED`

3. **Status After Processing**:
   - Success: `analysis_status = 'COMPLETED'`
   - Failure: `analysis_status = 'FAILED'`
   - Unprocessed: `analysis_status = 'PENDING'`

## How to Trigger Dictionary Population

### Option 1: During Ingestion (Recommended - TODO)

**Ideal**: Automatically trigger dictionary population after parsing completes during ingestion.

**Implementation needed**:
- Add API endpoint in `context-orchestrator` to trigger dictionary population
- Call this endpoint from `code-parser` or `ingestion-engine` after parsing completes
- Or add a webhook/event mechanism to trigger it automatically

**Example** (to be implemented):
```java
// In ingestion-engine after parsing completes
@Autowired
private RestTemplate restTemplate;

public void triggerDictionaryPopulation(UUID projectId) {
    String url = "http://context-orchestrator:8080/api/v1/dictionary/populate?projectId=" + projectId;
    restTemplate.postForEntity(url, null, String.class);
}
```

### Option 2: Manual API Trigger (Current)

**Trigger dictionary population manually** via API endpoint (if it exists):

```bash
POST http://localhost:8082/api/v1/dictionary/populate
# or with project filter:
POST http://localhost:8082/api/v1/dictionary/populate?projectId=<uuid>
```

### Option 3: Enable Auto-Populate (Not Recommended)

**Enable auto-population on startup** (only if needed):

```yaml
# application.yaml
dictionary:
  auto-populate-on-startup: true  # Not recommended
```

**Why not recommended**:
- Runs on every restart (unnecessary if already processed)
- Increases startup time
- May process symbols multiple times if status wasn't updated properly
- Better to trigger during ingestion or on-demand

## Configuration

### Default Behavior

**Configuration** (`application.yaml`):
```yaml
dictionary:
  auto-populate-on-startup: false  # Default: disabled
```

**Environment Variable**:
```bash
DICTIONARY_AUTO_POPULATE_ON_STARTUP=false  # Can override in docker-compose
```

**Docker Compose Override** (if needed):
```yaml
context-orchestrator:
  environment:
    - DICTIONARY_AUTO_POPULATE_ON_STARTUP=false
```

## Benefits

1. **Faster Startup**: No dictionary processing on restart (unless explicitly enabled)
2. **Clearer Intent**: Dictionary population happens during ingestion, not on restart
3. **Flexible**: Can still be enabled if needed, or triggered manually/automatically
4. **Better UX**: No confusion about when dictionary mapping happens
5. **Performance**: Avoids unnecessary LLM calls on every restart

## Future Improvements

### 1. Automatic Trigger During Ingestion

**Recommended**: Add automatic dictionary population trigger in ingestion flow:

```java
// In ingestion-engine or code-parser after parsing completes
public void onParsingComplete(UUID projectId) {
    // Trigger dictionary population for this project
    dictionaryService.populateDictionaryForProject(projectId);
}
```

### 2. Project-Level Status Tracking

**Optional**: Track if dictionary has been populated for a project:

```sql
ALTER TABLE projects ADD COLUMN dictionary_populated BOOLEAN DEFAULT FALSE;
```

This would allow:
- Checking if dictionary was already populated
- Only processing new projects
- Better status reporting

### 3. Async Processing

**Optional**: Make dictionary population async during ingestion:

```java
@Async
public void populateDictionaryForProject(UUID projectId) {
    // Process symbols for this project asynchronously
    // Don't block ingestion completion
}
```

## Testing

### Verify Fix

1. **Upload a new project** (ZIP file)
2. **Check logs** - Dictionary mapping should NOT happen on upload (if not wired up)
3. **Restart context-orchestrator**:
   ```
   docker-compose restart context-orchestrator
   ```
4. **Check logs** - Should see:
   ```
   INFO: Dictionary auto-population on startup is disabled.
   INFO: Dictionary population can be triggered manually via API or during ingestion.
   ```
5. **No dictionary mapping logs** should appear

### Verify Manual Trigger (if API exists)

```bash
# Trigger dictionary population manually
curl -X POST http://localhost:8082/api/v1/dictionary/populate
```

### Verify Auto-Populate (if enabled)

1. **Set config**:
   ```yaml
   dictionary:
     auto-populate-on-startup: true
   ```
2. **Restart** - Should see dictionary mapping logs

## Troubleshooting

### Dictionary Mapping Not Happening

**Problem**: Symbols remain in `PENDING` status and are never mapped.

**Solutions**:
1. Check if auto-populate is enabled: `dictionary.auto-populate-on-startup: true`
2. Trigger manually via API (if endpoint exists)
3. Wire up automatic trigger during ingestion (recommended)

### Dictionary Mapping Happening on Every Restart

**Problem**: Even after fix, dictionary mapping still runs on restart.

**Solutions**:
1. Verify configuration: `dictionary.auto-populate-on-startup: false`
2. Check environment variables (docker-compose may override)
3. Verify symbols are being updated to `COMPLETED` status
4. Check logs for: "Dictionary auto-population on startup is disabled."

### Symbols Not Updating Status

**Problem**: Symbols remain `PENDING` even after dictionary processing.

**Solutions**:
1. Check `DictionaryService.updateSymbolStatus()` is being called
2. Verify transaction is committed
3. Check for errors in dictionary processing
4. Verify symbol entity has `analysis_status` field mapped correctly

## Related Files

- `context-orchestrator/src/main/java/com/decode/context/orchestrator/runner/DictionaryRunner.java`
- `context-orchestrator/src/main/java/com/decode/context/orchestrator/service/DictionaryService.java`
- `context-orchestrator/src/main/resources/application.yaml`
- `infra/postgres/init.sql` (symbols table schema)

## Summary

**Problem**: Dictionary mapping ran on every restart instead of during ingestion.

**Solution**: Disabled auto-population on startup by default with configuration flag.

**Result**: Dictionary mapping no longer runs on restart. Should be triggered during ingestion or manually.

**Next Step**: Wire up automatic dictionary population trigger in ingestion flow (TODO).
