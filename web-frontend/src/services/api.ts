import axios from 'axios';

const API_BASE = 'http://localhost:8082/api/v1';

export interface ProjectMetrics {
    totalTrustScore: number;
    ambiguityCount: number;
    mappedRulesCount: number;
    lastScoreRefresh: string;
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
    status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
    ingestionProgress: number;
    currentFile?: string;
    estimatedRemainingSeconds?: number;
    totalFiles?: number;
    processedFiles?: number;
    techStack: string[];
}

export const api = {
    getProjects: () => axios.get(`${API_BASE}/explore/projects`),
    getProjectMetrics: (projectName: string) => axios.get<ProjectMetrics>(`${API_BASE}/explore/projects/${projectName}/metrics`),
    getMappings: (projectName: string) => axios.get<LogicMapping[]>(`${API_BASE}/explore/blueprint/generate?projectName=${projectName}`),
    resolveAmbiguity: (mappingId: string, selectedSymbolId: string) =>
        axios.post(`${API_BASE}/explore/modern/stitch`, { mappingId, selectedSymbolId }), // Example endpoint
    ingestGit: (gitUrl: string) => axios.post(`http://localhost:8083/api/v1/ingestion/git-clone?gitUrl=${gitUrl}`),
    uploadZip: (formData: FormData) => axios.post(`http://localhost:8083/api/v1/ingestion/upload-zip`, formData),
    getIngestionStatus: () => axios.get<Project[]>(`http://localhost:8083/api/v1/ingestion/status`),
    getLineage: (projectName: string) => axios.get(`${API_BASE}/explore/lineage/semantic?projectName=${projectName}`),
    getAmbiguities: (projectName: string) => axios.get(`${API_BASE}/explore/projects/${projectName}/ambiguities`),
    query: (query: string, domain: string = 'General') => axios.post<{ query: string, answer: string }>(`${API_BASE}/explore/query`, { query, domain }),
};
