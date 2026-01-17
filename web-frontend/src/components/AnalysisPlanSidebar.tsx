import React, { useState, useEffect } from 'react';
import './AnalysisPlanSidebar.css';

interface AnalysisPlan {
  sessionId: string;
  architectPlan: {
    userQuery: string;
    projectContext: string;
    identifiedAreas: string[];
    totalTasksPlanned: number;
  };
  workerAssignments: Array<{
    taskId: string;
    persona: string;
    focusArea: string;
    specificQuestion: string;
    status: string;
    iteration: number;
    evidenceFiles: string[];
    validationErrors: string | null;
  }>;
  qaChecklist: {
    checks: Array<{
      iteration: number;
      checkType: string;
      status: string;
      details: string;
    }>;
  };
  evidenceSummary: {
    totalFilesAnalyzed: number;
    topModules: Array<{ moduleName: string; fileCount: number }>;
    evidenceQualityScore: number;
  };
}

interface Props {
  sessionId: string | null;
  isOpen: boolean;
  onToggle: () => void;
}

export const AnalysisPlanSidebar: React.FC<Props> = ({ sessionId, isOpen, onToggle }) => {
  const [plan, setPlan] = useState<AnalysisPlan | null>(null);
  const [loading, setLoading] = useState(false);
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(['architect', 'workers']));

  useEffect(() => {
    if (sessionId && isOpen) {
      fetchPlan(sessionId);
      // Poll every 2 seconds while analysis is running
      const interval = setInterval(() => fetchPlan(sessionId), 2000);
      return () => clearInterval(interval);
    }
  }, [sessionId, isOpen]);

  const fetchPlan = async (sid: string) => {
    try {
      setLoading(true);
      const response = await fetch(`http://localhost:8082/api/semantic/plans/${sid}`);
      if (response.ok) {
        const data = await response.json();
        setPlan(data);
      }
    } catch (error) {
      console.error('Failed to fetch analysis plan:', error);
    } finally {
      setLoading(false);
    }
  };

  const toggleSection = (section: string) => {
    const newExpanded = new Set(expandedSections);
    if (newExpanded.has(section)) {
      newExpanded.delete(section);
    } else {
      newExpanded.add(section);
    }
    setExpandedSections(newExpanded);
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'COMPLETED': return '✅';
      case 'COMPLETED_SATISFIED': return '✅';
      case 'RUNNING': return '🔄';
      case 'REFINING': return '🔁';
      case 'FAILED_VALIDATION': return '❌';
      case 'FAILED': return '❌';
      case 'PENDING': return '⏳';
      default: return '📋';
    }
  };

  const getQAStatusIcon = (status: string) => {
    switch (status) {
      case 'PASS': return '✅';
      case 'WARN': return '⚠️';
      case 'FAIL': return '❌';
      default: return '📋';
    }
  };

  if (!isOpen) {
    return (
      <button className="sidebar-toggle collapsed" onClick={onToggle} title="Show Analysis Plan">
        📋
      </button>
    );
  }

  return (
    <div className="analysis-plan-sidebar">
      <div className="sidebar-header">
        <h3>🧠 Analysis Plan</h3>
        <button className="close-btn" onClick={onToggle}>×</button>
      </div>

      {loading && !plan && (
        <div className="loading">Loading plan...</div>
      )}

      {!loading && !plan && (
        <div className="empty-state" style={{ padding: '20px', textAlign: 'center', color: '#64748b' }}>
           <p>No active analysis plan.</p>
           <p style={{ fontSize: '13px' }}>Run a query in Semantic Explorer to generate a new execution strategy.</p>
        </div>
      )}

      {plan && (
        <div className="sidebar-content">
          {/* Architect's Plan */}
          <div className="section">
            <div className="section-header" onClick={() => toggleSection('architect')}>
              <span>{expandedSections.has('architect') ? '▼' : '▶'} Head Architect's Strategy</span>
            </div>
            {expandedSections.has('architect') && (
              <div className="section-body">
                <div className="plan-stat">
                  <strong>Total Tasks:</strong> {plan.architectPlan.totalTasksPlanned}
                </div>
                <div className="plan-stat">
                  <strong>Focus Areas:</strong>
                  <ul>
                    {plan.architectPlan.identifiedAreas.map((area, idx) => (
                      <li key={idx}>{area}</li>
                    ))}
                  </ul>
                </div>
              </div>
            )}
          </div>

          {/* Worker Assignments */}
          <div className="section">
            <div className="section-header" onClick={() => toggleSection('workers')}>
              <span>{expandedSections.has('workers') ? '▼' : '▶'} Worker Assignments</span>
            </div>
            {expandedSections.has('workers') && (
              <div className="section-body">
                {plan.workerAssignments.map((worker) => (
                  <div key={worker.taskId} className="worker-card">
                    <div className="worker-header">
                      {getStatusIcon(worker.status)} <strong>{worker.persona}</strong>
                      <span className="iteration-badge">Iter {worker.iteration}</span>
                    </div>
                    <div className="worker-details">
                      <div><strong>Focus:</strong> {worker.focusArea}</div>
                      {worker.specificQuestion && (
                        <div className="worker-question">
                          <strong>Task:</strong> {worker.specificQuestion}
                        </div>
                      )}
                      {worker.evidenceFiles.length > 0 && (
                        <div className="evidence-files">
                          <strong>Files ({worker.evidenceFiles.length}):</strong>
                          <ul>
                            {worker.evidenceFiles.slice(0, 3).map((file, idx) => (
                              <li key={idx}>{file}</li>
                            ))}
                            {worker.evidenceFiles.length > 3 && (
                              <li>... +{worker.evidenceFiles.length - 3} more</li>
                            )}
                          </ul>
                        </div>
                      )}
                      {worker.validationErrors && (
                        <div className="validation-error">
                          ⚠️ {worker.validationErrors}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* QA Checklist */}
          <div className="section">
            <div className="section-header" onClick={() => toggleSection('qa')}>
              <span>{expandedSections.has('qa') ? '▼' : '▶'} QA Validation Checklist</span>
            </div>
            {expandedSections.has('qa') && (
              <div className="section-body">
                {plan.qaChecklist.checks.map((check, idx) => (
                  <div key={idx} className="qa-check">
                    {getQAStatusIcon(check.status)} <strong>Iter {check.iteration}:</strong> {check.checkType}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Evidence Summary */}
          <div className="section">
            <div className="section-header" onClick={() => toggleSection('evidence')}>
              <span>{expandedSections.has('evidence') ? '▼' : '▶'} Evidence Map</span>
            </div>
            {expandedSections.has('evidence') && (
              <div className="section-body">
                <div className="evidence-stats">
                  <div className="stat">
                    <span className="stat-value">{plan.evidenceSummary.totalFilesAnalyzed}</span>
                    <span className="stat-label">Files Analyzed</span>
                  </div>
                  <div className="stat">
                    <span className="stat-value">{plan.evidenceSummary.evidenceQualityScore.toFixed(1)}/10</span>
                    <span className="stat-label">Evidence Quality</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
