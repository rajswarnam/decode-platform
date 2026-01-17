# Evidence-Based BRD System - Implementation Summary

## Problem Statement
The AI was generating "fluff" in BRDs - hallucinating business context instead of extracting facts from actual code evidence.

## Root Causes
1. Workers weren't required to cite specific files
2. Business Analyst prompt asked to "infer" instead of "extract"
3. No evidence chain from Code → Technical Finding → Business Implication
4. UI didn't show the raw plans (Head Architect, QA, Worker instructions)

## Solution Implemented

### Phase 1: Strict Evidence Requirements (✅ DEPLOYED)

#### Worker-Level Changes
- **Mandatory Citation Format**: Workers must use structured evidence format:
  ```
  ### Finding: [Title]
  **Evidence**: `filename.ext` (lines X-Y)
  ```
  [code snippet]
  ```
  **Analysis**: [What it means]
  ```

- **Validation**: Reports without `**Evidence**:` markers are marked as `FAILED_VALIDATION`
- **No Assumptions**: Workers must explicitly state "NO EVIDENCE FOUND" if they can't find code proof

#### Business Analyst Synthesis Changes
- **From**: "Infer stakeholders if InventoryService exists"
- **To**: "Extract from code: `InventoryService.java` exists → Inventory operations are supported"

- **From**: Generic KPIs like "Order Processing Time"
- **To**: "If code has `orderProcessingTime` metric → Track 'Order Processing Time'" (cite the monitoring code)

- **New Section**: "📎 Code Evidence Appendix" - Lists all files cited in the BRD

### Phase 2: Plan Visibility (NEXT STEP)

#### Backend API (To Implement)
Create new endpoint: `GET /api/semantic/plans/{sessionId}`

Returns:
```json
{
  "architectPlan": {
    "rawPrompt": "...",
    "generatedTasks": [
      {"persona": "BACKEND_JAVA", "focus": "order-processing", "question": "..."}
    ]
  },
  "qaChecklist": {
    "iteration1": ["Check for blocking calls", "Verify schema consistency"],
    "iteration2": ["Validate evidence citations", "Cross-check findings"]
  },
  "workerReports": [
    {
      "persona": "BACKEND_JAVA",
      "status": "COMPLETED",
      "evidenceFiles": ["OrderService.java", "PaymentGateway.java"],
      "validationErrors": null
    }
  ]
}
```

#### Frontend Sidebar (To Implement)
Add collapsible sidebar in `SemanticSearch.tsx`:

```
┌─────────────────────────────────────┐
│ 📋 Analysis Plan                    │
├─────────────────────────────────────┤
│ ▼ Head Architect's Strategy         │
│   • Backend Java: Order processing  │
│   • Frontend React: User flows      │
│   • Database SQL: Schema analysis   │
│                                     │
│ ▼ Worker Assignments (Iteration 1)  │
│   ✅ Backend Java - COMPLETED       │
│      Files: OrderService.java (3)   │
│   ⚠️ Frontend React - WEAK EVIDENCE │
│      Files: App.tsx (1)             │
│                                     │
│ ▼ QA Validation Checklist           │
│   ✅ Evidence citations present     │
│   ❌ Schema drift detected          │
│   ⚠️ Needs deeper payment analysis  │
│                                     │
│ ▼ Evidence Map                      │
│   📊 15 files analyzed               │
│   🔍 Top modules:                    │
│   • order-service (5 files)         │
│   • payment-gateway (3 files)       │
└─────────────────────────────────────┘
```

## Expected Outcome

### Before (Fluff):
```
## 2. Project Goals
- Minimize Stockouts: Achieve 20% reduction...
  (No code evidence - just generic business speak)
```

### After (Evidence-Based):
```
## 2. Project Goals (EXTRACTED from Code Capabilities)
- **Goal**: Real-time inventory tracking
  **Code Evidence**: `InventoryService.java:45-67` - Contains `updateStockLevel()` method 
  with database triggers that fire on every stock change
  **Business Value**: Prevents stockouts by maintaining accurate counts in real-time

## 📎 Code Evidence Appendix
- `InventoryService.java` - Real-time stock level management with DB triggers
- `OrderService.java` - Order processing with payment gateway integration
- `PaymentGateway.java` - External payment API client (blocking calls detected)
```

## Testing Instructions

1. **Rebuild Complete**: `docker-compose up -d --build context-orchestrator`
2. **Run BRD Query**: "You are a Business Analyst... Generate a comprehensive BRD..."
3. **Verify Output**:
   - ✅ Every business claim has a file citation
   - ✅ Code Evidence Appendix is present
   - ✅ No generic "inferred" stakeholders without code proof
   - ✅ KPIs are derived from actual code instrumentation

## Next Steps

1. **Implement Plan API** (backend)
2. **Build Sidebar Component** (frontend)
3. **Add Evidence Quality Scoring** (count citations per section)
4. **Create "Evidence Heatmap"** (visual showing which files were analyzed most)
