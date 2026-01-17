# Lexical Scout Caching - Implementation Summary

## ❓ Your Question
> "Does the scout run every time the user asks a question?"

## ✅ Answer: NO (Now Optimized with Caching)

### Before Caching (Wasteful)
```
User Query 1: "Generate BRD" → Scout runs (2-3s)
User Query 2: "Generate BRD" → Scout runs AGAIN (2-3s) ❌ WASTEFUL
User Query 3: "Analyze procurement" → Scout runs AGAIN (2-3s) ❌ WASTEFUL
```

### After Caching (Optimized)
```
User Query 1: "Generate BRD" → Scout runs (2-3s) → Cache result
User Query 2: "Generate BRD" → Use cache (< 1ms) ✅ FAST
User Query 3: "Analyze procurement" → Use cache (< 1ms) ✅ FAST
```

## 🔧 How Caching Works

### Cache Key
Domain identifier extracted from project context:
- Example: `"metasfresh"` (from "Domain: metasfresh" in context)
- Fallback: `"default"` if no domain found

### Cache Entry Structure
```java
CachedDomainMap {
    domainMap: DomainMap,      // The actual discovery results
    timestamp: 1736890800000,   // When it was cached
    vectorCount: 500            // Number of vectors analyzed
}
```

### Cache Invalidation
**TTL (Time To Live)**: 1 hour
- Cache age < 1 hour → Use cached result
- Cache age ≥ 1 hour → Re-discover and update cache

**Why 1 hour?**
- Domain vocabulary doesn't change frequently
- Re-ingestion would trigger new discovery anyway
- Balances freshness vs. performance

### Cache Storage
**Current**: In-memory `ConcurrentHashMap`
- Pros: Fast, simple
- Cons: Lost on service restart

**Production Recommendation**: Redis or Database
- Persistent across restarts
- Shared across multiple instances
- Can set TTL at storage level

## 📊 Performance Impact

### Single User Session (Multiple Queries)
```
Query 1: Scout runs (2.5s) + Analysis (45s) = 47.5s
Query 2: Scout cached (0.001s) + Analysis (45s) = 45s  ← 2.5s saved
Query 3: Scout cached (0.001s) + Analysis (45s) = 45s  ← 2.5s saved
Query 4: Scout cached (0.001s) + Analysis (45s) = 45s  ← 2.5s saved

Total time saved: 7.5s (15% reduction)
```

### Multi-User Environment (Same Project)
```
User A Query 1: Scout runs (2.5s) → Cache for "metasfresh"
User B Query 1: Scout cached (0.001s) ← Reuses User A's cache
User C Query 1: Scout cached (0.001s) ← Reuses cache
User D Query 1: Scout cached (0.001s) ← Reuses cache

Benefit: 3 users saved 7.5s total
```

## 🔄 Cache Lifecycle

### 1. First Query (Cache Miss)
```
User: "Generate BRD for metasfresh"
  ↓
Scout: Check cache for "metasfresh" → NOT FOUND
  ↓
Scout: Sample 500 vectors from Qdrant
  ↓
Scout: Analyze → Extract nouns → Cluster modules
  ↓
Scout: Generate domain map
  ↓
Scout: Cache result with timestamp
  ↓
Return: Domain map to Architect
```

### 2. Subsequent Queries (Cache Hit)
```
User: "Analyze procurement module"
  ↓
Scout: Check cache for "metasfresh" → FOUND
  ↓
Scout: Check age → 15 minutes old (< 1 hour) ✅
  ↓
Scout: Return cached domain map (no LLM calls, no vector search)
  ↓
Architect: Receives domain map instantly
```

### 3. Cache Expiration
```
User: "Generate BRD" (1.5 hours later)
  ↓
Scout: Check cache for "metasfresh" → FOUND
  ↓
Scout: Check age → 1.5 hours old (> 1 hour) ❌ EXPIRED
  ↓
Scout: Re-discover domain (fresh analysis)
  ↓
Scout: Update cache with new timestamp
  ↓
Return: Fresh domain map
```

### 4. Re-Ingestion Scenario
```
Admin: Re-ingests metasfresh project
  ↓
Qdrant: Vector count changes (44,229 → 52,000)
  ↓
User: "Generate BRD"
  ↓
Scout: Check cache → FOUND but vectorCount mismatch
  ↓
Scout: Re-discover to capture new code
  ↓
Scout: Update cache
```

## 🎯 Cache Effectiveness

### When Cache Helps Most
1. **Iterative Analysis**: User refining queries on same project
2. **Team Collaboration**: Multiple analysts working on same codebase
3. **Demo/Presentation**: Showing system to stakeholders repeatedly
4. **Development**: Testing and debugging the system

### When Cache Doesn't Help
1. **First-time project analysis**: No cache exists yet
2. **Multi-project environment**: Each project has its own cache entry
3. **After re-ingestion**: Cache invalidated by vector count change

## 🔮 Future Enhancements

### 1. Persistent Cache (Redis)
```java
@Autowired
private RedisTemplate<String, DomainMap> redisTemplate;

public DomainMap discoverDomain(String projectContext) {
    String domain = extractDomainIdentifier(projectContext);
    
    // Check Redis cache
    DomainMap cached = redisTemplate.opsForValue().get("domain:" + domain);
    if (cached != null) return cached;
    
    // Discover and cache with TTL
    DomainMap discovered = performDiscovery();
    redisTemplate.opsForValue().set("domain:" + domain, discovered, 1, TimeUnit.HOURS);
    
    return discovered;
}
```

### 2. Smart Invalidation
```java
// Invalidate cache when project is re-ingested
@EventListener
public void onProjectIngested(ProjectIngestedEvent event) {
    String domain = event.getDomain();
    domainCache.remove(domain);
    log.info("Invalidated domain cache for: {}", domain);
}
```

### 3. Warm-Up Cache
```java
// Pre-populate cache for known projects on startup
@PostConstruct
public void warmUpCache() {
    List<String> popularProjects = List.of("metasfresh", "decode-platform");
    popularProjects.forEach(domain -> {
        log.info("Warming up cache for: {}", domain);
        discoverDomain("Domain: " + domain, null);
    });
}
```

### 4. Cache Metrics
```java
@Scheduled(fixedRate = 60000) // Every minute
public void logCacheMetrics() {
    log.info("Domain cache stats: {} entries, {} hits, {} misses", 
        domainCache.size(), cacheHits.get(), cacheMisses.get());
}
```

## 📝 Summary

**Question**: Does Scout run every time?
**Answer**: No, it uses a 1-hour cache per project.

**Benefits**:
- ✅ 2-3 second savings per cached query
- ✅ Reduced vector store load
- ✅ Consistent domain vocabulary across queries
- ✅ Better user experience (faster responses)

**Trade-offs**:
- ⚠️ Slightly stale data (max 1 hour old)
- ⚠️ Memory usage (one cache entry per project)
- ⚠️ Lost on restart (in-memory implementation)

**Recommendation**: 
For production, migrate to Redis for persistent, distributed caching.
