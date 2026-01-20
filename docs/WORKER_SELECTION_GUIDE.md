# Worker Selection Guide

## Query Analysis: "find all the variables related to EBKUCIPx Business Customer profile KYC inquiry for data mapping"

### Query Intent Classification

**Your Query**: "find all the variables related to EBKUCIPx Business Customer profile KYC inquiry for data mapping"

**Intent Analysis**:
- **Keywords**: "variables", "data mapping", "Customer profile", "KYC inquiry"
- **Type**: **Hybrid** (Technical + Business)
  - Technical aspect: Finding variables (code-level)
  - Business aspect: Data mapping for business entity (Customer profile, KYC)

**Expected Intent**: Should be classified as **BUSINESS** because:
- Primary goal: Data mapping (business concern)
- Focus: Business entity (Customer profile, KYC)
- Context: Business inquiry workflow

However, the word "variables" might cause it to be misclassified as TECHNICAL.

---

## Correct Worker Selection

### For This Query, The Ideal Workers Are:

| Worker | Why It's Appropriate | Focus Area |
|--------|----------------------|------------|
| **LOGIC_EXTRACTOR** | ✅ **PRIMARY** - Extracts variables and maps them to business terms. Perfect for "variables related to...for data mapping" | EBKUCIPx variables, Customer profile fields |
| **DATABASE_SQL** | ✅ **PRIMARY** - Finds database fields/columns related to Customer profile and KYC | Customer table, KYC table, profile fields |
| **BACKEND_JAVA** | ✅ **SECONDARY** - Finds variables in Java code (if backend is Java) | CustomerProfileService, KYCInquiryService |
| **FRONTEND_REACT** | ⚠️ **OPTIONAL** - Only if UI components have Customer profile/KYC variables | CustomerProfileForm, KYCInquiryComponent |

### Worker Personas Explained

#### 1. LOGIC_EXTRACTOR ✅ **BEST FIT**
**Description**: "Focus on extracting IF-ELSE rules, identifying HARDCODED VALUES (Magic Numbers), and **mapping variables to Business Terms**."

**Why Perfect for Your Query**:
- Your query asks for "variables related to...for data mapping"
- LOGIC_EXTRACTOR specializes in "mapping variables to Business Terms"
- Extracts business logic and variable-to-business-term mappings

**Expected Task**:
```
LOGIC_EXTRACTOR|ebkucipx-variables|Find all variables related to EBKUCIPx Business Customer profile KYC inquiry. Map each variable to its business meaning for data mapping.
```

#### 2. DATABASE_SQL ✅ **BEST FIT**
**Description**: "Focus on Schema definitions, Foreign Keys, Stored Procedures, and Data Models."

**Why Perfect for Your Query**:
- Customer profile and KYC data are typically stored in database tables
- Database fields are "variables" in the data model
- Essential for data mapping (source-to-target mapping)

**Expected Task**:
```
DATABASE_SQL|customer-profile-schema|What database tables and columns store Customer profile and KYC inquiry data? What are the relationships between these tables?
```

#### 3. BACKEND_JAVA ⚠️ **CONDITIONAL**
**Description**: "Focus on Spring Boot, JAX-RS, JPA Entities, and Controllers."

**Why Useful**:
- If backend is Java, variables are in Java classes
- Service classes, DTOs, entities contain Customer profile/KYC variables
- Useful for finding variable definitions

**Expected Task**:
```
BACKEND_JAVA|customer-profile-backend|What Java classes and variables handle Customer profile and KYC inquiry? What are the field names and their types?
```

#### 4. FRONTEND_REACT ⚠️ **OPTIONAL**
**Description**: "Focus on Redux Actions, React Components, Axios API clients, and State Management."

**Why Optional**:
- Only needed if UI components have Customer profile/KYC state variables
- Less critical for data mapping (usually focuses on backend/database)

---

## How to Verify Worker Selection

### Check the Logs

Look for these log messages in `context-orchestrator` logs:

```
🧠 Head Architect: Devising execution plan...
📋 Plan Created: X parallel work streams defined.
🚀 Dispatching [WORKER_NAME] (Initial Scan) to analyse: [FOCUS_AREA]
```

### Expected Log Output for Your Query

**If Correctly Classified as BUSINESS**:
```
🧠 Head Architect: Devising execution plan...
**BUSINESS QUERY DETECTED**: Focus EXCLUSIVELY on discovering business domains...
📋 Plan Created: 3-5 parallel work streams defined.
🚀 Dispatching Business Logic Extractor (Initial Scan) to analyse: ebkucipx-variables
🚀 Dispatching Database Architect (Initial Scan) to analyse: customer-profile-schema
🚀 Dispatching Java Backend Specialist (Initial Scan) to analyse: customer-profile-backend
```

**If Incorrectly Classified as TECHNICAL**:
```
🧠 Head Architect: Devising execution plan...
**TECHNICAL QUERY DETECTED**: Focus on implementation details...
📋 Plan Created: 3-5 parallel work streams defined.
🚀 Dispatching Java Backend Specialist (Initial Scan) to analyse: customer-profile-backend
🚀 Dispatching React Frontend Specialist (Initial Scan) to analyse: customer-profile-ui
🚀 Dispatching Database Architect (Initial Scan) to analyse: customer-profile-schema
```

