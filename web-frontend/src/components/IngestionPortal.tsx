import { useState, useEffect } from 'react';
import { Github, Upload, CheckCircle, Loader2, Server, Folder } from 'lucide-react';
import { api } from '../services/api';
import type { Project } from '../services/api';

export const IngestionPortal = () => {
    const [gitUrl, setGitUrl] = useState('');
    const [groupName, setGroupName] = useState('');
    const [status, setStatus] = useState<'idle' | 'loading' | 'success'>('idle');
    const [message, setMessage] = useState('');
    const [projects, setProjects] = useState<Project[]>([]);

    const [ingestionMessages, setIngestionMessages] = useState<string[]>([]);

    // Stream updates via SSE
    useEffect(() => {
        // 1. Initial Load (Snapshot)
        api.getIngestionStatus().then(r => setProjects(r.data)).catch(console.error);

        // 2. Real-time Updates (Stream)
        const eventSource = new EventSource(api.ingestionStreamUrl);

        eventSource.addEventListener('init', () => {
            console.log('SSE Connected');
            setIngestionMessages(prev => [...prev, '✅ Connected to ingestion service']);
        });

        eventSource.addEventListener('progress', (event: MessageEvent) => {
            try {
                const updatedProject: Project = JSON.parse(event.data);
                setProjects(prev => {
                    const index = prev.findIndex(p => p.id === updatedProject.id);
                    if (index !== -1) {
                        const next = [...prev];
                        next[index] = updatedProject;
                        return next;
                    } else {
                        // New project detected
                        return [updatedProject, ...prev];
                    }
                });
            } catch (e) {
                console.error('Failed to parse progress event', e);
            }
        });

        eventSource.addEventListener('message', (event: MessageEvent) => {
            const message = event.data;
            setIngestionMessages(prev => {
                const updated = [...prev, message];
                // Keep only last 20 messages
                return updated.slice(-20);
            });
        });

        eventSource.onerror = (err) => {
            console.error('SSE Connection Error', err);
            // Don't close on error - allow reconnection attempts
            // The browser will automatically attempt to reconnect for SSE
            if (eventSource.readyState === EventSource.CLOSED) {
                console.warn('SSE connection closed. It will attempt to reconnect automatically.');
            }
        };

        return () => {
            eventSource.close();
        };
    }, []);

    const handleGitIngest = async () => {
        if (!gitUrl) return;
        setStatus('loading');
        try {
            await api.ingestGit(gitUrl, groupName);
            setStatus('success');
            setMessage('Git repository queued for ingestion successfully.');
            setGitUrl('');
            setGroupName('');
            setTimeout(() => setStatus('idle'), 5000);
        } catch (error) {
            setStatus('idle');
            setMessage('Ingestion failed. Please check the URL.');
        }
    };

    const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
        if (!e.target.files?.[0]) return;
        const file = e.target.files[0];
        const fileSizeMB = (file.size / (1024 * 1024)).toFixed(1);
        
        setStatus('loading');
        setMessage(`Uploading ZIP file (${fileSizeMB} MB)... This may take a few minutes for large files.`);
        
        const formData = new FormData();
        formData.append('file', file);
        if (groupName) formData.append('groupName', groupName);

        try {
            const response = await api.uploadZip(formData);
            setStatus('success');
            const message = response?.data?.message || 'ZIP archive uploaded and processing started.';
            setMessage(`${message} Check the Ingestion Monitor tab for progress.`);
            setTimeout(() => setStatus('idle'), 10000); // Longer timeout for user to read message
        } catch (error: any) {
            setStatus('idle');
            const errorMessage = error?.response?.data?.message || error?.message || 'Upload failed. Please check the file and try again.';
            setMessage(`Upload failed: ${errorMessage}`);
            console.error('Upload error:', error);
        }
    };

    const [activeTab, setActiveTab] = useState<'onboarding' | 'monitor'>('onboarding');
    const [activeOnboardingMethod, setActiveOnboardingMethod] = useState<'git' | 'zip'>('git');

    const formatTime = (seconds?: number) => {
        if (!seconds || seconds <= 0) return 'Calculating...';
        if (seconds < 60) return `${seconds}s`;
        const mins = Math.floor(seconds / 60);
        const secs = seconds % 60;
        return `${mins}m ${secs}s`;
    };

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
            {/* Top Level Tabs */}
            <div style={{ display: 'flex', gap: '32px', borderBottom: '1px solid var(--border-light)', paddingBottom: '12px' }}>
                <button
                    onClick={() => setActiveTab('onboarding')}
                    style={{
                        background: 'none',
                        border: 'none',
                        color: activeTab === 'onboarding' ? 'var(--primary)' : '#64748b',
                        fontSize: '16px',
                        fontWeight: 600,
                        cursor: 'pointer',
                        padding: '8px 0',
                        position: 'relative'
                    }}
                >
                    Project Onboarding
                    {activeTab === 'onboarding' && (
                        <div style={{ position: 'absolute', bottom: '-13px', left: 0, right: 0, height: '2px', background: 'var(--primary)' }}></div>
                    )}
                </button>
                <button
                    onClick={() => setActiveTab('monitor')}
                    style={{
                        background: 'none',
                        border: 'none',
                        color: activeTab === 'monitor' ? 'var(--primary)' : '#64748b',
                        fontSize: '16px',
                        fontWeight: 600,
                        cursor: 'pointer',
                        padding: '8px 0',
                        position: 'relative'
                    }}
                >
                    Ingestion Monitor
                    <span style={{
                        marginLeft: '8px',
                        fontSize: '10px',
                        background: projects.filter(p => p.status === 'IN_PROGRESS' || p.status === 'CLONING' || p.status === 'PENDING_CLONE').length > 0 ? 'var(--primary)' : 'rgba(255,255,255,0.1)',
                        color: 'white',
                        padding: '2px 6px',
                        borderRadius: '10px'
                    }}>
                        {projects.filter(p => p.status === 'IN_PROGRESS' || p.status === 'CLONING' || p.status === 'PENDING_CLONE').length}
                    </span>
                    {activeTab === 'monitor' && (
                        <div style={{ position: 'absolute', bottom: '-13px', left: 0, right: 0, height: '2px', background: 'var(--primary)' }}></div>
                    )}
                </button>
            </div>

            {activeTab === 'onboarding' ? (
                <div className="glass-panel" style={{ padding: '32px', maxWidth: '800px' }}>
                    {/* Sub-Tabs for Onboarding Methods */}
                    <div style={{
                        display: 'flex',
                        gap: '4px',
                        background: 'rgba(255,255,255,0.05)',
                        padding: '4px',
                        borderRadius: '12px',
                        marginBottom: '32px',
                        width: 'fit-content'
                    }}>
                        <button
                            onClick={() => setActiveOnboardingMethod('git')}
                            style={{
                                padding: '8px 20px',
                                borderRadius: '8px',
                                border: 'none',
                                background: activeOnboardingMethod === 'git' ? 'var(--primary)' : 'transparent',
                                color: activeOnboardingMethod === 'git' ? 'white' : '#94a3b8',
                                fontSize: '13px',
                                fontWeight: 600,
                                cursor: 'pointer'
                            }}
                        >
                            Git Repository
                        </button>
                        <button
                            onClick={() => setActiveOnboardingMethod('zip')}
                            style={{
                                padding: '8px 20px',
                                borderRadius: '8px',
                                border: 'none',
                                background: activeOnboardingMethod === 'zip' ? 'var(--primary)' : 'transparent',
                                color: activeOnboardingMethod === 'zip' ? 'white' : '#94a3b8',
                                fontSize: '13px',
                                fontWeight: 600,
                                cursor: 'pointer'
                            }}
                        >
                            ZIP Archive
                        </button>
                    </div>

                    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>

                        {/* Group Name Input (Shared) */}
                        <div>
                            <label style={{ fontSize: '12px', color: '#94a3b8', marginBottom: '8px', display: 'block' }}>PROJECT GROUP (OPTIONAL)</label>
                            <div className="glass-panel" style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 16px', borderRadius: '12px' }}>
                                <Folder size={20} color="#64748b" />
                                <input
                                    type="text"
                                    value={groupName}
                                    onChange={(e) => setGroupName(e.target.value)}
                                    placeholder="Enter or select a group name (e.g. 'Metasfresh Core')"
                                    list="existing-groups"
                                    style={{ background: 'transparent', border: 'none', color: 'white', width: '100%', outline: 'none', fontSize: '14px' }}
                                />
                                <datalist id="existing-groups">
                                    {Array.from(new Set(projects.map(p => p.domain).filter(d => d && d !== 'General'))).map(group => (
                                        <option key={group} value={group} />
                                    ))}
                                </datalist>
                            </div>
                        </div>

                        {activeOnboardingMethod === 'git' ? (
                            <div>
                                <label style={{ fontSize: '12px', color: '#94a3b8', marginBottom: '8px', display: 'block' }}>GIT REPOSITORY URL</label>
                                <div style={{ display: 'flex', gap: '12px' }}>
                                    <div className="glass-panel" style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 16px', borderRadius: '12px', flex: 1 }}>
                                        <Github size={20} color="#64748b" />
                                        <input
                                            type="text"
                                            value={gitUrl}
                                            onChange={(e) => setGitUrl(e.target.value)}
                                            placeholder="https://github.com/org/repo.git"
                                            style={{ background: 'transparent', border: 'none', color: 'white', width: '100%', outline: 'none', fontSize: '14px' }}
                                        />
                                    </div>
                                    <button
                                        onClick={handleGitIngest}
                                        disabled={status === 'loading'}
                                        className="premium-button"
                                        style={{
                                            background: 'var(--primary)',
                                            color: 'white',
                                            border: 'none',
                                            padding: '0 32px',
                                            borderRadius: '12px',
                                            fontWeight: 600,
                                            cursor: 'pointer'
                                        }}
                                    >
                                        {status === 'loading' ? <Loader2 className="animate-spin" size={20} /> : 'Begin Analysis'}
                                    </button>
                                </div>
                            </div>
                        ) : (
                            <div
                                style={{
                                    border: '2px dashed var(--border-light)',
                                    borderRadius: '20px',
                                    padding: '64px 32px',
                                    textAlign: 'center',
                                    background: 'rgba(255,255,255,0.02)',
                                    cursor: 'pointer',
                                    position: 'relative'
                                }}
                            >
                                <input
                                    type="file"
                                    accept=".zip"
                                    onChange={handleFileUpload}
                                    style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: '100%', opacity: 0, cursor: 'pointer' }}
                                />
                                <Upload size={48} color="#64748b" style={{ marginBottom: '16px' }} />
                                <div style={{ fontSize: '18px', fontWeight: 600, color: '#f8fafc' }}>Drop your project archive here</div>
                                <div style={{ fontSize: '13px', color: '#64748b', marginTop: '8px' }}>We'll extract and auto-detect the tech stack</div>
                            </div>
                        )}

                        {status === 'success' && (
                            <div style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: '12px',
                                color: 'var(--primary)',
                                background: 'rgba(16,185,129,0.1)',
                                padding: '16px',
                                borderRadius: '12px',
                                border: '1px solid var(--primary)'
                            }}>
                                <CheckCircle size={20} />
                                <div>
                                    <div style={{ fontWeight: 600 }}>{message || 'Queued Successfully'}</div>
                                    <div style={{ fontSize: '12px', opacity: 0.8 }}>You can track progress in the Ingestion Monitor tab.</div>
                                </div>
                            </div>
                        )}
                    </div>
                </div>
            ) : (
                /* Ingestion Monitor Tab */
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                    {/* Activity Log */}
                    {ingestionMessages.length > 0 && (
                        <div className="glass-panel" style={{ padding: '20px', maxHeight: '200px', overflowY: 'auto' }}>
                            <div style={{ fontSize: '12px', color: '#94a3b8', marginBottom: '12px', fontWeight: 600 }}>ACTIVITY LOG</div>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontFamily: 'monospace', fontSize: '11px' }}>
                                {ingestionMessages.slice().reverse().map((msg, idx) => (
                                    <div key={idx} style={{ color: msg.includes('❌') ? '#ef4444' : msg.includes('✅') ? 'var(--primary)' : '#94a3b8' }}>
                                        {msg}
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}
                    
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(400px, 1fr))', gap: '20px' }}>
                        {projects.length === 0 && (
                            <div style={{ gridColumn: '1/ -1', textAlign: 'center', padding: '64px', color: '#64748b' }}>
                                <Server size={48} style={{ marginBottom: '16px', opacity: 0.2 }} />
                                <div>No ingestion tasks found in the history.</div>
                            </div>
                        )}

                    {projects.map(project => (
                        <div key={project.id} className="glass-panel" style={{ padding: '24px' }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '20px', alignItems: 'flex-start' }}>
                                <div>
                                    <h3 style={{ fontSize: '16px', fontWeight: 600, color: '#f8fafc', marginBottom: '4px' }}>{project.name}</h3>
                                    <div style={{ fontSize: '12px', color: '#64748b' }}>{project.domain} Domain</div>
                                </div>
                                <span style={{
                                    fontSize: '11px',
                                    padding: '4px 10px',
                                    borderRadius: '20px',
                                    background: project.status === 'COMPLETED' ? 'rgba(16,185,129,0.1)' : 
                                               project.status === 'FAILED' ? 'rgba(239,68,68,0.1)' :
                                               project.status === 'CLONING' || project.status === 'PENDING_CLONE' ? 'rgba(251,191,36,0.1)' :
                                               'rgba(59,130,246,0.1)',
                                    color: project.status === 'COMPLETED' ? 'var(--primary)' : 
                                           project.status === 'FAILED' ? '#ef4444' :
                                           project.status === 'CLONING' || project.status === 'PENDING_CLONE' ? '#fbbf24' :
                                           '#3b82f6',
                                    fontWeight: 600
                                }}>
                                    {(project.status || 'PENDING').replace('_', ' ')}
                                </span>
                            </div>

                            <div style={{ marginBottom: '20px' }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px', marginBottom: '8px' }}>
                                    <span style={{ color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '6px' }}>
                                        Overall Progress
                                        {project.totalFiles ? (
                                            <span style={{ fontSize: '11px', color: '#64748b' }}>
                                                ({project.processedFiles} / {project.totalFiles} files)
                                            </span>
                                        ) : null}
                                    </span>
                                    <span style={{ fontWeight: 600, color: 'var(--primary)' }}>{project.ingestionProgress}%</span>
                                </div>
                                <div style={{ width: '100%', height: '8px', background: 'rgba(255,255,255,0.05)', borderRadius: '4px', overflow: 'hidden' }}>
                                    <div style={{
                                        width: `${project.ingestionProgress}%`,
                                        height: '100%',
                                        background: 'var(--primary)',
                                        transition: 'width 0.8s cubic-bezier(0.4, 0, 0.2, 1)',
                                        boxShadow: '0 0 10px var(--primary)'
                                    }}></div>
                                </div>
                            </div>

                            {(project.status === 'IN_PROGRESS' || project.status === 'CLONING' || project.status === 'PENDING_CLONE') && (
                                <div style={{ background: 'rgba(255,255,255,0.02)', borderRadius: '12px', padding: '16px', border: '1px solid var(--border-light)' }}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '12px', alignItems: 'center' }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#94a3b8', fontSize: '11px' }}>
                                            <Loader2 className="animate-spin" size={14} />
                                            {project.status === 'CLONING' || project.status === 'PENDING_CLONE' ? 'Cloning Repository:' : 'Currently Ingesting:'}
                                        </div>
                                        {project.status === 'IN_PROGRESS' && (
                                            <div style={{ textAlign: 'right' }}>
                                                <div style={{ fontSize: '11px', color: '#64748b' }}>
                                                    Time Left: <span style={{ color: '#f8fafc', fontWeight: 600 }}>{formatTime(project.estimatedRemainingSeconds)}</span>
                                                </div>
                                                <div style={{ fontSize: '10px', color: 'var(--primary)', marginTop: '2px' }}>
                                                    {(project.totalFiles || 0) - (project.processedFiles || 0)} files remaining
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                    <div style={{
                                        fontFamily: 'monospace',
                                        fontSize: '12px',
                                        color: project.status === 'CLONING' || project.status === 'PENDING_CLONE' ? '#fbbf24' : 'var(--primary)',
                                        whiteSpace: 'nowrap',
                                        overflow: 'hidden',
                                        textOverflow: 'ellipsis',
                                        background: 'rgba(0,0,0,0.2)',
                                        padding: '8px',
                                        borderRadius: '6px'
                                    }}>
                                        {project.status === 'CLONING' || project.status === 'PENDING_CLONE' 
                                            ? 'Cloning repository from Git...' 
                                            : (project.currentFile || 'Scanning filesystem...')}
                                    </div>
                                </div>
                            )}

                            <div style={{ marginTop: '20px', display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
                                {project.techStack?.map(tech => (
                                    <span key={tech} style={{ fontSize: '10px', background: 'rgba(255,255,255,0.05)', padding: '2px 8px', borderRadius: '4px', color: '#94a3b8' }}>
                                        {tech}
                                    </span>
                                ))}
                            </div>
                        </div>
                    ))}
                    </div>
                </div>
            )}
        </div>
    );
};
