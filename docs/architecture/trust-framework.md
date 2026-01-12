# Technical Specification: Deterministic Trust Framework for Legacy Logic Mapping
**Confidential Property of Shivam Apps LLC**

## 1. Abstract
This document defines a proprietary methodology for quantifying the accuracy of semantic linkages between disparate codebase layers (e.g., ASP Middleware to COBOL Mainframe). The framework utilizes a multi-weighted disambiguation engine to eliminate "Ghost Symbol" collisions and outputs an aggregate project "Trust Score" suitable for regulatory audit.

## 2. Granular Mapping Confidence Levels
Every mapping in the system is assigned a discrete confidence value based on the extraction methodology:

| Classification | Confidence Score | Extraction Logic |
| :--- | :--- | :--- |
| **P1: Deterministic (EXACT)** | **1.00** | 1:1 Identifier match with zero collision. |
| **P2: Weighted Disambiguation** | **0.80** | Resolved via Multi-Signal Affinity matching. |
| **P3: Semantic Heuristic** | **0.50 - 0.70** | Contextual alignment via LLM reasoning. |
| **P4: Ambiguous Collision** | **0.30** | Multiple candidates detected; requires Human-in-the-Loop (HITL). |

## 3. The Weighted Disambiguation Engine (Signal Logic)
In cases of "n-way" symbol collisions, the internal engine applies a weighted affinity analysis to each candidate:

### 3.1 Struct/Group Affinity (50%)
*   **Logic**: Calculates the semantic distance between the Business Tag and the Parent Data Structure (e.g., Level-01 Group in COBOL or `struct` name in C). 
*   **Vector Match**: If Tag `USR_AGE` maps to a variable inside `struct CUSTOMER_RECORD`, the affinity is high (~0.95).

### 3.2 Path/Module Affinity (30%)
*   **Logic**: Factors in the physical file location. Symbols inside `/ledger/` or `/auth/` modules carry higher weight for financial/security tags respectively.

### 3.3 Data Type Integrity (20%)
*   **Logic**: Validates physical memory format. A "Rate" tag expecting a `float` with 4-point precision receives an affinity boost if the candidate is a `PIC 9V9999` in COBOL.

## 4. Aggregate Trust Score (ATS) Formula
The overall project health is calculated as a weighted average of all active logic blueprints:

$$ATS = \frac{\sum_{i=1}^{n} (C_i \times W_i)}{n}$$

Where:
- $C_i$: Individual confidence score.
- $W_i$: Logic priority weight (e.g., Ledger paths carry 2.0x weight vs. Metadata paths at 0.5x).
- $n$: Total number of mapped requirements.

## 5. Defensive Mechanism: "HITL Boost"
The system utilizes a semi-supervised learning loop. When an architect manually resolves a P4 (0.30) ambiguity via the UI, the mapping is upgraded to a Verified State (1.00), triggering an immediate recalculation of the $ATS$. This provides a verifiable audit trail of human validation.

## 6. Claims to Innovation (Patent Readiness)
The Decode.AI Trust Framework represents several novel claims that distinguish it from standard Static Analysis or Generative AI tools:

*   **Claim 1: Deterministic Quantification of Semantic Uncertainty**: Standard LLM-based tools provide "guessed" outputs with no way to verify accuracy. This architecture converts semantic probability into a deterministic numerical "Confidence Vector," allowing for automated risk governance.
*   **Claim 2: Multi-Signal Affinity Reconciliation**: The specific three-factor weighting logic (Struct Affinity + Path Affinity + Type Integrity) used to resolve "Ghost Symbol" collisions is a novel method for navigating legacy data shadowing in C and COBOL monoliths.
*   **Claim 3: Cross-Layer Lineage Synthesis (ASP-to-Mainframe)**: The mechanical binding of high-level XML-based middleware configuration (ACLF) to physical memory offsets in a distinct legacy language via a unified Semantic Knowledge Map.
*   **Claim 4: Automated Governance via Confidence-Threshold Gating**: The system's ability to autonomously "Pause and Flag" low-confidence extractions, preventing the propagation of AI hallucinations into the business logic blueprint.
*   **Claim 5: Verifiable Knowledge Extraction (Non-Transpilation)**: Unlike tools that simply convert code, this architecture extracts the *intent* of the logic and links it to "Source Evidence," providing a mathematical guarantee of requirement retention during migration.

## 7. Legend for Intellectual Art (Figure 1)
To be used by patent counsel for the *Detailed Description of the Preferred Embodiment*:

### 7.1 Business Layer (Primary Input)
Represents the point of origin for business requirements, typically manifested as middleware configuration files (XML/ACLF) or modern API endpoints (ASP.NET Controllers).

### 7.2 Semantic Disambiguation Engine (Processor)
The core computational unit that reconciles the "Business Layer" intent with the "Legacy Layer" reality. It is an agentic processor that executes a multi-factor affinity algorithm to resolve symbolic uncertainty.

### 7.3 Weighted Signal Inputs (Affinity Variables)
These are the discrete variables fed into the Engine to determine the probability of a mapping's correctness:
*   **Struct Affinity (50%)**: The "Neighbor Presence" logic. Gauges correctness based on the semantic grouping of surrounding variables.
*   **Path Affinity (30%)**: The "Geographical Data Logic." Determines intent based on the physical subdirectory and module name.
*   **Type Integrity (20%)**: The "Physical Constraint" check. Ensures the technical data format (e.g., bit density, precision) supports the business requirement.

### 7.4 The Knowledge Sink (Audit Record)
The persistent state (PostgreSQL/MinIO) where the verified lineage and confidence scores are locked. This serves as the system of record for regulatory audit and the source of truth for the UI dashboard.

### 7.5 Confidence Score Gauge (Numerical Output)
The mathematical manifestation of Trust (Aggregate Trust Score). It represents the deterministic output of the Engine, allowing stakeholders to quantify migration risk.

