# Project Discovery Design Analysis

## Current Behavior

The `ProjectDiscoveryService` walks through all directories in an uploaded ZIP/Git repo and registers **each directory** that contains detectable tech stack (build files like `pom.xml`, `package.json`, or source files like `.c`, `.aspx`, `.aclf`, etc.) as a **separate project**.

### Why This Happens

**Discovery Logic** (lines 69-76):
```java
List<String> detectedTech = detectTechStack(dir);
if (!detectedTech.isEmpty()) {
    projectsFound.incrementAndGet();
    registerProject(dir, detectedTech, gitUrl, rootPath, contextName, targetDomain);
    // Prevent monorepo clutter: once a project is found, don't register sub-modules
    // as separate projects
    return FileVisitResult.SKIP_SUBTREE;
}
```

**Detection Criteria**:
- Build files: `pom.xml`, `build.gradle`, `package.json`, `requirements.txt`, `CMakeLists.txt`, `Makefile`
- Source files: `.c`, `.cpp`, `.aspx`, `.aclf`, `.ts`, `.js`, `.cbl`, `.cob`

**Result**: If a folder like `Argo/Wrkflw_Mgmt/Group/X/Transaction` contains `.aclf` files, it's detected as a separate project, even though it's part of the larger "Fusion" monolith.

---

## Advantages of Current Design

### ✅ **1. Granular Analysis**
- **Independent Analysis**: Each module/component can be analyzed separately
- **Focused Queries**: Can ask questions about specific sub-modules (e.g., "Workflow Management Transaction logic")
- **Isolated Domains**: Each sub-project can have its own domain mapping

### ✅ **2. Monorepo Support**
- **Multiple Projects in One Repo**: Handles monorepos with multiple independent projects
- **Mixed Technologies**: Supports repos with Java, Node.js, Python projects side-by-side
- **Maven Multi-Module**: Correctly identifies Maven sub-modules as separate projects

### ✅ **3. Better Code Organization**
- **Smaller Scope**: Easier to search and analyze smaller codebases
- **Faster Parsing**: Parses only relevant files per "project"
- **Targeted Vectorization**: Only vectorizes symbols from the specific sub-module

### ✅ **4. Flexible Queries**
- **Module-Specific Queries**: "Find all Transaction classes in Wrkflw_Mgmt"
- **Cross-Module Analysis**: Still possible via domain grouping
- **Better Worker Assignment**: Workers can focus on specific modules

---

## Disadvantages of Current Design

### ❌ **1. Cluttered UI**
- **Too Many Projects**: Large monoliths create hundreds of "projects"
- **Hard to Navigate**: Difficult to see the big picture
- **Lost Context**: Hard to understand relationships between sub-modules

### ❌ **2. Fragmented Analysis**
- **Split Business Logic**: Business logic spanning multiple folders appears fragmented
- **Cross-Reference Issues**: Relationships between modules might be missed
- **Incomplete Views**: Analysis of one "project" doesn't show the full picture

### ❌ **3. Context Loss**
- **Monolith Structure Lost**: The fact that it's one cohesive application is obscured
- **Shared Components**: Shared code/utilities might be duplicated across projects
- **Harder Documentation**: Generating comprehensive docs becomes harder

### ❌ **4. Analysis Overhead**
- **Repeated Processing**: Similar patterns might be analyzed multiple times
- **Duplicate Symbol Extraction**: Shared utilities parsed multiple times
- **Dictionary Fragmentation**: Same business terms mapped differently across sub-projects

---

## Recommended Approach: Hybrid Design

### Option 1: **Single Unified Project** (Recommended for Monoliths)

**How it works**:
- Register only the root directory as one project
- All subdirectories become "modules" within that project
- Analysis considers the entire monolith together

**Implementation**:
```java
// Check if we're at root level
if (dir.equals(rootPath)) {
    // Register root as single project
    registerProject(dir, detectedTech, gitUrl, rootPath, contextName, targetDomain);
    // Continue exploring subdirectories (don't skip)
    return FileVisitResult.CONTINUE;
} else {
    // Skip subdirectories - they're modules, not projects
    if (!detectedTech.isEmpty()) {
        log.debug("Skipping sub-module: {} (part of parent project)", dir);
        return FileVisitResult.SKIP_SUBTREE;
    }
}
```

**Benefits**:
- ✅ Single "Fusion" project in UI
- ✅ Comprehensive analysis of entire monolith
- ✅ Better understanding of cross-module relationships
- ✅ Unified business domain mapping

**Trade-offs**:
- ⚠️ Larger parsing/vectorization scope
- ⚠️ Slower initial analysis

### Option 2: **Configurable Discovery Mode**

Add a configuration option:

```yaml
ingestion:
  project-discovery:
    mode: unified  # Options: "unified" (single project) or "granular" (sub-projects)
    # OR
    max-depth: 1  # Only detect projects at depth <= 1 (root level)
```

**Default**: `granular` (current behavior) for backward compatibility  
**For Monoliths**: Set to `unified` to treat as single project

### Option 3: **Smart Detection**

Automatically detect if it's a monolith:
- If root has tech stack AND subdirectories also have tech stack → Monolith (unified)
- If root is empty but subdirectories have tech stack → Multi-project (granular)
- If single directory has tech stack → Single project

---

## Recommendation

**For your use case (Fusion monolith)**: Use **Option 1** (Single Unified Project)

**Why**:
1. Fusion appears to be a cohesive application with interconnected modules
2. You want to see one "Fusion" project, not hundreds of sub-projects
3. Business logic likely spans multiple modules
4. Better for generating comprehensive documentation

**Implementation Plan**:
1. Modify `ProjectDiscoveryService.discoverAndRegisterProjects()` to register only root
2. Skip subdirectory project registration if parent is already a project
3. Add configuration flag `ingestion.project-discovery.unified-mode: true`
4. Update UI to show modules/folders within a project (if needed)

---

## Questions to Consider

1. **Do you need to query specific sub-modules separately?**
   - If YES → Keep granular approach
   - If NO → Unified approach is better

2. **Is Fusion a true monolith or a multi-project repo?**
   - Monolith → Unified
   - Multi-project → Granular

3. **Do sub-modules have independent build/deploy cycles?**
   - If NO → They're modules, not projects (unified)
   - If YES → They're separate projects (granular)
