# ZIP Upload Fixes - Summary of Changes

This document lists all changes made to fix the ZIP upload functionality. Use this to port the changes to your other workspace.

---

## Files Changed

1. `web-frontend/src/services/api.ts`
2. `web-frontend/vite.config.ts`
3. `web-frontend/src/components/IngestionPortal.tsx`
4. `ingestion-engine/src/main/java/com/decode/ingestion/engine/config/CorsConfig.java`
5. `ingestion-engine/src/main/java/com/decode/ingestion/engine/controller/IngestionController.java`
6. `ingestion-engine/src/main/resources/application.yaml`

---

## 1. Frontend API Service

**File**: `web-frontend/src/services/api.ts`

### Changes:

**Before:**
```typescript
const API_BASE = 'http://localhost:8082/api/v1';

export const api = {
    // ... other methods ...
    ingestGit: (gitUrl: string, groupName?: string) => 
        axios.post(`http://localhost:8083/api/v1/ingestion/git-clone?gitUrl=${gitUrl}...`),
    uploadZip: (formData: FormData) => 
        axios.post(`http://localhost:8083/api/v1/ingestion/upload-zip`, formData),
    getIngestionStatus: () => 
        axios.get<Project[]>(`http://localhost:8083/api/v1/ingestion/status`),
    ingestionStreamUrl: 'http://localhost:8083/api/v1/ingestion/stream',
    // ... other methods using API_BASE ...
};
```

**After:**
```typescript
// Use environment variables with fallback to localhost for development
const CONTEXT_API_BASE = import.meta.env.VITE_CONTEXT_API_URL || 'http://localhost:8082/api/v1';
const INGESTION_API_BASE = import.meta.env.VITE_INGESTION_API_URL || 'http://localhost:8083/api/v1';

export const api = {
    getProjects: () => axios.get(`${CONTEXT_API_BASE}/explore/projects`),
    getProjectMetrics: (projectName: string) => 
        axios.get<ProjectMetrics>(`${CONTEXT_API_BASE}/explore/metrics?project=${encodeURIComponent(projectName)}`),
    getMappings: (projectName: string) => 
        axios.get<LogicMapping[]>(`${CONTEXT_API_BASE}/explore/mappings?project=${encodeURIComponent(projectName)}`),
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
    getLineage: (projectName: string) => 
        axios.get(`${CONTEXT_API_BASE}/explore/lineage/semantic?projectName=${encodeURIComponent(projectName)}`),
    getAmbiguities: (projectName: string) => 
        axios.get(`${CONTEXT_API_BASE}/explore/ambiguities?project=${encodeURIComponent(projectName)}`),
    query: (query: string, domain: string = 'General') => 
        axios.post<{ query: string, answer: string }>(`${CONTEXT_API_BASE}/explore/query`, { query, domain }),
    getSnippet: (storageKey: string, startLine: number, endLine: number) =>
        axios.get<{ code: string }>(`${CONTEXT_API_BASE}/explore/snippet`, { params: { storageKey, startLine, endLine } }),
};
```

### Key Changes:
- ✅ Replaced hardcoded `API_BASE` with `CONTEXT_API_BASE` and `INGESTION_API_BASE`
- ✅ Added environment variable support (`VITE_CONTEXT_API_URL`, `VITE_INGESTION_API_URL`)
- ✅ Added URL encoding for `gitUrl` parameter
- ✅ Added timeout (5 minutes) and Content-Type header for `uploadZip`
- ✅ All API calls now use environment variables with localhost fallback

---

## 2. Vite Configuration

**File**: `web-frontend/vite.config.ts`

### Changes:

**Before:**
```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
})
```

**After:**
```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Proxy ingestion API requests to ingestion-engine
      '/api/v1/ingestion': {
        target: 'http://localhost:8083',
        changeOrigin: true,
        secure: false,
      },
      // Proxy context orchestrator API requests
      '/api/v1/explore': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
