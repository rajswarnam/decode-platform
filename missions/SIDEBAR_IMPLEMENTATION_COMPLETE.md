# Evidence-Based BRD + Analysis Plan Sidebar - COMPLETE IMPLEMENTATION

## 🎯 Problem Solved

**Original Issues:**
1. ✅ BRD output contained "fluff" - hallucinated business context without code evidence
2. ✅ No visibility into the agentic process (Head Architect plan, Worker assignments, QA checks)
3. ✅ Missing file citations and evidence trails

## ✅ Solution Implemented

### Part 1: Evidence-Based Reporting (Backend)

#### Worker-Level Enforcement
- **Strict Citation Format**: Workers MUST cite files with this structure:
  ```
  ### Finding: [Title]
  **Evidence**: `filename.ext` (lines X-Y)
  ```code snippet```
  **Analysis**: [What it means]
  ```
- **Validation**: Reports without `**Evidence**:` markers are marked as `FAILED_VALIDATION`
- **No Assumptions**: Workers must state "NO EVIDENCE FOUND" if they can't find proof

#### Business Analyst Synthesis Rewrite
- **From**: "Infer stakeholders..." → **To**: "Extract from code: `InventoryService.java` exists → Inventory operations supported"
- **From**: Generic KPIs → **To**: "If code has `orderProcessingTime` metric → Track 'Order Processing Time'" (cite monitoring code)
- **New Section**: "📎 Code Evidence Appendix" - Lists all files cited in the BRD with their purpose

#### Expected BRD Output Structure
```markdown
## 1. Executive Summary
Based on analysis of 47 files across 8 modules, this system is an Event-Driven Retail ERP...
**Evidence**: OrderService.java, InventoryManager.java, PaymentGateway.java

## 2. Project Goals (EXTRACTED from Code Capabilities)
- **Goal**: Real-time inventory tracking
  **Code Evidence**: `InventoryService.java:45-67` - updateStockLevel() with DB triggers
  **Business Value**: Prevents stockouts by maintaining accurate counts

## 5. Business Risks (TRANSLATED from Technical Findings)
- **Technical Issue**: OrderService blocks on PaymentGateway
- **Code Location**: `OrderService.java:47`
- **Business Impact**: Payment gateway downtime halts all orders, losing $X/hour

## 📎 Code Evidence Appendix
- `OrderService.java` - Core order processing with payment integration
- `InventoryManager.java` - Real-time stock management with triggers
- `PaymentGateway.java` - External payment API (blocking calls detected)
```

### Part 2: Analysis Plan Sidebar (Frontend + Backend)

#### Backend API
**New Endpoint**: `GET /api/semantic/plans/{sessionId}`

**Response Structure**:
```json
{
  "sessionId": "uuid",
  "architectPlan": {
    "userQuery": "Generate BRD...",
    "identifiedAreas": ["order-processing", "inventory-mgmt"],
    "totalTasksPlanned": 5
  },
  "workerAssignments": [
    {
      "persona": "BACKEND_JAVA",
      "focusArea": "order-processing",
      "status": "COMPLETED",
      "iteration": 2,
      "evidenceFiles": ["OrderService.java", "PaymentGateway.java"],
      "validationErrors": null
    }
  ],
  "qaChecklist": {
    "checks": [
      {
        "iteration": 1,
        "checkType": "Schema Drift Detection",
        "status": "FAIL",
        "details": "❌ OrderService returns Integer, frontend expects Object"
      }
    ]
  },
  "evidenceSummary": {
    "totalFilesAnalyzed": 47,
    "evidenceQualityScore": 8.5
  }
}
```

#### Frontend Sidebar Component
**Location**: `/web-frontend/src/components/AnalysisPlanSidebar.tsx`

**Features**:
- ✅ Auto-opens when analysis starts (triggered by `SESSION_ID:` message)
- ✅ Real-time polling (updates every 2 seconds)
- ✅ Collapsible sections:
  - 📋 Head Architect's Strategy
  - 🔍 Worker Assignments (with file counts and status icons)
  - ✅ QA Validation Checklist
  - 📊 Evidence Map (files analyzed, quality score)
- ✅ Status indicators: ✅ (completed), 🔄 (running), ❌ (failed), ⏳ (pending)

