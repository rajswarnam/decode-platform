# UI Design Roadmap: Decode.AI Knowledge Management Portal
**Status**: Parked (Ready for Implementation)
**Related Specs**: [Trust Framework](./trust-framework.md), [BRD](../requirements/brd.md)

## 1. The 4-Screen Vision
This roadmap defines the transition from a technical code-parser to a stakeholder-facing Knowledge Platform.

### Screen A: The Project Command Center (Executive View)
*   **Hero Metric**: Aggregate Trust Score (ATS) Doughnut Chart.
*   **Module Status**: Progress of COBOL/C/Java ingestion.
*   **Risk Map**: Heatmap of modules with high Ambiguity Counts (> 5 collisions).

### Screen B: The Knowledge Explorer (Stakeholder View)
*   **Logic Rule Cards**: Human-readable summaries of business intent.
*   **Truth Toggle**: Expands to show raw source code evidence from MinIO.
*   **Lineage Breadcrumbs**: Trace from ASP endpoint to physical variable.

### Screen C: The Conflict Resolution Workspace (Architect View)
*   **Collision List**: Queue of `AMBIGUOUS_MATCH` mappings.
*   **Side-by-Side Comparison**: Candidate cards with Affinity Scores (Struct, Path, Type).
*   **Commit Knowledge**: Action to upgrade confidence to 1.0 (Verified).

### Screen D: The Semantic Trace Graph (Visual Connectivity)
*   **React Flow Graph**: Interactive nodes representing the "Full-Stack Bridge."
*   **Flow Animation**: Visual representation of data movement across layers.

## 2. Technical Stack (Design Principles)
*   **Frontend**: React / Next.js.
*   **Styling**: Modern dark-mode aesthetic with emerald 'Verified' highlights.
*   **Visuals**: React Flow for node graphs, Recharts for Trust gauages.

## 3. Resume Instructions
To restart this work, reference the `ui-prototype-v1.md` mission and the `patent_art_trust_framework.png` for visual style consistency.

Antigravity, resume Phase 4.2. Read docs/architecture/ui-design-roadmap.md and start the first mission in missions/ui-prototype-v1.md.