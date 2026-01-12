# Mission: Intelligent Blueprint Refinement System

**Status:** 🟡 Planned  
**Priority:** High  
**Estimated Effort:** 3-4 days  
**Dependencies:** MinIO Blueprint Persistence (✅ Complete)

---

## 🎯 Objective

Implement an intelligent "Refine Blueprint" feature that allows users to iteratively enhance saved blueprints with additional details while automatically detecting and incorporating code changes from the vector database.

---

## 💡 Problem Statement

Currently, when users want more detail about a saved blueprint, they have two suboptimal options:
1. **Re-run the entire query** - Wastes tokens, loses context, creates duplicate blueprints
2. **Ask follow-up questions** - Creates separate analyses that aren't consolidated

**Example Scenario:**
- User generates "Notification Service Use Cases" blueprint
- Blueprint mentions "SMTP Server" but doesn't show payload schema
- User wants to add payload details without regenerating everything
- User also wants to know if the code has changed since the blueprint was created

---

## 🧠 Solution Architecture

### **Core Concept: Context-Aware Iterative Refinement**

The system will:
1. Load the existing blueprint
2. Detect code changes since blueprint creation
3. Fetch additional context based on refinement prompt
4. Use LLM to merge new details into existing content
5. Save as a new version with change tracking

---

## 🏗️ Technical Design

### **1. Data Model Extensions**

#### **Blueprint Metadata (MinIO Object Tags)**
```json
{
  "title": "Notification Services Use Cases",
  "category": "use-cases",
  "tags": "REST API, CRUD, microservices",
  "query": "what are the usecases in notification service",
  "version": "2",
  "parentBlueprintId": "blueprints/piggymetrics/use-cases/notification-v1.md",
  "createdAt": "2026-01-12T14:21:23Z",
  "refinementPrompt": "add payload schemas for external services",
  "codeChangesDetected": "true",
  "symbolsAnalyzed": "15",
  "newSymbolsSinceParent": "2"
}
```

#### **Refinement History Table (PostgreSQL)**
```sql
CREATE TABLE blueprint_refinements (
    id SERIAL PRIMARY KEY,
    blueprint_path VARCHAR(500) NOT NULL,
    parent_blueprint_path VARCHAR(500),
    version INTEGER NOT NULL,
    refinement_prompt TEXT NOT NULL,
    code_changes_detected BOOLEAN DEFAULT FALSE,
    new_symbols_count INTEGER DEFAULT 0,
    modified_symbols_count INTEGER DEFAULT 0,
    deleted_symbols_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    created_by VARCHAR(100)
);
```

---

### **2. Backend Implementation**

#### **New Endpoint: POST /api/v1/explore/refine-blueprint**

**Request:**
```json
{
  "blueprintPath": "blueprints/piggymetrics/use-cases/notification-v1.md",
  "refinementPrompt": "add payload schemas for external service calls",
  "project": "piggymetrics",
  "updateExisting": false  // If true, overwrites; if false, creates new version
}
```

**Response:**
```json
{
  "status": "success",
  "newBlueprintPath": "blueprints/piggymetrics/use-cases/notification-v2.md",
  "version": 2,
  "codeChanges": {
    "detected": true,
    "newSymbols": 2,
    "modifiedSymbols": 1,
    "deletedSymbols": 0,
    "summary": "2 new methods added, 1 signature changed"
  },
  "tokensUsed": 3500
}
```

---

#### **Processing Flow**

