import axios from 'axios';

// Use environment variables with fallback to localhost for development
const CONTEXT_API_BASE = import.meta.env.VITE_CONTEXT_API_URL || 'http://localhost:8082/api/v1';
const INGESTION_API_BASE = import.meta.env.VITE_INGESTION_API_URL || 'http://localhost:8083/api/v1';

export interface ProjectMetrics {
    totalTrustScore: number;
    ambiguityCount: number;
    mappedRulesCount: number;
    lastScoreRefresh: string;
    isModern?: boolean;
}

export interface LogicMapping {
    id: string;
    attributeTag: string;
    aclfFilePath: string;
    confidenceScore: number;
    mappingStrategy: string;
    externalLayerRef: string;
    rawDataPath: string;
    zconnectJsonPath: string;
    businessDescription?: string;
    sourceSnippet?: string;
}

export interface Project {
    id: string;
    name: string;
    domain: string;
    status: 'PENDING' | 'PENDING_CLONE' | 'CLONING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
    ingestionProgress: number;
    currentFile?: string;
    estimatedRemainingSeconds?: number;
    totalFiles?: number;
    processedFiles?: number;
    techStack: string[];
}

export const api = {
    getProjects: () => axios.get(`${CONTEXT_API_BASE}/explore/projects`),
    getProjectMetrics: (projectName: string) => axios.get<ProjectMetrics>(`${CONTEXT_API_BASE}/explore/metrics?project=${encodeURIComponent(projectName)}`),
    getMappings: (projectName: string) => axios.get<LogicMapping[]>(`${CONTEXT_API_BASE}/explore/mappings?project=${encodeURIComponent(projectName)}`),
    resolveAmbiguity: (mappingId: string, selectedSymbolId: string) =>
        axios.post(`${CONTEXT_API_BASE}/explore/resolve-ambiguity`, { mappingId, selectedSymbolId }),
    ingestGit: (gitUrl: string, groupName?: string) => 
        axios.post(`${INGESTION_API_BASE}/ingestion/git-clone?gitUrl=${encodeURIComponent(gitUrl)}${groupName ? `&groupName=${encodeURIComponent(groupName)}` : ''}`),
    uploadZip: (formData: FormData) => 
        axios.post(`${INGESTION_API_BASE}/ingestion/upload-zip`, formData, {
            headers: {
                'Content-Type': 'multipart/form-data',
            },
            timeout: 300000, // 5 minutes for large zip files
        }),
    getIngestionStatus: () => axios.get<Project[]>(`${INGESTION_API_BASE}/ingestion/status`),
    ingestionStreamUrl: `${INGESTION_API_BASE}/ingestion/stream`,
    getLineage: (projectName: string) => axios.get(`${CONTEXT_API_BASE}/explore/lineage/semantic?projectName=${encodeURIComponent(projectName)}`),
    getAmbiguities: (projectName: string) => axios.get(`${CONTEXT_API_BASE}/explore/ambiguities?project=${encodeURIComponent(projectName)}`),
    query: (query: string, domain: string = 'General') => axios.post<{ query: string, answer: string }>(`${CONTEXT_API_BASE}/explore/query`, { query, domain }),
    getSnippet: (storageKey: string, startLine: number, endLine: number) =>
        axios.get<{ code: string }>(`${CONTEXT_API_BASE}/explore/snippet`, { params: { storageKey, startLine, endLine } }),
};
