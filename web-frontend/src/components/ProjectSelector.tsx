import { useState, useEffect, useRef } from 'react';
import { Search, X, Check, ChevronDown, ChevronUp, Folder, FileCode } from 'lucide-react';
import type { Project } from '../services/api';

interface ProjectSelectorProps {
  projects: Project[];
  selectedProjects: string[]; // Changed to array for multi-select
  onSelectionChange: (selected: string[]) => void;
  showGroups?: boolean;
  multiSelect?: boolean;
}

interface GroupedProjects {
  [domain: string]: Project[];
}

export const ProjectSelector = ({
  projects,
  selectedProjects,
  onSelectionChange,
  showGroups = true,
  multiSelect = false
}: ProjectSelectorProps) => {
  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set(['General'])); // Default expanded
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Group projects by domain
  const groupedProjects: GroupedProjects = projects.reduce((acc, project) => {
    const domain = project.domain || 'General';
    if (!acc[domain]) {
      acc[domain] = [];
    }
    acc[domain].push(project);
    return acc;
  }, {} as GroupedProjects);

  // Filter projects based on search query
  const filteredGroups: GroupedProjects = Object.keys(groupedProjects).reduce((acc, domain) => {
    const filtered = groupedProjects[domain].filter(project =>
      project.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (project.techStack?.some(tech => tech.toLowerCase().includes(searchQuery.toLowerCase())) || false)
    );
    if (filtered.length > 0) {
      acc[domain] = filtered;
    }
    return acc;
  }, {} as GroupedProjects);

  // Sort groups: General first, then alphabetically
  const sortedGroupKeys = Object.keys(filteredGroups).sort((a, b) => {
    if (a === 'General') return -1;
    if (b === 'General') return 1;
    return a.localeCompare(b);
  });

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [isOpen]);

  const toggleGroup = (domain: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(domain)) {
        next.delete(domain);
      } else {
        next.add(domain);
      }
      return next;
    });
  };

  const toggleProject = (projectName: string) => {
    if (!multiSelect) {
      // Single select mode: just set the selection and close
      onSelectionChange([projectName]);
      setIsOpen(false);
      return;
    }

    // Multi-select mode: toggle in the selection array
    const newSelection = selectedProjects.includes(projectName)
      ? selectedProjects.filter(p => p !== projectName)
      : [...selectedProjects, projectName];
    onSelectionChange(newSelection);
  };

  const toggleGroupSelection = (domain: string, e: React.MouseEvent) => {
    e.stopPropagation(); // Prevent group expand/collapse when clicking checkbox
    
    if (!multiSelect) {
      return; // Only works in multi-select mode
    }

    // Use groupedProjects to get ALL projects in the group, not just filtered ones
    const domainProjects = groupedProjects[domain] || [];
    const allSelected = domainProjects.length > 0 && domainProjects.every(p => selectedProjects.includes(p.name));
    
    if (allSelected) {
      // Deselect all projects in this group
      const newSelection = selectedProjects.filter(p => 
        !domainProjects.some(dp => dp.name === p)
      );
      onSelectionChange(newSelection);
    } else {
      // Select all projects in this group
      const projectNames = domainProjects.map(p => p.name);
      const newSelection = [...new Set([...selectedProjects, ...projectNames])];
      onSelectionChange(newSelection);
    }
  };

  const clearSelection = () => {
    onSelectionChange([]);
  };

  const getDisplayText = () => {
    if (selectedProjects.length === 0) {
      return 'Select Project(s)...';
    }
    if (selectedProjects.length === 1) {
      const project = projects.find(p => p.name === selectedProjects[0]);
      return project?.name || selectedProjects[0];
    }
    return `${selectedProjects.length} project(s) selected`;
  };

  const totalProjects = projects.length;
  const totalDomains = Object.keys(groupedProjects).length;

  return (
    <div style={{ position: 'relative', width: '100%' }} ref={dropdownRef}>
      {/* Trigger Button */}
      <div
        onClick={() => setIsOpen(!isOpen)}
        className="glass-panel"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '12px',
          padding: '8px 16px',
          borderRadius: '12px',
          cursor: 'pointer',
          userSelect: 'none',
          border: '1px solid rgba(255,255,255,0.1)',
          minWidth: '400px'
        }}
      >
        <FileCode size={16} color="var(--primary)" />
        <div style={{ flex: 1, textAlign: 'left' }}>
          <div style={{ fontSize: '14px', fontWeight: 600, color: 'white' }}>
            {getDisplayText()}
          </div>
          <div style={{ fontSize: '10px', color: '#64748b', marginTop: '2px' }}>
            {totalProjects} projects in {totalDomains} group(s)
          </div>
        </div>
        {selectedProjects.length > 0 && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              clearSelection();
            }}
            style={{
              background: 'rgba(239,68,68,0.1)',
              border: 'none',
              borderRadius: '6px',
              padding: '4px 8px',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center'
            }}
          >
            <X size={14} color="#ef4444" />
          </button>
        )}
        {isOpen ? <ChevronUp size={16} color="#94a3b8" /> : <ChevronDown size={16} color="#94a3b8" />}
      </div>

      {/* Dropdown Panel */}
      {isOpen && (
        <div
          className="glass-panel"
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            minWidth: '600px',
            maxWidth: '800px',
            marginTop: '8px',
            borderRadius: '12px',
            border: '1px solid rgba(255,255,255,0.1)',
            background: '#0f172a',
            boxShadow: '0 8px 32px rgba(0,0,0,0.4)',
            zIndex: 1000,
            maxHeight: '500px',
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden'
          }}
        >
          {/* Search Bar */}
          <div style={{ padding: '12px', borderBottom: '1px solid rgba(255,255,255,0.05)' }}>
            <div style={{ position: 'relative' }}>
              <Search size={16} color="#64748b" style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)' }} />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search projects or technologies..."
                style={{
                  width: '100%',
                  padding: '10px 12px 10px 36px',
                  background: 'rgba(255,255,255,0.03)',
                  border: '1px solid rgba(255,255,255,0.08)',
                  borderRadius: '8px',
                  color: '#fff',
                  fontSize: '13px',
                  outline: 'none'
                }}
                autoFocus
              />
            </div>
          </div>

          {/* Projects List - Scrollable */}
          <div style={{ overflowY: 'auto', maxHeight: '400px', padding: '8px' }}>
            {sortedGroupKeys.length === 0 ? (
              <div style={{ padding: '32px', textAlign: 'center', color: '#64748b', fontSize: '13px' }}>
                No projects found matching "{searchQuery}"
              </div>
            ) : (
              sortedGroupKeys.map(domain => {
                const domainProjects = filteredGroups[domain];
                const isExpanded = expandedGroups.has(domain);
                const domainSelectedCount = domainProjects.filter(p => selectedProjects.includes(p.name)).length;

                return (
                  <div key={domain} style={{ marginBottom: '8px' }}>
                    {/* Group Header */}
                    {showGroups && (
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '8px',
                          padding: '8px 12px',
                          background: 'rgba(255,255,255,0.02)',
                          borderRadius: '8px',
                          cursor: 'pointer',
                          userSelect: 'none',
                          marginBottom: '4px'
                        }}
                      >
                        {/* Group Selection Checkbox (only in multi-select mode) */}
                        {multiSelect && (
                          <div
                            onClick={(e) => toggleGroupSelection(domain, e)}
                            style={{
                              width: '18px',
                              height: '18px',
                              border: `2px solid ${domainSelectedCount === domainProjects.length ? 'var(--primary)' : '#64748b'}`,
                              borderRadius: '4px',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              background: domainSelectedCount === domainProjects.length ? 'var(--primary)' : 'transparent',
                              flexShrink: 0,
                              cursor: 'pointer'
                            }}
                          >
                            {domainSelectedCount === domainProjects.length && <Check size={12} color="white" />}
                          </div>
                        )}
                        
                        {/* Group Name and Expand/Collapse */}
                        <div
                          onClick={() => toggleGroup(domain)}
                          style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: 1, cursor: 'pointer' }}
                        >
                          <Folder size={14} color="#64748b" />
                          <span style={{ fontSize: '12px', fontWeight: 600, color: '#94a3b8', flex: 1 }}>
                            {domain}
                          </span>
                          {multiSelect && domainSelectedCount > 0 && (
                            <span style={{
                              fontSize: '10px',
                              background: 'var(--primary)',
                              color: 'white',
                              padding: '2px 6px',
                              borderRadius: '10px',
                              fontWeight: 600
                            }}>
                              {domainSelectedCount}/{domainProjects.length}
                            </span>
                          )}
                          <span style={{ fontSize: '11px', color: '#64748b' }}>
                            {domainProjects.length} project{domainProjects.length !== 1 ? 's' : ''}
                          </span>
                          {isExpanded ? <ChevronUp size={14} color="#64748b" /> : <ChevronDown size={14} color="#64748b" />}
                        </div>
                      </div>
                    )}

                    {/* Group Projects */}
                    {(!showGroups || isExpanded) && (
                      <div style={{ marginLeft: showGroups ? '16px' : '0', display: 'flex', flexDirection: 'column', gap: '2px' }}>
                        {domainProjects.map(project => {
                          const isSelected = selectedProjects.includes(project.name);

                          return (
                            <div
                              key={project.id}
                              onClick={() => toggleProject(project.name)}
                              style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: '8px',
                                padding: '8px 12px',
                                background: isSelected ? 'rgba(16,185,129,0.1)' : 'transparent',
                                borderRadius: '6px',
                                cursor: 'pointer',
                                userSelect: 'none',
                                border: isSelected ? '1px solid rgba(16,185,129,0.3)' : '1px solid transparent',
                                transition: 'all 0.2s'
                              }}
                              onMouseEnter={(e) => {
                                if (!isSelected) {
                                  e.currentTarget.style.background = 'rgba(255,255,255,0.05)';
                                }
                              }}
                              onMouseLeave={(e) => {
                                if (!isSelected) {
                                  e.currentTarget.style.background = 'transparent';
                                }
                              }}
                            >
                              {multiSelect && (
                                <div style={{
                                  width: '18px',
                                  height: '18px',
                                  border: `2px solid ${isSelected ? 'var(--primary)' : '#64748b'}`,
                                  borderRadius: '4px',
                                  display: 'flex',
                                  alignItems: 'center',
                                  justifyContent: 'center',
                                  background: isSelected ? 'var(--primary)' : 'transparent',
                                  flexShrink: 0
                                }}>
                                  {isSelected && <Check size={12} color="white" />}
                                </div>
                              )}
                              <div style={{ flex: 1, minWidth: 0 }}>
                                <div style={{
                                  fontSize: '13px',
                                  fontWeight: 600,
                                  color: isSelected ? '#f8fafc' : '#e2e8f0',
                                  whiteSpace: 'nowrap',
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis'
                                }}>
                                  {project.name}
                                </div>
                                {project.techStack && project.techStack.length > 0 && (
                                  <div style={{ display: 'flex', gap: '4px', marginTop: '4px', flexWrap: 'wrap' }}>
                                    {project.techStack.slice(0, 2).map(tech => (
                                      <span
                                        key={tech}
                                        style={{
                                          fontSize: '9px',
                                          background: 'rgba(255,255,255,0.05)',
                                          padding: '2px 6px',
                                          borderRadius: '4px',
                                          color: '#94a3b8'
                                        }}
                                      >
                                        {tech}
                                      </span>
                                    ))}
                                    {project.techStack.length > 2 && (
                                      <span style={{ fontSize: '9px', color: '#64748b' }}>
                                        +{project.techStack.length - 2}
                                      </span>
                                    )}
                                  </div>
                                )}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>

          {/* Footer with selection count */}
          {multiSelect && selectedProjects.length > 0 && (
            <div style={{
              padding: '12px',
              borderTop: '1px solid rgba(255,255,255,0.05)',
              background: 'rgba(16,185,129,0.05)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center'
            }}>
              <span style={{ fontSize: '12px', color: '#94a3b8' }}>
                {selectedProjects.length} project{selectedProjects.length !== 1 ? 's' : ''} selected
              </span>
              <button
                onClick={clearSelection}
                style={{
                  background: 'transparent',
                  border: '1px solid rgba(239,68,68,0.3)',
                  color: '#ef4444',
                  padding: '4px 12px',
                  borderRadius: '6px',
                  fontSize: '11px',
                  fontWeight: 600,
                  cursor: 'pointer'
                }}
              >
                Clear
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
