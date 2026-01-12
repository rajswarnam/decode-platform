---
trigger: always_on
---

Here is the Business Requirements Document (BRD) for Decode.AI. This document is designed to align your offshore team on the technical execution and your company leadership on the strategic ROI.

# Business Requirements Document: Decode.AI
**Project Title**: Agentic Knowledge Hub for Enterprise Modernization
**Version**: 1.1
**Status**: Draft for Stakeholder Review

## 1. Executive Summary
Decode.AI is an AI-powered reverse-engineering platform designed to transform "black box" legacy systems (C, COBOL, Mainframe) and complex modern polyglot microservices into a navigable, human-readable blueprint. By utilizing specialized Agentic MCP Servers, the platform automates the extraction of business rules and data lineage, reducing modernization discovery cycles by an estimated 300%.

## 2. Business Objectives & Success Metrics (SMART Goals)
The primary goal is to mitigate the risk of knowledge loss as senior developers retire and systems evolve.

| Metric Category | Business Objective | Success KPI (Target) |
| :--- | :--- | :--- |
| **Productivity** | Reduce manual code analysis time for offshore developers. | 14-34% increase in issue resolution speed. |
| **Risk** | Map 100% of hidden dependencies and "side effects" in legacy code. | Zero "blind-spot" outages during cloud migrations. |
| **Cost** | Automate documentation for compliance and audits. | 40% reduction in time spent on project prep and documentation. |

## 3. Project Scope
**In-Scope**
*   **Multi-Language Ingestion**: Automated scanning of C, COBOL, aspx, xml, yaml, html, css, Tibco, MQ, Salesforce, Java (Spring Boot), and React/Node.js.
*   **Semantic Mapping**: Building a "Global Dictionary" that links legacy variables to modern business terms.
*   **Hybrid Infrastructure**: Support for Native Postgres combined with Dockerized Qdrant/MinIO to balance VDI restrictions with modern tooling.

**Out-of-Scope**
*   Direct automated code refactoring (v1.0 is focused on Discovery & Documentation).
*   Processing of non-text-based legacy artifacts (e.g., scanned paper documents).

## 4. Functional Requirements
*   **4.1 Agentic Orchestration**: The system must use Model Context Protocol (MCP) to allow sub-agents to communicate across service boundaries.
*   **4.2 Data Lineage Visualization**: Generate end-to-end flowcharts from UI events to database state changes.
*   **4.3 Natural Language Query**: Enable non-technical stakeholders to ask "What happens when a refund is processed?" and receive a cited summary.

## 5. Non-Functional Requirements & Constraints
*   **5.1 Security**: All data processing must happen within the company firewall; no code shall be sent to external/public LLM endpoints.
*   **5.2 Performance**: The system must process up to 1 million lines of code in under 2 hours.
*   **5.3 VDI Compatibility (Hybrid)**: Java/Node services run natively; Database dependencies (except Postgres) run in Docker.
*   **5.4 Computational Determinism**:
    *   **ONNX Runtime**: Must be used for all embedding generation to ensure consistency.
    *   **LF Normalization**: All files must be converted to `LF` line endings before vectorization to prevent Windows/Linux drift.
*   **5.5 Resource & Token Management**:
    *   **5.5.1 Quota Awareness**: The system must operate within a 250,000 Tokens Per Minute (TPM) limit for LLM inference.
    *   **5.5.2 Tiered Analysis**: To optimize token usage, the system shall prioritize core business logic (COBOL/Java) over auxiliary files (Tests/UI/Documentation) when quota is constrained.
    *   **5.5.3 Cost-Efficiency**: Ingestion must use local static analysis (Tree-sitter) to minimize the volume of raw text sent to the LLM.
*   **5.6 Intelligence Integrity**:
    *   **No Heuristics**: Heuristic fallbacks for code analysis are strictly forbidden. The system must prioritize accuracy over uptime.
    *   **Failover Policy**: If the TPM limit is reached or the API is down, the system must **Pause and Retry**, never guess.
