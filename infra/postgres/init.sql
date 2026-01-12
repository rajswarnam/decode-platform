-- 1. EXTENSIONS (For search and uniqueness)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. WORKSPACE & PROJECTS
CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL UNIQUE,
    domain TEXT DEFAULT 'General', -- e.g., 'Banking', 'Mortgages', 'Credit Cards'
    description TEXT,
    base_path TEXT NOT NULL, -- Local path on VDI/Mac
    git_url TEXT,
    tech_stack TEXT[],       -- ['COBOL', 'Java', 'DB2']
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. SOURCE INVENTORY
CREATE TABLE IF NOT EXISTS source_files (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    extension TEXT NOT NULL, -- .cbl, .java, .c
    content_hash TEXT,       -- To detect changes
    last_indexed TIMESTAMP,
    file_summary TEXT,       -- High-level overview of the file (LLM)
    business_context TEXT,   -- LLM's interpretation of business value
    storage_key TEXT,        -- MinIO object key
    UNIQUE(project_id, file_path)
);

-- 4. SYMBOLS & AST METADATA (The "Code Map")
CREATE TABLE IF NOT EXISTS symbols (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id UUID REFERENCES source_files(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    category TEXT NOT NULL,  -- 'function', 'variable', 'paragraph', 'struct'
    data_type TEXT,          -- 'PIC X(10)', 'ArrayList<String>', 'uint32_t'
    
    -- Tree-sitter location data
    start_line INTEGER NOT NULL,
    start_column INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    end_column INTEGER NOT NULL,
    
    visibility TEXT,         -- 'public', 'private'
    is_definition BOOLEAN DEFAULT true,
    metadata JSONB,          -- For language-specific extra info
    analysis_status TEXT DEFAULT 'PENDING' -- PENDING, PROCESSING, COMPLETED, FAILED
);

-- Optimize JSONB searches for symbol attributes (e.g., annotations, copybooks)
CREATE INDEX IF NOT EXISTS idx_symbols_metadata_gin ON symbols USING GIN (metadata);

-- 5. THE GLOBAL DICTIONARY (The "Moat")
CREATE TABLE IF NOT EXISTS global_dictionary (
    id SERIAL PRIMARY KEY,
    technical_name TEXT NOT NULL, -- e.g., 'CUST-ID-99'
    business_name TEXT,          -- e.g., 'customerIdentifier'
    standard_label TEXT,         -- e.g., 'Customer ID'
    domain TEXT,                 -- e.g., 'Banking', 'Shipping'
    description TEXT,
    confidence_score FLOAT,      -- AI-generated confidence
    created_by_agent BOOLEAN DEFAULT true,
    UNIQUE(technical_name, domain)
);

-- 6. SEMANTIC DATA LINEAGE
CREATE TABLE IF NOT EXISTS data_lineage (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_symbol_id UUID REFERENCES symbols(id),
    target_symbol_id UUID REFERENCES symbols(id),
    flow_type TEXT NOT NULL,     -- 'assignment', 'api_call', 'mq_message'
    transformation_logic TEXT,   -- e.g., 'parsed as integer'
    project_id UUID REFERENCES projects(id)
);

-- 7. AGENT LOGS (Mission State Tracking)
CREATE TABLE IF NOT EXISTS agent_tasks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID REFERENCES projects(id),
    agent_name TEXT,             -- 'COBOL-Parser', 'Java-Mapper'
    status TEXT,                 -- 'running', 'completed', 'failed'
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    logs TEXT
);

-- 8. MISSING DEPENDENCIES (Gap Analysis)
CREATE TABLE IF NOT EXISTS missing_dependencies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,         -- File where the missing ref was found
    symbol_name TEXT NOT NULL,       -- logic.copybooks.CUST-REC
    dependency_type TEXT,            -- 'import', 'copybook', 'api_call'
    status TEXT DEFAULT 'detected',  -- 'detected', 'resolved', 'ignored'
    detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 9. FUSION ARGO: ACLF & MIGRATION MAPPINGS
CREATE TABLE IF NOT EXISTS aclf_mappings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    
    -- ACLF Metadata
    aclf_file_path TEXT NOT NULL,         -- e.g., 'config/RWIPOWN.ACLF'
    attribute_tag TEXT NOT NULL,          -- e.g., 'PRIMARY_OWNER' (The "Tag")
    attribute_length INTEGER,             -- e.g., 40 (The "Length")
    
    -- Mapping to C / MQ
    raw_data_path TEXT,                   -- e.g., 'FQDF.NWACOWNR.PRIMARY0'
    source_symbol_id UUID REFERENCES symbols(id), -- Pointer to the C struct definition
    
    -- MQ Specifics (Calculated by the C-Agent)
    mq_offset INTEGER,                    -- Starting byte in the MQ buffer
    mq_data_type TEXT,                    -- e.g., 'CHAR', 'COMP-3', 'BINARY'
    
    -- Migration Context
    zconnect_json_path TEXT,              -- Target path for migration (e.g., 'owner.primary')
    
    metadata JSONB,                       -- For extra flags like "Input", "Output", "Required"
    confidence_score FLOAT DEFAULT 1.0,   -- AI-generated confidence
    mapping_strategy TEXT DEFAULT 'EXACT', -- EXACT, SEMANTIC, AMBIGUOUS_MATCH, STITCHED_CONTRACT
    external_layer_ref TEXT,              -- Reference to modern layer (React/Spring)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, attribute_tag)
);

-- Index for fast lookup by Tag (used by the Global Dictionary)
CREATE INDEX IF NOT EXISTS idx_aclf_tag ON aclf_mappings(attribute_tag);

-- 9. BLUEPRINT REFINEMENTS (For Intelligent Blueprint Refinement System)
CREATE TABLE IF NOT EXISTS blueprint_refinements (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    blueprint_path VARCHAR(500) NOT NULL,
    parent_blueprint_path VARCHAR(500),
    project_name VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    refinement_prompt TEXT NOT NULL,
    code_changes_detected BOOLEAN DEFAULT FALSE,
    new_symbols_count INTEGER DEFAULT 0,
    modified_symbols_count INTEGER DEFAULT 0,
    deleted_symbols_count INTEGER DEFAULT 0,
    change_summary TEXT,
    tokens_used INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100) DEFAULT 'system'
);

-- Index for fast lookup by blueprint path
CREATE INDEX IF NOT EXISTS idx_blueprint_path ON blueprint_refinements(blueprint_path);
CREATE INDEX IF NOT EXISTS idx_parent_blueprint ON blueprint_refinements(parent_blueprint_path);
CREATE INDEX IF NOT EXISTS idx_project_refinements ON blueprint_refinements(project_name, created_at DESC);