```java
@PostMapping("/refine-blueprint")
public ResponseEntity<Map<String, Object>> refineBlueprint(@RequestBody RefinementRequest request) {
    // STEP 1: Load existing blueprint from MinIO
    String existingBlueprint = minioClient.getObject(request.getBlueprintPath());
    Map<String, String> metadata = minioClient.getObjectMetadata(request.getBlueprintPath());
    
    // STEP 2: Detect code changes since blueprint creation
    String blueprintTimestamp = metadata.get("createdAt");
    ChangeReport changes = changeDetectionService.detectChanges(
        request.getProject(),
        metadata.get("query"),
        blueprintTimestamp
    );
    
    // STEP 3: Fetch enhanced context
    EnhancedContext context = contextRetrievalService.fetchContext(
        request.getProject(),
        metadata.get("query"),
        request.getRefinementPrompt(),
        changes
    );
    
    // STEP 4: Build refinement prompt for LLM
    String llmPrompt = buildRefinementPrompt(
        existingBlueprint,
        request.getRefinementPrompt(),
        context,
        changes
    );
    
    // STEP 5: Call LLM to refine blueprint
    String refinedBlueprint = llmService.refineBlueprint(llmPrompt);
    
    // STEP 6: Save refined blueprint
    int newVersion = Integer.parseInt(metadata.getOrDefault("version", "1")) + 1;
    String newPath = generateVersionedPath(request.getBlueprintPath(), newVersion);
    
    Map<String, String> newMetadata = new HashMap<>(metadata);
    newMetadata.put("version", String.valueOf(newVersion));
    newMetadata.put("parentBlueprintId", request.getBlueprintPath());
    newMetadata.put("refinementPrompt", request.getRefinementPrompt());
    newMetadata.put("codeChangesDetected", String.valueOf(changes.hasChanges()));
    
    minioClient.putObject(newPath, refinedBlueprint, newMetadata);
    
    // STEP 7: Record refinement history
    blueprintRefinementRepository.save(new BlueprintRefinement(
        newPath,
        request.getBlueprintPath(),
        newVersion,
        request.getRefinementPrompt(),
        changes
    ));
    
    return ResponseEntity.ok(buildResponse(newPath, newVersion, changes));
}
```

---

#### **Change Detection Service**

```java
@Service
public class ChangeDetectionService {
    
    @Autowired
    private QdrantVectorRepository qdrantRepository;
    
    public ChangeReport detectChanges(String project, String originalQuery, String blueprintTimestamp) {
        // 1. Query Qdrant for symbols related to original query
        List<CodeSymbol> currentSymbols = qdrantRepository.searchSymbols(project, originalQuery);
        
        // 2. Filter symbols by timestamp
        Instant blueprintTime = Instant.parse(blueprintTimestamp);
        List<CodeSymbol> newSymbols = currentSymbols.stream()
            .filter(s -> s.getIndexedAt().isAfter(blueprintTime))
            .collect(Collectors.toList());
        
        List<CodeSymbol> modifiedSymbols = currentSymbols.stream()
            .filter(s -> s.getLastModified().isAfter(blueprintTime) && 
                         s.getIndexedAt().isBefore(blueprintTime))
            .collect(Collectors.toList());
        
        // 3. Detect deleted symbols (symbols in original blueprint but not in current codebase)
        // This requires storing the original symbol list in metadata
        
        return new ChangeReport(newSymbols, modifiedSymbols, deletedSymbols);
    }
}
```

---

#### **Enhanced Context Retrieval**

```java
@Service
public class ContextRetrievalService {
    
    public EnhancedContext fetchContext(
        String project,
        String originalQuery,
        String refinementPrompt,
        ChangeReport changes
    ) {
        // 1. Fetch original context (symbols from original query)
        List<CodeSymbol> originalSymbols = qdrantRepository.searchSymbols(project, originalQuery);
        
        // 2. Fetch additional context based on refinement prompt
        // Example: If prompt mentions "payload", prioritize DTO/model classes
        List<String> keywords = extractKeywords(refinementPrompt); // ["payload", "schema", "external"]
        List<CodeSymbol> additionalSymbols = qdrantRepository.searchSymbols(project, keywords);
        
        // 3. Combine and deduplicate
        Set<CodeSymbol> allSymbols = new HashSet<>();
        allSymbols.addAll(originalSymbols);
        allSymbols.addAll(additionalSymbols);
        allSymbols.addAll(changes.getNewSymbols());
        allSymbols.addAll(changes.getModifiedSymbols());
        
        return new EnhancedContext(
            originalSymbols,
            additionalSymbols,
            changes.getNewSymbols(),
            changes.getModifiedSymbols(),
            changes.getSummary()
        );
    }
}
```

