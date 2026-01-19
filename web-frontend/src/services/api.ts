import axios from 'axios';

const API_BASE = 'http://localhost:8082/api/v1';

// Configure axios for large file uploads
const axiosInstance = axios.create({
    timeout: 600000, // 10 minutes for large ZIP files
    maxContentLength: 500 * 1024 * 1024, // 500MB
    maxBodyLength: 500 * 1024 * 1024, // 500MB
});

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
    getProjects: () => axios.get(`${API_BASE}/explore/projects`),
    getProjectMetrics: (projectName: string) => axios.get<ProjectMetrics>(`${API_BASE}/explore/metrics?project=${encodeURIComponent(projectName)}`),
    getMappings: (projectName: string) => axios.get<LogicMapping[]>(`${API_BASE}/explore/mappings?project=${encodeURIComponent(projectName)}`),
    resolveAmbiguity: (mappingId: string, selectedSymbolId: string) =>
        axios.post(`${API_BASE}/explore/resolve-ambiguity`, { mappingId, selectedSymbolId }),
    ingestGit: (gitUrl: string, groupName?: string) => axios.post(`http://localhost:8083/api/v1/ingestion/git-clone?gitUrl=${gitUrl}${groupName ? `&groupName=${encodeURIComponent(groupName)}` : ''}`),
    // Use axiosInstance with extended timeout for large file uploads
    uploadZip: (formData: FormData) => axiosInstance.post(`http://localhost:8083/api/v1/ingestion/upload-zip`, formData, {
        headers: {
            'Content-Type': 'multipart/form-data',
        },
        onUploadProgress: (progressEvent) => {
            if (progressEvent.total) {
                const percentCompleted = Math.round((progressEvent.loaded * 100) / progressEvent.total);
                console.log(`Upload progress: ${percentCompleted}%`);
            }
        },
    }),
    getIngestionStatus: () => axios.get<Project[]>(`http://localhost:8083/api/v1/ingestion/status`),
    ingestionStreamUrl: 'http://localhost:8083/api/v1/ingestion/stream',
    getLineage: (projectName: string) => axios.get(`${API_BASE}/explore/lineage/semantic?projectName=${encodeURIComponent(projectName)}`),
    getAmbiguities: (projectName: string) => axios.get(`${API_BASE}/explore/ambiguities?project=${encodeURIComponent(projectName)}`),
    query: (query: string, domain: string = 'General') => axios.post<{ query: string, answer: string }>(`${API_BASE}/explore/query`, { query, domain }),
    getSnippet: (storageKey: string, startLine: number, endLine: number) =>
        axios.get<{ code: string }>(`${API_BASE}/explore/snippet`, { params: { storageKey, startLine, endLine } }),
};
