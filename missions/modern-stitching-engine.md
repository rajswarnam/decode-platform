# Mission: Modern Stitching Engine & Contract Mapping

## 1. Objective
Enable "Knowledge Extraction" for modern distributed stacks where source code is unavailable. Build the logic to bridge the gap between React frontends, Spring Boot extensions, and Vendor "Black Box" binaries using YAML contracts and Correlation IDs.

## 2. Core Functional Requirements

### A. Vendor YAML Schema Parser (`ContractMappingService`)
* **Logic**: Ingest Vendor YAML files that define NoSQL (Cassandra/Elastic) data structures and API behaviors.
* **Mapping**: Create "Virtual Symbols" in the `global_dictionary` that represent vendor-owned logic steps, using YAML tags as the anchor.

### B. The "Teleportation" Engine (`StitchingEngine`)
* **Logic**: Implement a correlation service that identifies 'Session Correlation IDs' across the 5-page transaction flow.
* **Trace Stitching**: Link the Outbound Egress (React/Spring request to Iframe) with the Inbound Ingress (Vendor callback to Spring API).
* **Gap Filling**: Automatically generate a "Synthetic Lineage" that connects the two ends of the "Black Box" to ensure the Knowledge Map remains unbroken.

### C. NoSQL Contextualizer
* **Inference**: Use Spring Boot Data Repository annotations to infer business rules for Cassandra/Elastic Search interactions.

## 3. Data Integrity & Trust
* **Confidence Scoring**: Assign a default `confidence_score` of **0.7** for stitched logic, flagging it for 'Contract-Based' verification in the UI.
* **Lineage Storage**: Save these synthetic traces in the `aclf_mappings` table with a `mapping_strategy` of `STITCHED_CONTRACT`.

## 4. Success Criteria
1. **Trace Continuity**: A full lineage graph is produced for a multi-page transaction that includes an external vendor callback.
2. **Knowledge Generation**: The system produces a "Plain English" rule for a vendor-owned step based purely on YAML definitions.
