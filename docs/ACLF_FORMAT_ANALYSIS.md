# ACLF Format Analysis

## Problem Identified

The current `AclfParserService` only handles **XML-format ACLF files** (files that start with `<`). However, ACLF files can exist in **two different formats**:

### Format 1: XML Format (Currently Supported)
```xml
<ExternalXML>
  <Detail>
    <Tag>AGE</Tag>
    <Data>FQDF.CUSTOMER.AGE</Data>
  </Detail>
</ExternalXML>
```

### Format 2: DSL Format (Currently NOT Supported) ⚠️
```aclf
ExternalDatalist A2AIMGO
{
    Type = ExternalFormat.DelimitedText;
    BlankFillSupport = false;
    FieldDelimiter = 0x0;
    Entries = {
        {
            Length = 32768;
            Data = FQDF.LOCAL.DYNBYNRY;
        }
    };
}

Datafield _ABANUM
{
    Type = DataType.Numeric;
    Length = 9;
    Precision = 0;
}

Transaction HSUSAL2
{
    Execute()
    {
        SetIndexVariable(Index: FQDF.SYSTEM.INDEX32, Value: 0);
        InitializeDatalist(Name: Datalist.HSUSRL2);
        ParseHostRecord(Target: ExternalDatalist.HSUSRL2, ...);
        // ... transaction logic
    }
}
```

## What We're Missing

### 1. **ExternalDatalist Definitions**
- **Purpose**: Define external data structures for integration
- **Business Value**: Maps business data structures to external systems
- **Example**: `A2AIMGO` defines how data is formatted for external communication
- **Missing Symbols**: 
  - Datalist names (e.g., `A2AIMGO`)
  - Field mappings (e.g., `FQDF.LOCAL.DYNBYNRY`)
  - Data types and formats

### 2. **Datafield Definitions**
- **Purpose**: Define data field structures and types
- **Business Value**: Core business data model definitions
- **Example**: `_ABANUM` defines a numeric field (9 digits, 0 precision)
- **Missing Symbols**:
  - Field names (e.g., `_ABANUM`)
  - Data types (e.g., `DataType.Numeric`)
  - Field properties (Length, Precision)

### 3. **Transaction Definitions**
- **Purpose**: Define business transaction logic
- **Business Value**: Core business workflows and processes
- **Example**: `HSUSAL2` processes `GetBranchByUser` service calls
- **Missing Symbols**:
  - Transaction names (e.g., `HSUSAL2`)
  - Method calls (e.g., `SetIndexVariable`, `InitializeDatalist`)
  - Business logic flow
  - Data transformations

### 4. **Business Logic Mapping**
- **Purpose**: Map ACLF transactions to C code implementations
- **Business Value**: Trace business requirements to code
- **Missing**: 
  - Transaction-to-C-function mappings
  - Data structure lineage (ACLF → C structs)
  - Business rule extraction

## Impact Assessment

### High Impact (Critical Business Logic)
1. **Transaction Definitions** - These contain the actual business workflows
2. **Datafield Definitions** - Core business data model
3. **ExternalDatalist Definitions** - Integration points with external systems

### Medium Impact (Supporting Logic)
1. **Method Calls** - Business operations (e.g., `SetIndexVariable`, `ParseHostRecord`)
2. **Data Transformations** - How data flows through the system

### Low Impact (Metadata)
1. **Comments** - Documentation (already captured if file is read)
2. **Configuration** - Settings (less critical for business understanding)

## Recommended Solution

### Option 1: Add DSL Parser (Recommended)
Create a new parser method that handles DSL-format ACLF files:

```java
private List<ParsedSymbol> parseDslFormat(String content, File file) {
    List<ParsedSymbol> symbols = new ArrayList<>();
    
    // Parse ExternalDatalist
    Pattern datalistPattern = Pattern.compile("ExternalDatalist\\s+(\\w+)");
    // Extract datalist name and fields
    
    // Parse Datafield
    Pattern datafieldPattern = Pattern.compile("Datafield\\s+(\\w+)\\s*\\{[^}]*Type\\s*=\\s*(\\w+)[^}]*Length\\s*=\\s*(\\d+)");
    // Extract field name, type, length
    
    // Parse Transaction
    Pattern transactionPattern = Pattern.compile("Transaction\\s+(\\w+)\\s*\\{");
    // Extract transaction name and methods
    
    return symbols;
}
```

### Option 2: Use LLM for DSL Parsing
Since DSL format is complex and variable, use LLM to extract:
- Transaction names and purposes
- Data structure definitions
- Business logic flow
- Data mappings

### Option 3: Hybrid Approach (Best)
1. **Simple Regex** for structured definitions (ExternalDatalist, Datafield)
2. **LLM** for complex transactions and business logic extraction
3. **Fallback** to XML parser if file starts with `<`

## Implementation Plan

### Phase 1: Detect Format
```java
if (trimmedContent.startsWith("<")) {
    // XML format - use existing parser
    return parseXmlFormat(content, file);
} else {
    // DSL format - use new parser
    return parseDslFormat(content, file);
}
```

### Phase 2: Parse DSL Format
1. Extract `ExternalDatalist` definitions
2. Extract `Datafield` definitions  
3. Extract `Transaction` definitions
4. Extract method calls and data flows

### Phase 3: Create Symbols
- Create `ParsedSymbol` for each:
  - Datalist name
  - Datafield name
  - Transaction name
  - Method calls (optional)

### Phase 4: Semantic Binding
- Map ACLF transactions to C functions
- Map ACLF datafields to C struct members
- Map ACLF datalists to C data structures

## Expected Benefits

1. **Complete Business Logic Coverage**: Parse all ACLF files, not just XML format
2. **Better Lineage**: Trace business requirements (ACLF transactions) to code (C functions)
3. **Data Model Understanding**: Understand business data structures (Datafields)
4. **Integration Points**: Identify external system interfaces (ExternalDatalists)
5. **Workflow Discovery**: Extract business workflows from Transaction definitions

## Current Status

- ✅ XML format ACLF files: **Supported**
- ❌ DSL format ACLF files: **NOT Supported** (skipped with error)
- ⚠️ **Missing**: ~50-70% of ACLF business logic (depending on project)

## Next Steps

1. Implement DSL format detection
2. Add DSL parser (regex + LLM hybrid)
3. Extract symbols from DSL format
4. Test with real ACLF files
5. Update documentation
