import { useState, useEffect } from 'react';
import { LayoutDashboard, Brain, ShieldAlert, GitBranch, Bell, User, Upload, FileText, Search, RefreshCw } from 'lucide-react';
import { TrustScoreGauge } from './components/TrustScoreGauge';
import { LogicCard } from './components/LogicCard';
import { AmbiguityResolver } from './components/AmbiguityResolver';
import { LineageGraph } from './components/LineageGraph';
import { IngestionPortal } from './components/IngestionPortal';
import { SemanticSearch } from './components/SemanticSearch';
import { RefineDialog } from './components/RefineDialog';
import { AnimatePresence } from 'framer-motion';
import { api } from './services/api';
import type { ProjectMetrics, LogicMapping, Project } from './services/api';

function App() {
  const [metrics, setMetrics] = useState<ProjectMetrics | null>(null);
  const [mappings, setMappings] = useState<LogicMapping[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProject, setSelectedProject] = useState<string>('');
  const [ambiguities, setAmbiguities] = useState<any[]>([]);
  const [activeTab, setActiveTab] = useState<'dashboard' | 'ingestion' | 'explorer'>('dashboard');
  const [blueprints, setBlueprints] = useState<any[]>([]);
  const [blueprintFilter, setBlueprintFilter] = useState('');
  const [selectedBlueprint, setSelectedBlueprint] = useState<string | null>(null);

  // Refine Blueprint State
  const [showRefineDialog, setShowRefineDialog] = useState(false);
  const [refineBlueprintPath, setRefineBlueprintPath] = useState<string | null>(null);

  const refreshBlueprints = () => {
    if (selectedProject) { // Changed activeProject to selectedProject based on context
      fetch(`http://localhost:8082/api/v1/explore/list-blueprints?project=${selectedProject}`)
        .then(res => res.json())
        .then(data => setBlueprints(data || []))
        .catch(err => console.error('Failed to fetch blueprints:', err));
    }
  };

  useEffect(() => {
    // Fetch project list
    api.getProjects().then(res => {
      setProjects(res.data);
      if (res.data.length > 0) {
        setSelectedProject(res.data[0].name);
      }
    }).catch(err => console.error("Failed to fetch projects", err));
  }, []);

  useEffect(() => {
    if (!selectedProject) return;

    // Fetch project-specific metrics
    api.getProjectMetrics(selectedProject).then(res => {
      setMetrics(res.data);
    }).catch(err => {
      console.error("Failed to fetch metrics", err);
    });

    // Fetch mappings
    api.getMappings(selectedProject).then(res => {
      if (Array.isArray(res.data)) {
        setMappings(res.data);
      } else {
        setMappings([]);
      }
    }).catch(err => {
      console.error("Failed to fetch mappings", err);
      setMappings([]);
    });

    // Fetch active ambiguities
    api.getAmbiguities(selectedProject).then(res => {
      setAmbiguities(res.data);
    }).catch(err => console.error("Failed to fetch ambiguities", err));

  }, [selectedProject]);

  useEffect(() => {
    // Load blueprints when entering Semantic Explorer
    if (activeTab === 'explorer' && selectedProject) {
      fetch(`http://localhost:8082/api/v1/explore/list-blueprints?project=${selectedProject}`)
        .then(res => res.json())
        .then(data => setBlueprints(data))
        .catch(err => console.error('Failed to load blueprints:', err));
    }
  }, [activeTab, selectedProject]);

  const handleResolveAmbiguity = async (mappingId: string, selectedSymbolId: string) => {
    try {
      await api.resolveAmbiguity(mappingId, selectedSymbolId);

      // Remove from local state immediately for UI responsiveness
      setAmbiguities(prev => prev.filter(a => a.id !== mappingId));

      // Refresh metrics to show score increase
      if (selectedProject) {
        const metricsRes = await api.getProjectMetrics(selectedProject);
        setMetrics(metricsRes.data);

        // Refresh mappings to show the new VERIFIED card
        const mappingsRes = await api.getMappings(selectedProject);
        setMappings(mappingsRes.data);
      }
    } catch (error) {
      console.error("Failed to resolve ambiguity", error);
      alert("Failed to verify mapping. Please try again.");
    }
  };

  return (
    <div className="dashboard-grid" style={{
      gridTemplateColumns: activeTab === 'explorer' ? '280px 1fr' : '320px 1fr 380px',
      gap: activeTab === 'explorer' ? '0' : '24px',
      padding: activeTab === 'explorer' ? '0' : '24px'
    }}>
      {/* Sidebar */}
      <aside className="sidebar" style={{
        padding: activeTab === 'explorer' ? '24px' : '0',
        borderRight: activeTab === 'explorer' ? '1px solid rgba(255,255,255,0.05)' : 'none',
        background: activeTab === 'explorer' ? '#070912' : 'transparent'
      }}>
        <div style={{ padding: '0 12px', marginBottom: '24px', display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{ background: 'var(--primary)', padding: '8px', borderRadius: '12px' }}>
            <Brain size={24} color="white" />
          </div>
          <h1 style={{ fontSize: '20px', fontWeight: 700 }}>Decode.AI</h1>
        </div>

        <nav style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          <div
            className="glass-panel"
            onClick={() => setActiveTab('dashboard')}
            style={{
              padding: '12px',
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              background: activeTab === 'dashboard' ? 'rgba(16,185,129,0.1)' : 'transparent',
              borderColor: activeTab === 'dashboard' ? 'var(--primary)' : 'transparent',
              cursor: 'pointer'
            }}
          >
            <LayoutDashboard size={18} color={activeTab === 'dashboard' ? 'var(--primary)' : '#94a3b8'} />
            <span style={{ fontSize: '14px', fontWeight: 600, color: activeTab === 'dashboard' ? '#f8fafc' : '#94a3b8' }}>Command Center</span>
          </div>

          <div
            className="glass-panel"
            onClick={() => setActiveTab('ingestion')}
            style={{
              padding: '12px',
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              background: activeTab === 'ingestion' ? 'rgba(16,185,129,0.1)' : 'transparent',
              borderColor: activeTab === 'ingestion' ? 'var(--primary)' : 'transparent',
              cursor: 'pointer'
            }}
          >
            <Upload size={18} color={activeTab === 'ingestion' ? 'var(--primary)' : '#94a3b8'} />
            <span style={{ fontSize: '14px', fontWeight: 600, color: activeTab === 'ingestion' ? '#f8fafc' : '#94a3b8' }}>Project Ingestion</span>
          </div>

          <div
            className="glass-panel"
            onClick={() => setActiveTab('explorer')}
            style={{
              padding: '12px',
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              background: activeTab === 'explorer' ? 'rgba(16,185,129,0.1)' : 'transparent',
              borderColor: activeTab === 'explorer' ? 'var(--primary)' : 'transparent',
              cursor: 'pointer'
            }}
          >
            <GitBranch size={18} color={activeTab === 'explorer' ? 'var(--primary)' : '#94a3b8'} />
            <span style={{ fontSize: '14px', fontWeight: 600, color: activeTab === 'explorer' ? '#f8fafc' : '#94a3b8' }}>Semantic Explorer</span>
          </div>
          <div style={{ padding: '12px', display: 'flex', alignItems: 'center', gap: '12px', color: '#94a3b8' }}>
            <ShieldAlert size={18} />
            <span style={{ fontSize: '14px' }}>Security Audit</span>
          </div>
        </nav>

        {/* Saved Blueprints Section - Only visible in Semantic Explorer */}
        {activeTab === 'explorer' && (
          <div style={{ marginTop: '24px', flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
            <div style={{ fontSize: '12px', color: '#64748b', marginBottom: '12px', fontWeight: 700, letterSpacing: '0.05em' }}>
              📚 SAVED BLUEPRINTS
            </div>

            {/* Search Filter */}
            <div style={{ position: 'relative', marginBottom: '16px' }}>
              <Search size={16} color="#64748b" style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)' }} />
              <input
                type="text"
                value={blueprintFilter}
                onChange={(e) => setBlueprintFilter(e.target.value)}
                placeholder="Search blueprints..."
                style={{
                  width: '100%',
                  padding: '10px 12px 10px 36px',
                  background: 'rgba(255,255,255,0.03)',
                  border: '1px solid rgba(255,255,255,0.08)',
                  borderRadius: '10px',
                  color: '#fff',
                  fontSize: '13px',
                  outline: 'none'
                }}
              />
            </div>

            {/* Blueprints List */}
            <div style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {blueprints.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '32px 16px', color: '#64748b', fontSize: '13px' }}>
                  <FileText size={32} color="#334155" style={{ margin: '0 auto 12px' }} />
                  <p>No blueprints saved yet</p>
                </div>
              ) : (
                blueprints
                  .filter(bp =>
                    blueprintFilter === '' ||
                    bp.filename.toLowerCase().includes(blueprintFilter.toLowerCase())
                  )
                  .map((bp: any, idx: number) => (
                    <div
                      key={idx}
                      onClick={() => setSelectedBlueprint(bp.path)}
                      className="group" // Add group class for hover effects if Tailwind enabled, otherwise inline styles work
                      style={{
                        padding: '12px',
                        background: selectedBlueprint === bp.path ? 'rgba(16, 185, 129, 0.1)' : 'rgba(255,255,255,0.02)',
                        border: `1px solid ${selectedBlueprint === bp.path ? 'rgba(16, 185, 129, 0.3)' : 'rgba(255,255,255,0.05)'}`,
                        borderRadius: '10px',
                        cursor: 'pointer',
                        transition: 'all 0.2s',
                        position: 'relative'
                      }}
                      onMouseEnter={(e) => {
                        if (selectedBlueprint !== bp.path) e.currentTarget.style.background = 'rgba(255,255,255,0.05)';
                      }}
                      onMouseLeave={(e) => {
                        if (selectedBlueprint !== bp.path) e.currentTarget.style.background = 'rgba(255,255,255,0.02)';
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <div style={{ fontSize: '13px', fontWeight: 600, color: '#fff', marginBottom: '4px', fontFamily: "'JetBrains Mono', monospace", lineHeight: '1.4', flex: 1 }}>
                          {bp.filename.replace(/_\d{8}_\d{6}\.md$/, '').replace(/-/g, ' ')}
                          {bp.version && parseInt(bp.version) > 1 && (
                            <span style={{
                              display: 'inline-block',
                              background: 'rgba(16, 185, 129, 0.2)',
                              color: '#10b981',
                              fontSize: '10px',
                              padding: '2px 6px',
                              borderRadius: '4px',
                              marginLeft: '8px',
                              verticalAlign: 'middle'
                            }}>v{bp.version}</span>
                          )}
                        </div>
                      </div>

                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '4px' }}>
                        <div style={{ fontSize: '11px', color: '#64748b' }}>
                          {(bp.size / 1024).toFixed(1)} KB • {new Date(bp.lastModified).toLocaleDateString()}
                        </div>

                        {/* Refine Button */}
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setRefineBlueprintPath(bp.path);
                            setShowRefineDialog(true);
                          }}
                          style={{
                            background: 'rgba(16, 185, 129, 0.1)',
                            border: '1px solid rgba(16, 185, 129, 0.2)',
                            borderRadius: '6px',
                            padding: '4px 8px',
                            color: '#10b981',
                            fontSize: '10px',
                            fontWeight: 600,
                            cursor: 'pointer',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px'
                          }}
                        >
                          <RefreshCw size={10} />
                          Refine
                        </button>
                      </div>
                    </div>
                  ))
              )}
            </div>
          </div>
        )}

        <div className="glass-panel" style={{ marginTop: 'auto', padding: '16px' }}>
          <div style={{ fontSize: '12px', color: '#64748b', marginBottom: '12px' }}>PROJECT HEALTH</div>
          <TrustScoreGauge score={metrics?.totalTrustScore || 0} />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginTop: '16px' }}>
            <div style={{ textAlign: 'center' }}>
              <div style={{ fontSize: '16px', fontWeight: 700 }}>{metrics?.mappedRulesCount}</div>
              <div style={{ fontSize: '9px', color: '#64748b' }}>RULES</div>
            </div>
            <div style={{ textAlign: 'center' }}>
              <div style={{ fontSize: '16px', fontWeight: 700, color: '#f59e0b' }}>{metrics?.ambiguityCount}</div>
              <div style={{ fontSize: '9px', color: '#64748b' }}>AMBIGUITIES</div>
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content */}
      <main className="main-content" style={{ padding: activeTab === 'explorer' ? '0' : '0' }}>
        <header style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '8px',
          padding: activeTab === 'explorer' ? '24px 40px' : '0',
          borderBottom: activeTab === 'explorer' ? '1px solid rgba(255,255,255,0.05)' : 'none',
          background: activeTab === 'explorer' ? '#0a0b10' : 'transparent'
        }}>
          <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
            <div className="glass-panel" style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '8px 16px', borderRadius: '12px' }}>
              <GitBranch size={16} color="var(--primary)" />
              <select
                value={selectedProject}
                onChange={(e) => setSelectedProject(e.target.value)}
                style={{
                  background: 'transparent',
                  border: 'none',
                  color: 'white',
                  fontSize: '14px',
                  fontWeight: 600,
                  outline: 'none',
                  cursor: 'pointer'
                }}
              >
                <option value="" disabled>Select Project Context</option>
                {projects.map(p => (
                  <option key={p.id} value={p.name} style={{ background: '#0f172a' }}>{p.name}</option>
                ))}
              </select>
            </div>
            {selectedProject && (
              <div style={{ fontSize: '11px', color: '#64748b' }}>
                Active Environment: <span style={{ color: 'var(--primary)', fontWeight: 600 }}>{selectedProject}</span>
              </div>
            )}
          </div>
          <div style={{ display: 'flex', gap: '16px', alignItems: 'center' }}>
            <Bell size={20} color="#94a3b8" />
            <div className="glass-panel" style={{ padding: '6px 12px', borderRadius: '12px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <div style={{ width: '24px', height: '24px', background: '#3b82f6', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <User size={14} color="white" />
              </div>
              <span style={{ fontSize: '13px' }}>Architect</span>
            </div>
          </div>
        </header>

        {activeTab === 'dashboard' ? (
          <>
            <section style={{ padding: '0 24px' }}>
              <h2 style={{ fontSize: '18px', fontWeight: 600, marginBottom: '16px' }}>Knowledge Explorer</h2>
              {mappings.length > 0 ? (
                mappings.map((m: LogicMapping) => (
                  <LogicCard key={m.id} mapping={m} />
                ))
              ) : (
                <div style={{ padding: '40px', textAlign: 'center', color: '#64748b' }} className="glass-panel">
                  No semantic mappings identified yet for {selectedProject}.
                </div>
              )}
            </section>

            <section style={{ padding: '0 24px' }}>
              <LineageGraph projectName={selectedProject} />
            </section>
          </>
        ) : activeTab === 'ingestion' ? (
          <section style={{ padding: '0 24px' }}>
            <IngestionPortal />
          </section>
        ) : (
          <section style={{ height: 'calc(100vh - 84px)', overflowY: 'auto' }}>
            <SemanticSearch selectedBlueprintPath={selectedBlueprint} />
          </section>
        )}
      </main>

      {/* Right Panel - Hidden in Explorer */}
      {activeTab !== 'explorer' && (
        <aside className="right-panel">
          <section>
            <h2 style={{ fontSize: '16px', fontWeight: 600, marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <ShieldAlert size={18} color="#f59e0b" />
              Ambiguity Resolver
            </h2>
            {ambiguities.length > 0 ? (
              ambiguities.map((a: any) => (
                <AmbiguityResolver
                  key={a.id}
                  tag={a.tag}
                  candidates={a.candidates}
                  onSelect={(selectedSymbolId: string) => handleResolveAmbiguity(a.id, selectedSymbolId)}
                />
              ))
            ) : (
              <div style={{ padding: '20px', fontSize: '12px', color: '#64748b', textAlign: 'center' }} className="glass-panel">
                No active ambiguities for current context.
              </div>
            )}
          </section>

          <div className="glass-panel" style={{ padding: '16px' }}>
            <h3 style={{ fontSize: '14px', fontWeight: 600, marginBottom: '12px' }}>System Feeds</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ fontSize: '11px', borderLeft: '2px solid #3b82f6', paddingLeft: '12px' }}>
                <div style={{ color: '#94a3b8' }}>Ingestion Engine</div>
                <div style={{ color: '#f8fafc' }}>Project Analysis Complete: {selectedProject}</div>
              </div>
              <div style={{ fontSize: '11px', borderLeft: '2px solid #10b981', paddingLeft: '12px' }}>
                <div style={{ color: '#94a3b8' }}>Context Orchestrator</div>
                <div style={{ color: '#10b981' }}>Trust Score synced for {selectedProject}</div>
              </div>
            </div>
          </div>
        </aside>
      )}

      {/* Refine Dialog */}
      <AnimatePresence>
        {showRefineDialog && refineBlueprintPath && (
          <RefineDialog
            blueprintPath={refineBlueprintPath}
            onClose={() => setShowRefineDialog(false)}
            onRefine={(prompt, updateExisting) => {
              console.log('Refinement started:', prompt, updateExisting);
              refreshBlueprints();
              if (updateExisting) {
                if (selectedBlueprint === refineBlueprintPath) {
                  const current = selectedBlueprint;
                  setSelectedBlueprint(null);
                  setTimeout(() => setSelectedBlueprint(current), 50);
                }
              }
            }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}

export default App;