**Visual Design**:
- Dark gradient background (#1a1a2e → #16213e)
- Glassmorphism effects
- Smooth slide-in animation
- Pulsing toggle button when collapsed

## 🚀 How It Works

### Flow Diagram
```
User asks BRD question
    ↓
AgentOrchestrator generates sessionId
    ↓
Frontend receives "SESSION_ID:xxx" via SSE
    ↓
Sidebar auto-opens and starts polling /api/semantic/plans/{sessionId}
    ↓
Workers execute with STRICT evidence requirements
    ↓
QA Agent validates (checks for ❌ schema drift, ⚠️ weak evidence)
    ↓
Architect refines tasks if needed
    ↓
Sidebar updates in real-time showing:
  - Which workers are running
  - What files they're analyzing
  - What QA found
    ↓
Final BRD generated with:
  - Every claim backed by file citations
  - Code Evidence Appendix
  - Evidence Quality Score
```

## 📁 Files Modified/Created

### Backend
- ✅ `AgentOrchestrator.java` - Added plan tracking, sessionId generation, evidence validation
- ✅ `AnalysisPlanDTO.java` - NEW: Data transfer object for plan API
- ✅ `AnalysisPlanController.java` - NEW: REST endpoint for plan retrieval
- ✅ `SemanticExplorerService.java` - Captures sessionId from orchestrator

### Frontend
- ✅ `AnalysisPlanSidebar.tsx` - NEW: Sidebar component
- ✅ `AnalysisPlanSidebar.css` - NEW: Sidebar styles
- ✅ `SemanticSearch.tsx` - Integrated sidebar, extracts sessionId from SSE

## 🧪 Testing Instructions

1. **Start all services** (already running):
   - Backend: `http://localhost:8082`
   - Frontend: `http://localhost:5173`

2. **Run BRD Query**:
   ```
   You are a Business Analyst expert leading a team of AI agents (Architect, Workers, QA).
   Generate a comprehensive Business Requirements Document (BRD) for this project.
   ```

3. **Observe**:
   - ✅ Sidebar auto-opens on right side
   - ✅ Shows "Head Architect's Strategy" with identified areas
   - ✅ Worker cards update in real-time with file counts
   - ✅ QA Checklist shows ✅/⚠️/❌ for each check
   - ✅ Evidence Map shows total files analyzed and quality score

4. **Verify BRD Output**:
   - ✅ Every business claim has a file citation
   - ✅ "📎 Code Evidence Appendix" section exists
   - ✅ No generic "inferred" content without code proof
   - ✅ Business risks include specific `filename:line` references

## 🎨 UI Preview

```
┌─────────────────────────────────────┐
│ 🧠 Analysis Plan              [×]  │
├─────────────────────────────────────┤
│ ▼ Head Architect's Strategy         │
│   Total Tasks: 5                    │
│   Focus Areas:                      │
│   • order-processing                │
│   • inventory-management            │
│                                     │
│ ▼ Worker Assignments                │
│   ┌─────────────────────────────┐  │
│   │ ✅ BACKEND_JAVA    Iter 2   │  │
│   │ Focus: order-processing     │  │
│   │ Files (3):                  │  │
│   │ • OrderService.java         │  │
│   │ • PaymentGateway.java       │  │
│   │ • OrderRepository.java      │  │
│   └─────────────────────────────┘  │
│                                     │
│ ▼ QA Validation Checklist           │
│   ✅ Iter 1: Evidence citations OK  │
│   ❌ Iter 1: Schema drift detected  │
│   ⚠️ Iter 2: Needs payment analysis │
│                                     │
│ ▼ Evidence Map                      │
│   ┌──────────┐  ┌──────────┐       │
│   │    47    │  │  8.5/10  │       │
│   │  Files   │  │ Quality  │       │
│   └──────────┘  └──────────┘       │
└─────────────────────────────────────┘
```

## 🔧 Technical Details

### Session Management
- Sessions stored in `ConcurrentHashMap` in `AgentOrchestrator`
- In production, should use Redis or database for persistence
- Current implementation: In-memory (lost on restart)

### Real-Time Updates
- Frontend polls every 2 seconds while sidebar is open
- Backend updates plan as workers complete tasks
- QA reports appended to list after each iteration

### Evidence Extraction
- Backend parses worker reports for `**Evidence**: \`filename\`` patterns
- Counts unique files per worker
- Calculates quality score: (workers with evidence / total workers) * 10

## 🎯 Success Metrics

**Before**:
- Generic business claims without proof
- No visibility into analysis process
- "Fluff" content like "Achieve 20% reduction..." without code backing

**After**:
- Every claim cites specific files and line numbers
- Full transparency: see what each agent analyzed
- Evidence-based: "InventoryService.java:45-67 contains updateStockLevel() → enables real-time tracking"

## 📝 Next Steps (Optional Enhancements)

1. **Evidence Heatmap**: Visual showing which files were analyzed most
2. **Module Grouping**: Group files by module in Evidence Summary
3. **Export Plan**: Download analysis plan as JSON
4. **Session History**: Store past sessions in database
5. **Evidence Quality Breakdown**: Show quality per worker, not just overall