```

### Key Changes:
- ✅ Added proxy configuration for development
- ✅ Proxies `/api/v1/ingestion` → `http://localhost:8083`
- ✅ Proxies `/api/v1/explore` → `http://localhost:8082`
- ✅ Set explicit port 5173

---

## 3. Frontend Upload Handler

**File**: `web-frontend/src/components/IngestionPortal.tsx`

### Changes:

**Before:**
```typescript
const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files?.[0]) return;
    setStatus('loading');
    const formData = new FormData();
    formData.append('file', e.target.files[0]);
    if (groupName) formData.append('groupName', groupName);

    try {
        await api.uploadZip(formData);
        setStatus('success');
        setMessage('ZIP archive uploaded and projects registered.');
        setTimeout(() => setStatus('idle'), 5000);
    } catch (error) {
        setStatus('idle');
        setMessage('Upload failed.');
    }
};
```

**After:**
```typescript
const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files?.[0]) return;
    
    const file = e.target.files[0];
    
    // Validate file type
    if (!file.name.toLowerCase().endsWith('.zip')) {
        setStatus('error');
        setMessage('Please upload a ZIP file.');
        setTimeout(() => setStatus('idle'), 3000);
        return;
    }
    
    // Check file size (e.g., 500MB limit)
    const maxSize = 500 * 1024 * 1024; // 500MB
    if (file.size > maxSize) {
        setStatus('error');
        setMessage(`File size exceeds limit. Maximum size is 500MB.`);
        setTimeout(() => setStatus('idle'), 3000);
        return;
    }
    
    setStatus('loading');
    setMessage(`Uploading ${file.name}...`);
    
    const formData = new FormData();
    formData.append('file', file);
    if (groupName) formData.append('groupName', groupName);

    try {
        const response = await api.uploadZip(formData);
        setStatus('success');
        setMessage(response.data || 'ZIP archive uploaded and projects registered.');
        setTimeout(() => setStatus('idle'), 5000);
        
        // Clear file input
        e.target.value = '';
    } catch (error: any) {
        console.error('Upload error:', error);
        setStatus('error');
        
        // Better error messages
        if (error.response) {
            // Server responded with error
            const errorMsg = error.response.data || error.response.statusText;
            setMessage(`Upload failed: ${errorMsg} (Status: ${error.response.status})`);
        } else if (error.request) {
            // Request made but no response
            setMessage('Upload failed: No response from server. Please check if ingestion-engine is running.');
        } else {
            // Error in request setup
            setMessage(`Upload failed: ${error.message}`);
        }
        
        setTimeout(() => setStatus('idle'), 5000);
        
        // Clear file input
        e.target.value = '';
    }
};
```

### Key Changes:
- ✅ Added file type validation (ZIP only)
- ✅ Added file size validation (500MB limit)
- ✅ Added upload progress message
- ✅ Improved error handling with detailed messages
- ✅ Clear file input after upload (success or failure)
- ✅ Display server error messages and status codes
- ✅ Handle network errors (no response from server)

---

## 4. Backend CORS Configuration

**File**: `ingestion-engine/src/main/java/com/decode/ingestion/engine/config/CorsConfig.java`

### Changes:

**Before:**
```java
@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
            .allowedOrigins("http://localhost:5173", "http://localhost:4173") // Explicit frontend origins
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true);
}
```

**After:**
```java
@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
            .allowedOrigins(
                    "http://localhost:5173",  // Vite dev server
                    "http://localhost:4173",  // Vite preview
                    "http://localhost:3000",  // Production build
                    "http://localhost:8080",  // Docker/K8s
                    "http://web-frontend:3000", // Docker service name
                    "*"  // Allow all origins (adjust for production security)
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600); // Cache preflight requests for 1 hour
}
```

### Key Changes:
- ✅ Added more allowed origins (localhost:3000, localhost:8080, web-frontend:3000)
- ✅ Added `*` for all origins (adjust for production security)
- ✅ Added `PATCH` method
- ✅ Added `maxAge(3600)` to cache preflight requests