---

#### **LLM Refinement Prompt Template**

```java
private String buildRefinementPrompt(
    String existingBlueprint,
    String refinementPrompt,
    EnhancedContext context,
    ChangeReport changes
) {
    return String.format("""
        You are refining an existing technical blueprint for a software system.
        
        ## ORIGINAL BLUEPRINT
        %s
        
        ## CODE CHANGES SINCE BLUEPRINT CREATION
        %s
        
        ## CURRENT CODE CONTEXT
        %s
        
        ## USER REFINEMENT REQUEST
        "%s"
        
        ## INSTRUCTIONS
        1. **Preserve all existing content** from the original blueprint
        2. **Add the requested details** based on the refinement prompt
        3. **Update any sections** affected by code changes (mark with "🔄 Updated in v%d")
        4. **Add new sections** for newly discovered code (mark with "✨ New in v%d")
        5. **Add a "📝 Refinement History" section** at the end documenting:
           - What was added
           - What was updated
           - Code changes detected
        6. **Use proper Markdown formatting** with headers, code blocks, and examples
        
        Output the complete refined blueprint in Markdown format.
        """,
        existingBlueprint,
        changes.toMarkdown(),
        context.toMarkdown(),
        refinementPrompt,
        context.getVersion(),
        context.getVersion()
    );
}
```

---

### **3. Frontend Implementation**

#### **UI Changes to Sidebar**

```tsx
// In App.tsx - Saved Blueprints Section
{blueprints.map((bp: any, idx: number) => (
  <div key={idx} style={{ ... }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <div onClick={() => setSelectedBlueprint(bp.path)} style={{ flex: 1, cursor: 'pointer' }}>
        <div style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>
          {bp.filename.replace(/_\d{8}_\d{6}\.md$/, '').replace(/-/g, ' ')}
          {bp.version > 1 && <span style={{ color: '#10b981', marginLeft: '8px' }}>v{bp.version}</span>}
        </div>
        <div style={{ fontSize: '11px', color: '#64748b' }}>
          {(bp.size / 1024).toFixed(1)} KB • {new Date(bp.lastModified).toLocaleDateString()}
        </div>
      </div>
      
      {/* Refine Button */}
      <button
        onClick={(e) => {
          e.stopPropagation();
          setRefineBlueprintPath(bp.path);
          setShowRefineDialog(true);
        }}
        style={{
          background: 'rgba(16, 185, 129, 0.1)',
          border: '1px solid rgba(16, 185, 129, 0.3)',
          borderRadius: '8px',
          padding: '6px 10px',
          color: '#10b981',
          fontSize: '11px',
          fontWeight: 600,
          cursor: 'pointer',
          display: 'flex',
          alignItems: 'center',
          gap: '4px'
        }}
      >
        <RefreshCw size={14} />
        Refine
      </button>
    </div>
  </div>
))}
```

---

#### **Refine Dialog Component**

