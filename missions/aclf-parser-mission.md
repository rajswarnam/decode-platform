# Mission Briefing: ACLF Ingestion & Mapping Service

## 1. Objective
Build an automated parser to extract data attributes from Fusion Argo `.ACLF` configuration files and link them to C source code symbols. This is the foundation for the MQ-to-zConnect migration mapping.

## 2. Input Data
- **Primary Source**: `.ACLF` files (e.g., `RWIPOWN.ACLF`).
- **Format**: Hierarchical structured text/XML.
- **Key Fields to Extract**:
    - `Tag`: The business-facing attribute name (e.g., `PRIMARY_OWNER`).
    - `Data`: The C-code reference (e.g., `FQDF.NWACOWNR.PRIMARY0`).
    - `Length`: The fixed byte length of the field.

## 3. Execution Logic (The "Mission" Steps)
1. **Parser Implementation**: Use a regex or XML-based parser to iterate through every `ExternalXML.Detail` entry.
2. **Symbol Resolution**: Query the `symbols` table in Postgres for the `Data` path extracted.
3. **Offset Calculation**:
    - Locate the base C `struct` in the source code.
    - Calculate the **byte offset** from the start of the MQ buffer for each attribute based on the field ordering in the C struct.
4. **Persistence**: Upsert the results into the `aclf_mappings` table.

## 4. Edge Case Handling
- **Dangling Tags**: If a `Tag` exists in ACLF but the `Data` reference is missing in the C code, log a warning in the `agent_tasks` table.
- **Overlapping Offsets**: Detect if two ACLF tags point to overlapping memory offsets in the same C struct and flag for review.

## 5. Success Metric
- A successful mission results in a complete "Request/Response" map for the Fusion Argo MQ messages, enabling a developer to see UI-to-MQ-Offset lineage in a single view.