---

## 5. Backend Upload Endpoint

**File**: `ingestion-engine/src/main/java/com/decode/ingestion/engine/controller/IngestionController.java`

### Changes:

**Before:**
```java
@PostMapping("/upload-zip")
public ResponseEntity<String> uploadZip(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String groupName) throws IOException {
    log.info("📂 Received ZIP Upload: {}", file.getOriginalFilename());

    Path tempDir = Files.createTempDirectory("decode-upload-");
    try {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(tempDir.toFile(), entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }

        String originalFilename = file.getOriginalFilename();
        String displayProjectName = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : "Manual Upload";

        projectDiscoveryService.discoverAndRegisterProjects(tempDir.toString(), "manual-upload", displayProjectName, groupName);
        
        return ResponseEntity.ok("Zip archive processed and projects registered.");
        
    } finally {
        // CLEANUP ZIP EXTRACT
        log.info("Cleaning up temp zip directory: {}", tempDir);
        deleteDirectoryRecursively(tempDir);
    }
}
```

**After:**
```java
@PostMapping("/upload-zip")
public ResponseEntity<String> uploadZip(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String groupName) throws IOException {
    log.info("📂 Received ZIP Upload: {} (size: {} bytes)", file.getOriginalFilename(), file.getSize());

    // Validate file
    if (file.isEmpty()) {
        log.warn("Received empty ZIP file");
        return ResponseEntity.badRequest().body("ZIP file is empty");
    }

    // Validate file name
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
        log.warn("Invalid file type: {}", originalFilename);
        return ResponseEntity.badRequest().body("File must be a ZIP archive");
    }

    // Check file size (e.g., 500MB limit)
    long maxSize = 500 * 1024 * 1024; // 500MB
    if (file.getSize() > maxSize) {
        log.warn("ZIP file too large: {} bytes (max: {} bytes)", file.getSize(), maxSize);
        return ResponseEntity.badRequest().body("ZIP file exceeds maximum size of 500MB");
    }

    Path tempDir = Files.createTempDirectory("decode-upload-");
    try {
        int extractedFiles = 0;
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Security: Prevent zip slip vulnerability
                String entryName = entry.getName();
                Path resolvedPath = tempDir.resolve(entryName).normalize();
                
                if (!resolvedPath.startsWith(tempDir.normalize())) {
                    log.warn("Zip slip detected: {}", entryName);
                    throw new SecurityException("Invalid entry in ZIP file: " + entryName);
                }

                File newFile = resolvedPath.toFile();
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[8192]; // Increased buffer size
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        extractedFiles++;
                    }
                }
            }
        }

        log.info("Extracted {} files from ZIP archive to {}", extractedFiles, tempDir);

        String displayProjectName = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                : "Manual Upload";

        log.info("Starting project discovery for uploaded ZIP: {}", displayProjectName);
        projectDiscoveryService.discoverAndRegisterProjects(tempDir.toString(), "manual-upload", displayProjectName, groupName);
        
        log.info("✅ ZIP upload and project registration complete: {}", displayProjectName);
        return ResponseEntity.ok("Zip archive processed and projects registered. Extracted " + extractedFiles + " files.");
        
    } catch (SecurityException e) {
        log.error("Security error processing ZIP file", e);
        return ResponseEntity.status(400).body("Security error: " + e.getMessage());
    } catch (Exception e) {
        log.error("Error processing ZIP file: {}", file.getOriginalFilename(), e);
        return ResponseEntity.status(500).body("Error processing ZIP file: " + e.getMessage());
    } finally {
        // CLEANUP ZIP EXTRACT
        log.info("Cleaning up temp zip directory: {}", tempDir);
        deleteDirectoryRecursively(tempDir);
    }
}
```

