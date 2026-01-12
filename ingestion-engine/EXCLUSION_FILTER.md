# Ingestion Engine - Exclusion Filter Integration

## Overview
The Ingestion Engine now includes **fiscal protection** via the `ExclusionService`, preventing binary artifacts and build noise from consuming your 250k TPM quota.

## Architecture

```
┌─────────────────────────────────────────┐
│      IngestionRunner (Entry Point)      │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│     ProjectDiscoveryService             │
│  - Scans workspace recursively          │
│  - Uses ExclusionService for filtering  │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│        ExclusionService                 │
│  - Checks .decodeignore patterns        │
│  - MIME-type detection                  │
│  - File size limits                     │
│  - Logs exclusion stats                 │
└─────────────────────────────────────────┘
```

## What Gets Excluded

### Binary Files
- `.class`, `.jar`, `.dll`, `.exe`, `.so`, `.bin`
- **Reason**: Compiled artifacts provide no semantic value

### Media & Office Documents
- `.jpg`, `.png`, `.pdf`, `.doc`, `.xls`
- **Reason**: Not source code, waste TPM quota

### TIBCO/Mainframe Artifacts
- `.LOAD`, `.LNK`, `.vcrepo`, `.tra`, `.projlib`
- **Reason**: Enterprise middleware binaries (Fusion Argo specific)

### Build Directories
- `node_modules/`, `target/`, `bin/`, `obj/`, `.gradle/`
- **Reason**: Generated files, not source

### Data Files (High TPM Risk)
- `.dat`, `.db`, `.sqlite`, `.dump`
- **Reason**: Can be gigabytes, would exhaust daily quota

## Integration Points

### 1. ProjectDiscoveryService
**File**: `ingestion-engine/src/main/java/com/decode/ingestion/engine/service/ProjectDiscoveryService.java`

**Changes**:
```java
@Service
@RequiredArgsConstructor
public class ProjectDiscoveryService {
    private final ExclusionService exclusionService; // ✅ Added

    public void discoverAndRegisterProjects(String rootPath, String gitUrl) {
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, ...) {
                // ✅ Fiscal protection
                if (exclusionService.shouldExclude(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // ... rest of logic
            }
        });
        
        // ✅ Log stats
        exclusionService.logExclusionStats(totalDirs, excludedDirs);
    }
}
```

### 2. ExclusionService
**File**: `ingestion-engine/src/main/java/com/decode/ingestion/service/ExclusionService.java`

**Key Methods**:
- `shouldExclude(Path)`: Returns `true` if file/dir should be skipped
- `logExclusionStats(int, int)`: Logs noise reduction percentage

## Configuration

### .decodeignore File
**Location**: Project root (`.decodeignore`)

**Format**: Standard gitignore syntax

**Example**:
```gitignore
# Binaries
*.class
*.jar
*.dll

# TIBCO
*.vcrepo
*.tra

# Build dirs
node_modules/
target/
```

## Usage

### Run Ingestion
```bash
docker-compose run --rm ingestion-engine
```

### Expected Logs
```
INFO  --- Starting Auto-Discovery ---
INFO  --- Scanning Workspace Root: /workspace
DEBUG --- Excluded directory: /workspace/node_modules
DEBUG --- Excluded directory: /workspace/target
INFO  --- Exclusion Stats: 5786/5971 files excluded (96.9% noise reduction)
INFO  --- Registered New Project: fusion-argo [C/C++, COBOL]
INFO  --- Auto-Discovery Complete ---
```

## Metrics

### Fiscal Impact (Example)
| Metric | Value |
|--------|-------|
| Total Files | 5,971 |
| Files Excluded | 5,786 (96.9%) |
| Tokens Saved | ~106.8M (427x daily quota!) |
| Size Excluded | 417 MB |

**Without Exclusion**: Would consume 427 days of TPM quota in one run  
**With Exclusion**: Only 185 source files processed ✅

## Testing

### Pre-Ingestion Report
Run before ingestion to see what will be excluded:

```bash
# Via Docker (cross-platform)
./pre-ingestion-report-docker.sh /path/to/project

# Via Java (if compiled)
java -cp ingestion-engine/target/classes \
  com.decode.ingestion.tool.PreIngestionReport /path/to/project
```

### Manual Test
```bash
# Create test files
mkdir test-exclusion
echo "public class Test {}" > test-exclusion/Good.java
echo "binary" > test-exclusion/Bad.class

# Run ingestion
docker-compose run --rm -v $(pwd)/test-exclusion:/workspace ingestion-engine

# Verify: Only Good.java should be processed
```

## Troubleshooting

### Issue: Files not being excluded
**Solution**: Check `.decodeignore` syntax. Ensure patterns match file paths.

### Issue: Too many files excluded
**Solution**: Review exclusion patterns. Adjust `ExclusionService.ALLOWED_EXTENSIONS`.

### Issue: Logs show 0% exclusion
**Solution**: Verify `ExclusionService` is autowired in `ProjectDiscoveryService`.

## Cross-Platform Compatibility

✅ **Windows**: Uses `java.nio.file` (no bash dependencies)  
✅ **Linux**: Standard POSIX file operations  
✅ **macOS**: Native Java file handling  
✅ **Alpine**: Works in musl libc environments  

## References

- **BRD Section 5.8**: Data Ingestion Exclusions
- **GEMINI.md Rule #9**: Computational Determinism
- **GEMINI.md Task**: ✅ Implement Binary/Noise Exclusion Filtering

## Next Steps

1. ✅ Integration complete
2. ⏳ Test with real Fusion Argo codebase
3. ⏳ Monitor exclusion stats in production
4. ⏳ Tune `.decodeignore` based on actual patterns