---

## Common Issues & Solutions

### Issue 1: Query Misclassified as TECHNICAL

**Symptom**: You see BACKEND_JAVA, FRONTEND_REACT workers instead of LOGIC_EXTRACTOR

**Root Cause**: Query contains "variables" which sounds technical

**Solution**: Rephrase query to emphasize business aspect:
- ❌ "find all the variables related to..."
- ✅ "find all the data fields and business attributes related to EBKUCIPx Customer profile KYC inquiry for data mapping"

### Issue 2: Missing LOGIC_EXTRACTOR

**Symptom**: LOGIC_EXTRACTOR worker not in the plan

**Root Cause**: Query not classified as BUSINESS, or Architect didn't prioritize LOGIC_EXTRACTOR

**Solution**: 
- Ensure query is classified as BUSINESS (add "business", "data mapping", "overview" keywords)
- LOGIC_EXTRACTOR is prioritized for BUSINESS queries

### Issue 3: Too Many Technical Workers

**Symptom**: Multiple BACKEND_JAVA, FRONTEND_REACT workers for a data mapping query

**Root Cause**: Query classified as TECHNICAL

**Solution**: 
- Add business keywords: "business", "data mapping", "overview"
- Remove technical keywords: "variables" → "data fields", "code" → "business logic"

---

## Query Optimization Tips

### For Data Mapping Queries

**Best Practice**: Emphasize business context

**Examples**:

✅ **Good Query** (Will prioritize LOGIC_EXTRACTOR + DATABASE_SQL):
```
"Find all data fields and business attributes for EBKUCIPx Customer profile KYC inquiry for data mapping"
```

✅ **Good Query**:
```
"Map all variables and fields related to Customer profile KYC inquiry for business data mapping"
```

❌ **Less Optimal** (Might trigger TECHNICAL workers):
```
"Find all variables in the code related to EBKUCIPx"
```

### Keywords That Trigger BUSINESS Intent

- "business"
- "data mapping"
- "overview"
- "functional"
- "use case"
- "blueprint"
- "requirements"
- "BRD"

### Keywords That Trigger TECHNICAL Intent

- "bugs"
- "error"
- "log"
- "performance"
- "SRE"
- "refactor"
- "code"
- "implementation"

---

## Verification Checklist

After running your query, check:

- [ ] **Intent Classification**: Should be BUSINESS (check logs for "BUSINESS QUERY DETECTED")
- [ ] **LOGIC_EXTRACTOR Present**: Should see "Business Logic Extractor" in worker list
- [ ] **DATABASE_SQL Present**: Should see "Database Architect" in worker list
- [ ] **Focus Areas**: Should include "ebkucipx", "customer-profile", "kyc-inquiry"
- [ ] **Questions**: Should ask about data fields, business attributes, not technical implementation

---

## Example: Correct Worker Assignment

**Query**: "find all the variables related to EBKUCIPx Business Customer profile KYC inquiry for data mapping"

**Expected Workers**:

1. **LOGIC_EXTRACTOR** | `ebkucipx-variables` | "Find all variables related to EBKUCIPx Customer profile KYC inquiry. Map each variable to its business meaning for data mapping."

2. **DATABASE_SQL** | `customer-profile-schema` | "What database tables and columns store Customer profile and KYC inquiry data? What are the field names and their relationships?"

3. **BACKEND_JAVA** | `customer-profile-backend` | "What Java classes and fields handle Customer profile and KYC inquiry? What are the variable names and their types?"

4. **LOGIC_EXTRACTOR** | `kyc-inquiry-workflow` | "What business rules and data fields are involved in the KYC inquiry workflow?"

---

## If Workers Are Incorrect

### Option 1: Rephrase Query (Recommended)

Add business keywords:
```
"Find all data fields and business attributes for EBKUCIPx Business Customer profile KYC inquiry for data mapping and business overview"
```

### Option 2: Check Intent Detection

Look for this in logs:
```
🎯 Analysis Mode: BUSINESS (or TECHNICAL)
```

If it says TECHNICAL but should be BUSINESS, the query needs more business keywords.

### Option 3: Manual Override (Future Enhancement)

Currently, intent detection is automatic. Future enhancement could allow manual intent selection in UI.

---

## Summary

**For your query**: "find all the variables related to EBKUCIPx Business Customer profile KYC inquiry for data mapping"

**Correct Workers**:
1. ✅ **LOGIC_EXTRACTOR** (Primary - variable-to-business mapping)
2. ✅ **DATABASE_SQL** (Primary - database fields)
3. ✅ **BACKEND_JAVA** (Secondary - Java variables if backend is Java)
4. ⚠️ **FRONTEND_REACT** (Optional - only if UI has relevant state)

**If you see different workers**, the query might be misclassified. Check the logs for intent classification and consider rephrasing with more business keywords.