### Key Changes:
- ✅ Added file validation (empty check, file type, size limit)
- ✅ Added **Zip Slip security protection** (prevents directory traversal attacks)
- ✅ Improved error handling with specific error responses
- ✅ Increased buffer size from 1024 to 8192 bytes (faster extraction)
- ✅ Added extracted file count tracking
- ✅ Better logging throughout the process
- ✅ More detailed error messages in responses

---

## 6. Backend Configuration

**File**: `ingestion-engine/src/main/resources/application.yaml`

### Changes:

**Before:**
```yaml
spring:
  main:
    web-application-type: servlet
  application:
    name: ingestion-engine
  datasource:
    url: jdbc:postgresql://postgres:5432/decode
    username: ${SPRING_DATASOURCE_USERNAME:decode_user}
    password: ${SPRING_DATASOURCE_PASSWORD:decode_password} 
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        jdbc:
          lob:
            non_contextual_creation: true
```

**After:**
```yaml
spring:
  main:
    web-application-type: servlet
  application:
    name: ingestion-engine
  datasource:
    url: jdbc:postgresql://postgres:5432/decode
    username: ${SPRING_DATASOURCE_USERNAME:decode_user}
    password: ${SPRING_DATASOURCE_PASSWORD:decode_password} 
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        jdbc:
          lob:
            non_contextual_creation: true
  servlet:
    multipart:
      enabled: true
      max-file-size: 500MB
      max-request-size: 500MB
      file-size-threshold: 10MB
server:
  max-http-header-size: 16KB
```

### Key Changes:
- ✅ Added multipart configuration for file uploads
- ✅ `max-file-size: 500MB` - allows large ZIP files
- ✅ `max-request-size: 500MB` - allows large request bodies
- ✅ `file-size-threshold: 10MB` - files over 10MB are written to disk
- ✅ `max-http-header-size: 16KB` - increased header size limit

---

## Environment Variables (Optional)

If you want to use environment variables in your other workspace, create a `.env` file in `web-frontend/`:

```bash
# .env (optional - for production/Docker)
VITE_CONTEXT_API_URL=http://context-orchestrator:8080/api/v1
VITE_INGESTION_API_URL=http://ingestion-engine:8080/api/v1
```

**Note:** In development with Vite dev server, the proxy in `vite.config.ts` handles routing, so these aren't needed locally.

---

## Summary of Key Fixes

1. **Environment Variable Support** - Frontend now uses `VITE_CONTEXT_API_URL` and `VITE_INGESTION_API_URL`
2. **Vite Proxy Configuration** - Added proxy for dev server to route API calls
3. **File Validation** - Frontend validates file type and size before upload
4. **Better Error Handling** - Detailed error messages in both frontend and backend
5. **Security Fix** - Added Zip Slip protection in backend
6. **Multipart Configuration** - Backend now accepts files up to 500MB
7. **CORS Updates** - Backend CORS supports Docker and production origins
8. **Improved Performance** - Larger buffer size for ZIP extraction

---

## Testing Checklist

After porting these changes:

- [ ] Test ZIP upload locally (file < 500MB)
- [ ] Test ZIP upload with large file (> 100MB)
- [ ] Test ZIP upload with invalid file type
- [ ] Test ZIP upload with file > 500MB (should fail gracefully)
- [ ] Test in Docker environment
- [ ] Test error handling (stop ingestion-engine, try upload)
- [ ] Check backend logs for detailed error messages
- [ ] Verify file input clears after upload
- [ ] Test CORS in Docker environment

---

## Notes

1. **Security Warning:** The CORS config currently allows `*` (all origins). For production, you should restrict this to specific domains.

2. **File Size Limit:** The 500MB limit is configured in both frontend and backend. Adjust if needed.

3. **Environment Variables:** The frontend will fall back to `localhost` if environment variables aren't set, so it should work locally without `.env` file.

4. **Vite Proxy:** The proxy only works in development (`npm run dev`). For production builds, use environment variables or a reverse proxy (nginx).

---

This completes all the changes needed to fix the ZIP upload functionality.