```tsx
// New component: RefineDialog.tsx
interface RefineDialogProps {
  blueprintPath: string;
  onClose: () => void;
  onRefine: (prompt: string, updateExisting: boolean) => void;
}

export const RefineDialog = ({ blueprintPath, onClose, onRefine }: RefineDialogProps) => {
  const [refinementPrompt, setRefinementPrompt] = useState('');
  const [updateExisting, setUpdateExisting] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleRefine = async () => {
    setLoading(true);
    try {
      const response = await fetch('http://localhost:8082/api/v1/explore/refine-blueprint', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          blueprintPath,
          refinementPrompt,
          project: 'piggymetrics',
          updateExisting
        })
      });
      
      const result = await response.json();
      
      if (result.status === 'success') {
        onRefine(refinementPrompt, updateExisting);
        onClose();
      }
    } catch (error) {
      console.error('Refinement failed:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        background: 'rgba(0,0,0,0.8)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 2000,
        padding: '40px'
      }}
      onClick={onClose}
    >
      <motion.div
        initial={{ scale: 0.9, y: 20 }}
        animate={{ scale: 1, y: 0 }}
        exit={{ scale: 0.9, y: 20 }}
        onClick={(e) => e.stopPropagation()}
        style={{
          background: '#111422',
          border: '1px solid #23273a',
          borderRadius: '24px',
          padding: '32px',
          maxWidth: '600px',
          width: '100%'
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
          <h2 style={{ margin: 0, color: '#fff', fontSize: '24px', fontWeight: 800 }}>
            🔍 Refine Blueprint
          </h2>
          <button onClick={onClose} style={{ background: 'transparent', border: 'none', color: '#475569', cursor: 'pointer' }}>
            <X size={24} />
          </button>
        </div>

        <div style={{ marginBottom: '24px' }}>
          <label style={{ display: 'block', color: '#94a3b8', fontSize: '14px', fontWeight: 600, marginBottom: '8px' }}>
            What additional details do you want to add?
          </label>
          <textarea
            value={refinementPrompt}
            onChange={(e) => setRefinementPrompt(e.target.value)}
            placeholder="e.g., Add payload schemas for external service calls"
            rows={4}
            style={{
              width: '100%',
              padding: '12px 16px',
              background: 'rgba(255,255,255,0.03)',
              border: '1px solid rgba(255,255,255,0.08)',
              borderRadius: '12px',
              color: '#fff',
              fontSize: '15px',
              outline: 'none',
              resize: 'vertical'
            }}
          />
        </div>

        <div style={{ marginBottom: '24px', padding: '16px', background: 'rgba(16, 185, 129, 0.05)', borderRadius: '12px', border: '1px solid rgba(16, 185, 129, 0.2)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
            <Info size={16} color="#10b981" />
            <span style={{ color: '#10b981', fontSize: '13px', fontWeight: 600 }}>Smart Refinement</span>
          </div>
          <p style={{ color: '#94a3b8', fontSize: '12px', margin: 0 }}>
            The system will automatically detect code changes since this blueprint was created and incorporate them into the refined version.
          </p>
        </div>

        <div style={{ marginBottom: '24px' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={updateExisting}
              onChange={(e) => setUpdateExisting(e.target.checked)}
              style={{ cursor: 'pointer' }}
            />
            <span style={{ color: '#94a3b8', fontSize: '14px' }}>
              Update existing blueprint (instead of creating new version)
            </span>
          </label>
        </div>

        <div style={{ display: 'flex', gap: '12px' }}>
          <button
            onClick={onClose}
            style={{
              flex: 1,
              padding: '14px',
              background: 'rgba(255,255,255,0.03)',
              border: '1px solid rgba(255,255,255,0.08)',
              borderRadius: '12px',
              color: '#94a3b8',
              fontSize: '15px',
              fontWeight: 700,
              cursor: 'pointer'
            }}
          >
            Cancel
          </button>
          <button
            onClick={handleRefine}
            disabled={!refinementPrompt.trim() || loading}
            style={{
              flex: 1,
              padding: '14px',
              background: refinementPrompt.trim() && !loading ? '#10b981' : 'rgba(255,255,255,0.03)',
              border: 'none',
              borderRadius: '12px',
              color: refinementPrompt.trim() && !loading ? '#fff' : '#475569',
              fontSize: '15px',
              fontWeight: 800,
              cursor: refinementPrompt.trim() && !loading ? 'pointer' : 'not-allowed',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px'
            }}
          >
            {loading ? (
              <>
                <Loader2 size={18} className="animate-spin" />
                Refining...
              </>
            ) : (
              <>
                <RefreshCw size={18} />
                Refine Blueprint
              </>
            )}
          </button>
        </div>
      </motion.div>
    </motion.div>
  );
};

---

## 📋 Implementation Checklist
**Status:** 🟢 In Progress  

### **Phase 1: Backend Foundation** (Day 1-2)
- [x] Create `blueprint_refinements` table in PostgreSQL
- [x] Implement `ChangeDetectionService`
  - [x] Query Qdrant for symbols by timestamp
  - [x] Compare current vs original symbols
  - [x] Generate change report
- [x] Implement `ContextRetrievalService`
  - [x] Fetch original context
  - [x] Fetch additional context based on keywords
  - [x] Merge and deduplicate symbols
- [x] Create `/refine-blueprint` endpoint
  - [x] Load existing blueprint from MinIO
  - [x] Detect changes
  - [x] Build refinement prompt
  - [x] Call LLM
  - [x] Save refined blueprint with metadata
- [x] Update `/list-blueprints` to include version info

### **Phase 2: Frontend UI** (Day 2-3)
- [x] Add "Refine" button to saved blueprints in sidebar
- [x] Create `RefineDialog` component
  - [x] Refinement prompt textarea
  - [x] Update existing vs create new version toggle
  - [x] Smart refinement info box
  - [x] Loading state
- [x] Add version badges to blueprint list (v1, v2, etc.)
- [ ] Implement refinement success notification
- [ ] Add "View History" to show all versions of a blueprint

### **Phase 3: Enhanced Features** (Day 3-4)
- [ ] Add "Compare Versions" feature
  - [ ] Side-by-side diff view
  - [ ] Highlight added/modified sections
- [ ] Implement "Rollback to Version" functionality
- [ ] Add "Auto-Refine" option
  - [ ] Automatically refine when code changes detected
  - [ ] Notify user of available updates
- [ ] Add refinement analytics
  - [ ] Track most common refinement prompts
  - [ ] Show token usage per refinement

### **Phase 4: Testing & Polish** (Day 4)
- [ ] Unit tests for `ChangeDetectionService`
- [ ] Integration tests for `/refine-blueprint` endpoint
- [ ] E2E tests for refinement flow
- [ ] Performance optimization
  - [ ] Cache change detection results
  - [ ] Optimize Qdrant queries
- [ ] Documentation
  - [ ] API documentation
  - [ ] User guide for refinement feature

---

## 🎯 Success Metrics

1. **User Adoption:**
   - 70%+ of users refine at least one blueprint within first week
   - Average 2-3 refinements per blueprint

2. **Efficiency:**
   - Refinement uses 40-60% fewer tokens than full regeneration
   - Change detection completes in <2 seconds

3. **Quality:**
   - 90%+ of refinements successfully merge new content
   - Zero data loss from original blueprints

4. **User Satisfaction:**
   - "Refine" feature rated 4.5+ stars
   - Reduced support tickets about "how to add more detail"

---

## 🚀 Future Enhancements

1. **AI-Suggested Refinements:**
   - System proactively suggests refinements based on code changes
   - "We detected 3 new methods. Would you like to add them to your blueprint?"

2. **Collaborative Refinement:**
   - Multiple users can refine the same blueprint
   - Track who made which changes

3. **Template-Based Refinements:**
   - Pre-built refinement templates
   - "Add Security Analysis", "Add Performance Metrics", etc.

4. **Export to Confluence/Notion:**
   - One-click export of refined blueprints
   - Maintain version history in external systems

---

## 📚 Related Documents

- [MinIO Blueprint Persistence](./minio-blueprint-persistence.md) - ✅ Complete
- [Semantic Explorer Architecture](../docs/architecture/semantic-explorer.md)
- [Vector Database Integration](../docs/architecture/qdrant-integration.md)

---

**Created:** 2026-01-12  
**Author:** Decode.AI Team  
**Last Updated:** 2026-01-12