*   **5.7 LLM Integration Standard**:
    *   **Standardization**: All LLM communication must use a REST-based Gateway pattern to mirror enterprise security/audit protocols.
    *   **Separation**: Business logic agents must not have direct knowledge of LLM provider credentials.
    *   **Governance**: The system enforces a 250k TPM limit at the gateway level. All LLM interactions must be mediated by the local LLM Gateway to ensure 100% adherence.
*   **5.8 Data Ingestion Exclusions** (Fiscal Guardrail):
    *   **Efficiency**: To optimize token usage and storage, the system must exclude non-textual and compiled binary artifacts from the ingestion pipeline. No noise artifacts (e.g., .dat, .bin, .exe) shall be processed by the LLM.
    *   **Scope**: Only source code, configuration files (XML, ACLF, YAML), and documentation (Markdown) are permitted for embedding.
    *   **Binary Detection**: Use file signature (Magic Bytes) and MIME-type detection to verify files are plain-text before processing.
    *   **Size Limit**: Files larger than 1MB (except source code) are automatically excluded to protect TPM quota.
    *   **Exclusion Categories**:
        *   Compiled Binaries: `.class`, `.jar`, `.dll`, `.exe`, `.so`
        *   Media/Office: `.jpg`, `.png`, `.pdf`, `.doc`, `.xls`
        *   Raw Data: `.dat`, `.db`, `.sqlite`, `.dump`
        *   Build Directories: `target/`, `node_modules/`, `bin/`

## 5.9 Rule 14: Semantic Binding Requirement (ACLF)
*   The system MUST perform "Semantic Binding" between external configuration tags and physical memory offsets in legacy C/COBOL structures.

## 5.10 Rule 15: Conflict Resolution & Ambiguity Defense
*   **Mandatory Detection**: The system MUST identify and flag "Semantic Collisions" (e.g., multiple instances of the same variable name across different header files).
*   **High-Fidelity Logging**: Collisions must be logged as `AMBIGUOUS_MATCH` with a confidence penalty, requiring human validation rather than defaulting to an unsafe guess.

## 5.11 Rule 16: The Trust Mandate (New)
*   **Confidence Scoring**: The platform must assign a numerical Confidence Score to every business-to-code mapping. 
*   **Logic**: Full details are governed by the [Trust Framework Specification](../architecture/trust-framework.md).
*   **Threshold Enforcement**: Any mapping with a `confidence_score < 0.8` must be flagged for manual architect review in the UI.

## 5.12 Rule 17: Truth Verification & Traceability of Intent
*   **Auditability**: Every extracted "Plain English" logic summary must maintain a 1:1 link to its physical "Source Evidence" (raw C/COBOL snippets in MinIO).
*   **Requirement Lineage**: All logic units must trace back to their originating layer (ASP Endpoint or ACLF Tag) to ensure 100% auditability for regulatory compliance.

## 5.13 Rule 18: Contractual Continuity (New)
*   **Boundary Stitching**: The system MUST reconstruct business logic across 3rd-party boundaries (e.g., iFrames, Vendor Binaries, Webhooks) by correlating state hand-offs (Session/Correlation IDs).
*   **Black-Box Mapping**: Even when 3rd-party source code is unavailable, the system MUST use interface contracts (YAML, OpenAPI, DTOs) to maintain the integrity of the Knowledge Map.

## 6. Technology Scope
*   **Source Layers**: ASP, .NET Middleware, C-Core (Legacy ARGO), and COBOL (Mainframe Ledger).
*   **Infrastructure**: MinIO (Code Cache), Qdrant (Knowledge Vectorization), Postgres (Audit Trail/Knowledge Mapping).

## 6. Competitive Differentiation (The "Moat")
Unlike generic tools (e.g., Copilot), Decode.AI provides:
*   **Cross-Project Context**: Tracing logic between entirely different repositories (e.g., Mainframe to React).
*   **Proprietary Middleware Support**: Specialized agents for TIBCO and MQ that consumer AI lacks.