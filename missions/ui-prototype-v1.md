# Mission: UI Prototype V1 - The Knowledge Management Portal

## 1. Objective
Build a React-based frontend for **Decode.AI** that transitions the platform from a technical parser to a **Computable Knowledge Map**. The UI must allow a non-technical stakeholder to understand legacy business logic in plain English while providing architects the tools to resolve semantic ambiguities.

## 2. Core Functional Requirements

### A. The "Plain English" Logic Card
*   **Knowledge Extraction**: Display a dedicated panel that shows the "Business Intent" of a code segment in natural language (sourced from `BlueprintService`).
*   **Evidence Linking**: Every plain-English rule must have a "Source Evidence" toggle to show the raw C/COBOL code retrieved from MinIO.
*   **Contextual Metadata**: Display the associated ASP Endpoint, ACLF Tag, and Physical C-Struct member for every logic unit.

### B. The Ambiguity Intelligence Dashboard
*   **Collision Highlighting**: Create a "Review Required" list for all mappings with a `confidence_score` < 0.8 (Strategy: `AMBIGUOUS_MATCH`).
*   **Conflict Resolver**: Implement a side-by-side comparison view where an architect can see multiple code candidates (e.g., the 4 'age' symbols) and select the correct one.
*   **Confidence Impact**: Selecting a candidate must trigger a `TrustScoreCalculator` refresh to update the project-level Trust Score.

### C. The Full-Stack Lineage Graph
*   **Trace Visualization**: Use a node-based graph (React Flow or D3) to show the path from `ExternalXML.Detail` tags to physical memory offsets in C/COBOL.
*   **Trust Metrics**: Render the `total_trust_score` from the `projects` table as a high-visibility doughnut chart in the "Trust Center".

## 3. Data Integration (Backend)
*   **API Consumer**: Connect to the `context-orchestrator` V1 API (specifically the new `/v1/explore/blueprint/generate` and lineage endpoints).
*   **Real-time Updates**: The UI must reflect changes to the `aclf_mappings` and `projects` tables following any score refreshes.

## 4. Success Criteria
1.  **Founder's Demo**: A user can click on a "Business Tag" and see a plain-English explanation of what that field does in the legacy core.
2.  **Zero-Crash Navigation**: The UI handles the `NonUniqueResultException` scenarios by displaying them as "Ambiguous" rather than failing.
3.  **Audit Ready**: Every logic extraction displays its confidence score and mapping strategy (EXACT vs. SEMANTIC).
