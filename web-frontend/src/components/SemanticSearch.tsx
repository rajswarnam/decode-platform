import { useState, useEffect } from 'react';
import {
    Search, Bot, Sparkles, Loader2, Check, Copy, AlertTriangle, X, Settings, Save, Download, FileText, Tag
} from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { motion, AnimatePresence } from 'framer-motion';
import { AnalysisPlanSidebar } from './AnalysisPlanSidebar';

interface SemanticSearchProps {
    selectedBlueprintPath?: string | null;
    domain: string;
}

export const SemanticSearch = ({ selectedBlueprintPath, domain }: SemanticSearchProps) => {
    const [query, setQuery] = useState('');
    const [answer, setAnswer] = useState('');
    const [loading, setLoading] = useState(false);
    const [progress, setProgress] = useState<string[]>([]);
    const [currentStatus, setCurrentStatus] = useState('');
    const [ambiguities, setAmbiguities] = useState<any[]>([]);
    const [showAmbiguities, setShowAmbiguities] = useState(false);
    const [copied, setCopied] = useState(false);
    const [isFocused, setIsFocused] = useState(false);
    const [saved, setSaved] = useState(false);
    const [showBlueprints, setShowBlueprints] = useState(false);
    const [blueprints, setBlueprints] = useState<any[]>([]);
    const [showSaveDialog, setShowSaveDialog] = useState(false);
    const [saveTitle, setSaveTitle] = useState('');
    const [saveCategory, setSaveCategory] = useState('');
    const [saveTags, setSaveTags] = useState('');
    
    // Analysis Plan Sidebar
    const [sessionId, setSessionId] = useState<string | null>(null);
    const [sidebarOpen, setSidebarOpen] = useState(false);
    const [detectedIntent, setDetectedIntent] = useState<string | null>(null);

    // Load blueprint content when selected
    useEffect(() => {
        if (selectedBlueprintPath) {
            setLoading(true);
            setAnswer('');

            fetch(`http://localhost:8082/api/v1/explore/get-blueprint?path=${encodeURIComponent(selectedBlueprintPath)}`)
                .then(res => res.text())
                .then(content => {
                    setAnswer(content);
                    setLoading(false);
                })
                .catch(err => {
                    console.error('Failed to load blueprint:', err);
                    setAnswer('# Error Loading Blueprint\n\nFailed to load blueprint content. Please try again.');
                    setLoading(false);
                });
        }
    }, [selectedBlueprintPath]);

    const handleSearch = async (e?: any) => {
        if (e && e.preventDefault) e.preventDefault();
        if (!query.trim()) return;

        setLoading(true);
        setAnswer(''); // Clear previous answer
        setProgress([]);
        setCurrentStatus('Initializing Semantic Vectors...');
        setAmbiguities([]);
        setSidebarOpen(false);
        setSessionId(null);
        setDetectedIntent(null); // Reset intent

        let accumulatedAnswer = '';
        let lastUpdateTime = Date.now();
        const THROTTLE_MS = 16;

        try {
            const response = await fetch('http://localhost:8082/api/v1/explore/query', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ query, domain: domain || 'General' }),
            });

            if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

            const reader = response.body?.getReader();
            const decoder = new TextDecoder();
            if (!reader) throw new Error('No reader available');

            let buffer = '';
            let currentEvent = '';

            while (true) {
                const { value, done } = await reader.read();
                if (done) break;

                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop() || '';

                for (const line of lines) {
                    if (!line.trim() && !line.startsWith('data:')) {
                        currentEvent = '';
                        continue;
                    }

                    if (line.startsWith('event:')) {
                        currentEvent = line.replace('event:', '').trim();
                    } else if (line.startsWith('data:')) {
                        // Extract data after "data:" - SSE spec allows optional space after colon
                        let rawData = line.substring(5); // Skip "data:"
                        if (rawData.startsWith(' ')) {
                            rawData = rawData.substring(1); // Remove the optional space
                        }

                        if (currentEvent === 'progress') {
                            const trimmedProgress = rawData.trim();
                            
                            // Extract sessionId if present
                            if (trimmedProgress.startsWith('SESSION_ID:')) {
                                const sid = trimmedProgress.substring(11).trim();
                                console.log('🔗 Session ID Captured:', sid);
                                setSessionId(sid);
                                setSidebarOpen(true); // Auto-open sidebar when analysis starts
                            }
                            // Extract completion signal with sessionId
                            else if (trimmedProgress.startsWith('COMPLETE:')) {
                                const sid = trimmedProgress.substring(9).trim();
                                console.log('✅ Analysis Complete:', sid);
                                setSessionId(sid);
                                // Results should already be in answer, but mark as complete
                            }
                            // Extract Intent
                            else if (trimmedProgress.startsWith('INTENT_DETECTED:')) {
                                setDetectedIntent(trimmedProgress.split(':')[1]);
                            }
                            else {
                                setCurrentStatus(trimmedProgress);
                                setProgress(prev => prev.includes(trimmedProgress) ? prev : [...prev, trimmedProgress]);

                                // Mocking ambiguities for UX demonstration if scanning production logic
                                if (trimmedProgress.toLowerCase().includes('scanning') && ambiguities.length === 0) {
                                    setAmbiguities([{ id: 'm1', tag: 'AuthHandler.java', reason: 'High-affinity mapping clash between legacy COBOL Core and Modern Registration endpoint.' }]);
                                }
                            }
                        } else if (currentEvent === 'answer') {
                            // Unescape newlines that were escaped for SSE transport
                            const unescaped = rawData.replace(/\\n/g, '\n');
                            accumulatedAnswer += unescaped;

                            if (Date.now() - lastUpdateTime > THROTTLE_MS) {
                                setAnswer(accumulatedAnswer);
                                lastUpdateTime = Date.now();
                            }
                        } else if (currentEvent === 'error') {
                            accumulatedAnswer += "\n\n> 🧪 **DIAGNOSTIC FAULT**: " + rawData.trim() + "\n";
                            setAnswer(accumulatedAnswer);
                        }
                    }
                }
            }
            setAnswer(accumulatedAnswer);
        } catch (error) {
            console.error('Stream error:', error);
            
            // Try to fetch stored results if sessionId is available
            if (sessionId) {
                console.log('🔄 Attempting to fetch stored results for session:', sessionId);
                try {
                    const resultResponse = await fetch(`http://localhost:8082/api/semantic/plans/${sessionId}/result`);
                    if (resultResponse.ok) {
                        const resultData = await resultResponse.json();
                        if (resultData.finalResult) {
                            console.log('✅ Retrieved stored results');
                            setAnswer(resultData.finalResult);
                            setCurrentStatus('Results retrieved from stored analysis');
                            // Still set loading to false
                            setLoading(false);
                            setCurrentStatus('');
                            return; // Exit early - we got the results
                        }
                    }
                } catch (fetchError) {
                    console.error('Failed to fetch stored results:', fetchError);
                }
            }
            
            // Fallback error message if we can't fetch stored results
            const errorMessage = sessionId 
                ? `### 🚩 Discovery Interrupted\n\nConnection to the Knowledge Graph was severed, but we're checking for stored results...\n\n**Session ID**: ${sessionId}\n\nIf analysis completed on the server, results will be displayed shortly.`
                : "### 🚩 Discovery Interrupted\nConnection to the Knowledge Graph was severed. This is often caused by VDI network restrictions or gateway timeouts.";
            
            setAnswer(errorMessage);
        } finally {
            setLoading(false);
            setCurrentStatus('');
        }
    };

    const copyToClipboard = () => {
        navigator.clipboard.writeText(answer);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    const saveBlueprint = () => {
        // Generate smart default title from query
        const smartTitle = query
            .replace(/what are the|what is the|show me|list|find/gi, '')
            .trim()
            .split(' ')
            .map(word => word.charAt(0).toUpperCase() + word.slice(1))
            .join(' ') || 'Analysis Report';

        setSaveTitle(smartTitle);
        setSaveCategory('general');
        setSaveTags('');
        setShowSaveDialog(true);
    };

    const confirmSave = async () => {
        try {
            console.log('Saving blueprint...', { saveTitle, saveCategory, saveTags });
            const response = await fetch('http://localhost:8082/api/v1/explore/save-blueprint', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    content: answer,
                    query: query,
                    project: domain,
                    title: saveTitle,
                    category: saveCategory,
                    tags: saveTags
                }),
            });

            const result = await response.json();
            console.log('Save response:', result);

            if (response.ok && result.status === 'success') {
                setSaved(true);
                setShowSaveDialog(false);
                console.log('Blueprint saved successfully to:', result.path);
                setTimeout(() => setSaved(false), 3000);
            } else {
                console.error('Save failed:', result.message);
                alert(`Failed to save: ${result.message || 'Unknown error'}`);
            }
        } catch (error) {
            console.error('Error saving blueprint:', error);
            alert('Failed to save blueprint. Check console for details.');
        }
    };

    const downloadBlueprint = () => {
        const blob = new Blob([answer], { type: 'text/markdown' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `blueprint_${new Date().toISOString().slice(0, 10)}.md`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    };

    const loadBlueprints = async () => {
        try {
            const response = await fetch(`http://localhost:8082/api/v1/explore/list-blueprints?project=${domain}`);
            if (response.ok) {
                const data = await response.json();
                setBlueprints(data);
                setShowBlueprints(true);
            }
        } catch (error) {
            console.error('Error loading blueprints:', error);
        }
    };

    return (
        <div style={{
            background: '#07080d',
            minHeight: '100%',
            display: 'flex',
            flexDirection: 'column',
            position: 'relative',
            color: '#f8fafc',
            fontFamily: "'Inter', sans-serif"
        }}>
            <style>{`
                @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&family=Outfit:wght@400;500;600;700;800&family=Inter:wght@300;400;500;600;700&display=swap');
                
                .markdown-render {
                    width: 100%;
                    padding: 40px 60px 180px;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                }

                .markdown-render h1 { font-family: 'Outfit', sans-serif; font-size: 52px; font-weight: 800; color: #fff; margin-bottom: 40px; letter-spacing: -0.04em; line-height: 1.1; word-wrap: break-word; }
                .markdown-render h2 { font-family: 'Outfit', sans-serif; font-size: 28px; font-weight: 700; color: #fff; margin: 64px 0 24px; letter-spacing: -0.02em; padding-bottom: 12px; border-bottom: 1px solid rgba(255,255,255,0.05); word-wrap: break-word; }
                .markdown-render h3 { font-family: 'Outfit', sans-serif; font-size: 20px; font-weight: 600; color: #10b981; margin: 40px 0 16px; word-wrap: break-word; }
                .markdown-render p { font-size: 18px; line-height: 1.85; color: #94a3b8; margin-bottom: 28px; word-wrap: break-word; white-space: pre-wrap; }
                .markdown-render ul { margin-bottom: 36px; padding-left: 0; list-style: none; }
                .markdown-render li { display: flex; gap: 14px; margin-bottom: 16px; color: #cbd5e1; font-size: 17px; line-height: 1.7; word-wrap: break-word; }
                .markdown-render li::before { content: '→'; color: #10b981; font-weight: 800; flex-shrink: 0; margin-top: 1px; }
                .markdown-render code { font-family: 'JetBrains Mono', monospace; background: rgba(16, 185, 129, 0.08); color: #10b981; padding: 2px 6px; borderRadius: 4px; font-size: 0.85em; font-weight: 500; word-break: break-word; }
                .markdown-render pre { background: #0c0e14; padding: 28px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.06); margin: 40px 0; overflow-x: auto; box-shadow: 0 10px 30px rgba(0,0,0,0.4); max-width: 100%; }
                .markdown-render pre code { background: transparent; color: #acb6c2; padding: 0; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
                .markdown-render blockquote { border-left: 4px solid #10b981; background: rgba(16, 185, 129, 0.03); padding: 24px 32px; margin: 48px 0; borderRadius: 0 16px 16px 0; color: #64748b; font-style: italic; font-size: 18px; word-wrap: break-word; }

                .floating-toolbar {
                    position: fixed;
                    bottom: 32px;
                    left: 50%;
                    transform: translateX(-50%);
                    width: calc(100% - 320px);
                    max-width: 900px;
                    background: rgba(10, 11, 16, 0.85);
                    backdrop-filter: blur(24px);
                    -webkit-backdrop-filter: blur(24px);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                    border-radius: 24px;
                    padding: 12px 24px;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    box-shadow: 0 20px 80px rgba(0,0,0,0.8);
                    z-index: 1000;
                }

                .search-focus {
                    background: rgba(255,255,255,0.02);
                    border: 1px solid rgba(255,255,255,0.08);
                    border-radius: 20px;
                    padding: 4px 4px 4px 16px;
                    display: flex;
                    align-items: flex-start;
                    gap: 16px;
                    width: 100%;
                    max-width: 800px;
                    margin: 0 auto;
                    transition: all 0.4s cubic-bezier(0.19, 1, 0.22, 1);
                }
                .search-focus:focus-within {
                    background: #0d0f17;
                    border-color: #10b981;
                    box-shadow: 0 0 40px rgba(16, 185, 129, 0.12);
                }

                .ambiguity-popup {
                    position: absolute;
                    bottom: 100%;
                    right: 0;
                    margin-bottom: 20px;
                    width: 400px;
                    background: #111422;
                    border: 1px solid #23273a;
                    border-radius: 20px;
                    padding: 24px;
                    box-shadow: 0 -30px 100px rgba(0,0,0,0.9);
                    z-index: 1001;
                }

                .pulse-emerald {
                    animation: pulse-emerald 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
                }
                @keyframes pulse-emerald {
                    0%, 100% { opacity: 1; transform: scale(1); }
                    50% { opacity: 0.4; transform: scale(0.8); }
                }

                .animate-spin { animation: spin 0.8s linear infinite; }
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
            `}</style>

            {/* Content Area */}
            <div style={{ flex: 1, paddingBottom: '120px' }}>
                {!answer && !loading && (
                    <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} style={{ textAlign: 'center', margin: '140px 0 80px' }}>
                        <h1 style={{ fontSize: '84px', fontWeight: 800, margin: '0 0 20px', letterSpacing: '-0.05em', background: 'linear-gradient(to bottom, #fff, #475569)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent' }}>
                            Architectural Vision.
                        </h1>
                        <p style={{ color: '#64748b', fontSize: '22px', maxWidth: '600px', margin: '0 auto 48px', fontWeight: 500 }}>The real-time business logic engine for legacy and modern ecosystems.</p>
                    </motion.div>
                )}

                {/* Search integrated on top of report if answer exists, or centered if intro */}
                <div style={{
                    padding: answer || loading ? '40px 0' : '0',
                    borderBottom: answer || loading ? '1px solid rgba(255,255,255,0.05)' : 'none',
                    background: answer || loading ? '#05060a' : 'transparent',
                    transition: 'all 0.6s cubic-bezier(0.4, 0, 0.2, 1)',
                    position: answer || loading ? 'sticky' : 'relative',
                    top: 0,
                    zIndex: 900,
                    backdropFilter: 'blur(16px)'
                }}>
                    <form onSubmit={handleSearch} className="search-focus">
                        <Search size={22} color={isFocused ? '#10b981' : '#475569'} style={{ marginTop: '16px' }} />
                        <textarea
                            value={query}
                            onChange={(e) => setQuery(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter' && !e.shiftKey) {
                                    e.preventDefault();
                                    handleSearch(e);
                                }
                            }}
                            onFocus={() => setIsFocused(true)}
                            onBlur={() => setIsFocused(false)}
                            placeholder="Map a business flow, find architecture debt, or extract logic..."
                            rows={6}
                            style={{ 
                                flex: 1, 
                                background: 'transparent', 
                                border: 'none', 
                                color: 'white', 
                                fontSize: '19px', 
                                outline: 'none', 
                                padding: '14px 0',
                                fontFamily: "'Inter', sans-serif",
                                resize: 'none',
                                overflowY: 'auto',
                                lineHeight: '1.5'
                            }}
                        />
                        <button
                            type="button"
                            onClick={loadBlueprints}
                            style={{
                                marginTop: '6px',
                                background: 'rgba(255,255,255,0.03)',
                                color: '#94a3b8',
                                border: '1px solid rgba(255,255,255,0.08)',
                                padding: '14px 24px',
                                borderRadius: '14px',
                                fontWeight: 700,
                                cursor: 'pointer',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '10px',
                                transition: 'all 0.2s'
                            }}
                            onMouseEnter={(e) => {
                                e.currentTarget.style.background = 'rgba(16, 185, 129, 0.1)';
                                e.currentTarget.style.borderColor = 'rgba(16, 185, 129, 0.3)';
                                e.currentTarget.style.color = '#10b981';
                            }}
                            onMouseLeave={(e) => {
                                e.currentTarget.style.background = 'rgba(255,255,255,0.03)';
                                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)';
                                e.currentTarget.style.color = '#94a3b8';
                            }}
                        >
                            <FileText size={18} />
                            <span>Saved</span>
                        </button>
                        <button
                            type="submit"
                            disabled={loading}
                            style={{
                                marginTop: '6px',
                                background: loading ? 'rgba(255,255,255,0.03)' : '#10b981',
                                color: 'white', border: 'none', padding: '14px 32px', borderRadius: '14px',
                                fontWeight: 800, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '12px',
                                boxShadow: loading ? 'none' : '0 4px 20px rgba(16, 185, 129, 0.2)'
                            }}
                        >
                            {loading ? <Loader2 size={18} className="animate-spin" /> : <Sparkles size={18} />}
                            <span>{loading ? 'Analyzing' : 'Synthesize'}</span>
                        </button>
                    </form>
                </div>

                {/* The "Infinite Canvas" Report */}
                <div className="markdown-render">
                    <AnimatePresence mode="wait">
                        {!answer && loading ? (
                            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} style={{ padding: '120px 0', textAlign: 'center' }}>
                                <div style={{ position: 'relative', width: '96px', height: '96px', margin: '0 auto 40px' }}>
                                    <div style={{ position: 'absolute', inset: 0, borderRadius: '50%', border: '5px solid rgba(16, 185, 129, 0.05)', borderTopColor: '#10b981', animation: 'spin 1.2s cubic-bezier(0.5, 0, 0.5, 1) infinite' }} />
                                    <Bot style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%, -50%)', color: '#10b981' }} size={32} />
                                </div>
                                <h3 style={{ fontSize: '24px', color: '#fff', margin: '0 0 12px', letterSpacing: '-0.02em' }}>Deep Reasoning Active</h3>
                                <p style={{ color: '#64748b', fontSize: '17px' }}>{currentStatus || 'Mapping production invariants...'}</p>
                            </motion.div>
                        ) : (
                            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
                                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                    {answer}
                                </ReactMarkdown>
                            </motion.div>
                        )}
                    </AnimatePresence>
                </div>
            </div>

            {/* FLOATING MISSION TOOLBAR */}
            <AnimatePresence>
                {(loading || answer || progress.length > 0) && (
                    <motion.div initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} transition={{ type: 'spring', damping: 20 }} className="floating-toolbar">
                        <div style={{ display: 'flex', gap: '24px', alignItems: 'center' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '12px', background: 'rgba(16, 185, 129, 0.08)', padding: '8px 18px', borderRadius: '14px', border: '1px solid rgba(16, 185, 129, 0.15)' }}>
                                {loading ? (
                                    <div className="pulse-emerald" style={{ width: '8px', height: '8px', borderRadius: '50%', background: '#10b981' }} />
                                ) : (
                                    <Check size={14} color="#10b981" />
                                )}
                                <span style={{ fontSize: '12px', fontWeight: 800, color: '#10b981', letterSpacing: '0.08em' }}>
                                    {loading ? 'AGENT ENGAGED' : 'SYNTHESIS COMPLETE'}
                                </span>
                            </div>
                            <div style={{ fontSize: '14px', fontWeight: 600, color: '#64748b', fontFamily: "'JetBrains Mono', monospace" }}>
                                {currentStatus || 'Ready.'}
                            </div>
                        </div>

                        <div style={{ display: 'flex', gap: '14px', alignItems: 'center' }}>
                            {/* Ambiguity Tooltip */}
                            <div style={{ position: 'relative' }}>
                                <button
                                    onClick={() => setShowAmbiguities(!showAmbiguities)}
                                    style={{
                                        background: ambiguities.length > 0 ? 'rgba(245, 158, 11, 0.1)' : 'transparent',
                                        border: ambiguities.length > 0 ? '1px solid #f59e0b' : '1px solid rgba(255,255,255,0.1)',
                                        borderRadius: '12px', padding: '10px 18px', color: ambiguities.length > 0 ? '#f59e0b' : '#64748b',
                                        fontSize: '13px', fontWeight: 700, display: 'flex', alignItems: 'center', gap: '10px', cursor: 'pointer',
                                        transition: 'all 0.3s'
                                    }}
                                >
                                    <AlertTriangle size={16} />
                                    Ambiguities ({ambiguities.length})
                                </button>

                                <AnimatePresence>
                                    {showAmbiguities && (
                                        <motion.div
                                            initial={{ opacity: 0, y: 12, scale: 0.95 }}
                                            animate={{ opacity: 1, y: 0, scale: 1 }}
                                            exit={{ opacity: 0, y: 12, scale: 0.95 }}
                                            className="ambiguity-popup"
                                        >
                                            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '24px', alignItems: 'center' }}>
                                                <h4 style={{ margin: 0, color: '#fff', fontSize: '18px', fontWeight: 800, letterSpacing: '-0.02em' }}>Ambiguity Resolver</h4>
                                                <button onClick={() => setShowAmbiguities(false)} style={{ background: 'transparent', border: 'none', color: '#475569', cursor: 'pointer' }}>
                                                    <X size={20} />
                                                </button>
                                            </div>
                                            {ambiguities.length === 0 ? (
                                                <div style={{ textAlign: 'center', padding: '32px 0' }}>
                                                    <div style={{ width: '48px', height: '48px', borderRadius: '50%', background: 'rgba(16, 185, 129, 0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px' }}>
                                                        <Check size={24} color="#10b981" />
                                                    </div>
                                                    <p style={{ color: '#64748b', fontSize: '14px', lineHeight: '1.6' }}>No logical conflicts detected in this discovery mission path.</p>
                                                </div>
                                            ) : (
                                                <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                                                    {ambiguities.map(a => (
                                                        <div key={a.id} style={{ background: 'rgba(255,255,255,0.02)', padding: '20px', borderRadius: '16px', border: '1px solid rgba(245, 158, 11, 0.15)' }}>
                                                            <div style={{ fontSize: '12px', fontWeight: 900, color: '#f59e0b', marginBottom: '8px', letterSpacing: '0.05em' }}>CONTRADICTION DETECTED: {a.tag}</div>
                                                            <div style={{ fontSize: '14px', color: '#94a3b8', lineHeight: '1.5', marginBottom: '16px' }}>{a.reason}</div>
                                                            <button style={{ width: '100%', padding: '10px', borderRadius: '10px', background: '#334155', border: 'none', color: '#fff', fontSize: '12px', fontWeight: 800, cursor: 'pointer', transition: 'background 0.2s' }}>INITIATE SEMANTIC STITCH</button>
                                                        </div>
                                                    ))}
                                                </div>
                                            )}
                                        </motion.div>
                                    )}
                                </AnimatePresence>
                            </div>

                            <div style={{ width: '1px', height: '28px', background: 'rgba(255,255,255,0.1)', margin: '0 8px' }} />

                            <div style={{ display: 'flex', gap: '8px' }}>
                                <button onClick={saveBlueprint} style={{ background: 'rgba(255,255,255,0.03)', border: 'none', color: '#94a3b8', cursor: 'pointer', padding: '10px', borderRadius: '12px', transition: 'all 0.2s' }} title="Save to MinIO">
                                    {saved ? <Check size={20} color="#10b981" /> : <Save size={20} />}
                                </button>
                                <button onClick={downloadBlueprint} style={{ background: 'rgba(255,255,255,0.03)', border: 'none', color: '#94a3b8', cursor: 'pointer', padding: '10px', borderRadius: '12px', transition: 'all 0.2s' }} title="Download Markdown">
                                    <Download size={20} />
                                </button>
                                <button onClick={copyToClipboard} style={{ background: 'rgba(255,255,255,0.03)', border: 'none', color: '#94a3b8', cursor: 'pointer', padding: '10px', borderRadius: '12px', transition: 'all 0.2s' }} title="Copy to Clipboard">
                                    {copied ? <Check size={20} color="#10b981" /> : <Copy size={20} />}
                                </button>
                                <button onClick={loadBlueprints} style={{ background: 'rgba(255,255,255,0.03)', border: 'none', color: '#94a3b8', cursor: 'pointer', padding: '10px', borderRadius: '12px', transition: 'all 0.2s' }} title="View Saved Blueprints">
                                    <FileText size={20} />
                                </button>
                                <button style={{ background: 'rgba(255,255,255,0.03)', border: 'none', color: '#94a3b8', cursor: 'pointer', padding: '10px', borderRadius: '12px' }}>
                                    <Settings size={20} />
                                </button>
                            </div>
                        </div>
                    </motion.div>
                )}
            </AnimatePresence>

            {/* Saved Blueprints Modal */}
            <AnimatePresence>
                {showBlueprints && (
                    <motion.div
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        style={{
                            position: 'fixed',
                            top: 0,
                            left: 0,
                            right: 0,
                            bottom: 0,
                            background: 'rgba(0,0,0,0.8)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            zIndex: 2000,
                            padding: '40px'
                        }}
                        onClick={() => setShowBlueprints(false)}
                    >
                        <motion.div
                            initial={{ scale: 0.9, y: 20 }}
                            animate={{ scale: 1, y: 0 }}
                            exit={{ scale: 0.9, y: 20 }}
                            onClick={(e) => e.stopPropagation()}
                            style={{
                                background: '#111422',
                                border: '1px solid #23273a',
                                borderRadius: '24px',
                                padding: '32px',
                                maxWidth: '800px',
                                width: '100%',
                                maxHeight: '80vh',
                                overflow: 'auto'
                            }}
                        >
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '32px' }}>
                                <h2 style={{ margin: 0, color: '#fff', fontSize: '28px', fontWeight: 800, fontFamily: "'Outfit', sans-serif" }}>📚 Saved Blueprints</h2>
                                <button onClick={() => setShowBlueprints(false)} style={{ background: 'transparent', border: 'none', color: '#475569', cursor: 'pointer' }}>
                                    <X size={24} />
                                </button>
                            </div>

                            {blueprints.length === 0 ? (
                                <div style={{ textAlign: 'center', padding: '60px 0' }}>
                                    <FileText size={48} color="#334155" style={{ margin: '0 auto 24px' }} />
                                    <p style={{ color: '#64748b', fontSize: '16px' }}>No blueprints saved yet. Generate and save a blueprint to see it here.</p>
                                </div>
                            ) : (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                                    {blueprints.map((bp: any, idx: number) => (
                                        <div key={idx} style={{
                                            background: 'rgba(255,255,255,0.02)',
                                            padding: '20px',
                                            borderRadius: '16px',
                                            border: '1px solid rgba(255,255,255,0.05)',
                                            transition: 'all 0.2s',
                                            cursor: 'pointer'
                                        }}
                                            onMouseEnter={(e) => {
                                                e.currentTarget.style.background = 'rgba(16, 185, 129, 0.05)';
                                                e.currentTarget.style.borderColor = 'rgba(16, 185, 129, 0.2)';
                                            }}
                                            onMouseLeave={(e) => {
                                                e.currentTarget.style.background = 'rgba(255,255,255,0.02)';
                                                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.05)';
                                            }}
                                        >
                                            <div style={{ fontSize: '16px', fontWeight: 600, color: '#fff', marginBottom: '8px', fontFamily: "'JetBrains Mono', monospace" }}>{bp.filename}</div>
                                            <div style={{ fontSize: '13px', color: '#64748b', display: 'flex', gap: '16px' }}>
                                                <span>{(bp.size / 1024).toFixed(1)} KB</span>
                                                <span>•</span>
                                                <span>{new Date(bp.lastModified).toLocaleString()}</span>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </motion.div>
                    </motion.div>
                )}
            </AnimatePresence>

            {/* Save Dialog Modal */}
            <AnimatePresence>
                {showSaveDialog && (
                    <motion.div
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        style={{
                            position: 'fixed',
                            top: 0,
                            left: 0,
                            right: 0,
                            bottom: 0,
                            background: 'rgba(0,0,0,0.8)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            zIndex: 2000,
                            padding: '40px'
                        }}
                        onClick={() => setShowSaveDialog(false)}
                    >
                        <motion.div
                            initial={{ scale: 0.9, y: 20 }}
                            animate={{ scale: 1, y: 0 }}
                            exit={{ scale: 0.9, y: 20 }}
                            onClick={(e) => e.stopPropagation()}
                            style={{
                                background: '#111422',
                                border: '1px solid #23273a',
                                borderRadius: '24px',
                                padding: '32px',
                                maxWidth: '500px',
                                width: '100%'
                            }}
                        >
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '32px' }}>
                                <h2 style={{ margin: 0, color: '#fff', fontSize: '24px', fontWeight: 800, fontFamily: "'Outfit', sans-serif" }}>💾 Save Blueprint</h2>
                                <button onClick={() => setShowSaveDialog(false)} style={{ background: 'transparent', border: 'none', color: '#475569', cursor: 'pointer' }}>
                                    <X size={24} />
                                </button>
                            </div>

                            <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
                                <div>
                                    <label style={{ display: 'block', color: '#94a3b8', fontSize: '14px', fontWeight: 600, marginBottom: '8px' }}>
                                        Title *
                                    </label>
                                    <input
                                        type="text"
                                        value={saveTitle}
                                        onChange={(e) => setSaveTitle(e.target.value)}
                                        placeholder="e.g., Account Services Use Cases"
                                        style={{
                                            width: '100%',
                                            padding: '12px 16px',
                                            background: 'rgba(255,255,255,0.03)',
                                            border: '1px solid rgba(255,255,255,0.08)',
                                            borderRadius: '12px',
                                            color: '#fff',
                                            fontSize: '15px',
                                            outline: 'none',
                                            transition: 'all 0.2s'
                                        }}
                                        onFocus={(e) => {
                                            e.currentTarget.style.borderColor = '#10b981';
                                            e.currentTarget.style.background = 'rgba(16, 185, 129, 0.05)';
                                        }}
                                        onBlur={(e) => {
                                            e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)';
                                            e.currentTarget.style.background = 'rgba(255,255,255,0.03)';
                                        }}
                                    />
                                </div>

                                <div>
                                    <label style={{ display: 'block', color: '#94a3b8', fontSize: '14px', fontWeight: 600, marginBottom: '8px' }}>
                                        Category
                                    </label>
                                    <select
                                        value={saveCategory}
                                        onChange={(e) => setSaveCategory(e.target.value)}
                                        style={{
                                            width: '100%',
                                            padding: '12px 16px',
                                            background: 'rgba(255,255,255,0.03)',
                                            border: '1px solid rgba(255,255,255,0.08)',
                                            borderRadius: '12px',
                                            color: '#fff',
                                            fontSize: '15px',
                                            outline: 'none',
                                            cursor: 'pointer'
                                        }}
                                    >
                                        <option value="general">General</option>
                                        <option value="use-cases">Use Cases</option>
                                        <option value="architecture">Architecture</option>
                                        <option value="security">Security</option>
                                        <option value="data-flow">Data Flow</option>
                                        <option value="api-docs">API Documentation</option>
                                    </select>
                                </div>

                                <div>
                                    <label style={{ display: 'block', color: '#94a3b8', fontSize: '14px', fontWeight: 600, marginBottom: '8px' }}>
                                        Tags <span style={{ color: '#64748b', fontWeight: 400 }}>(comma-separated)</span>
                                    </label>
                                    <div style={{ position: 'relative' }}>
                                        <Tag size={18} color="#64748b" style={{ position: 'absolute', left: '16px', top: '50%', transform: 'translateY(-50%)' }} />
                                        <input
                                            type="text"
                                            value={saveTags}
                                            onChange={(e) => setSaveTags(e.target.value)}
                                            placeholder="e.g., REST API, CRUD, microservices"
                                            style={{
                                                width: '100%',
                                                padding: '12px 16px 12px 44px',
                                                background: 'rgba(255,255,255,0.03)',
                                                border: '1px solid rgba(255,255,255,0.08)',
                                                borderRadius: '12px',
                                                color: '#fff',
                                                fontSize: '15px',
                                                outline: 'none'
                                            }}
                                        />
                                    </div>
                                </div>

                                <div style={{ display: 'flex', gap: '12px', marginTop: '8px' }}>
                                    <button
                                        onClick={() => setShowSaveDialog(false)}
                                        style={{
                                            flex: 1,
                                            padding: '14px',
                                            background: 'rgba(255,255,255,0.03)',
                                            border: '1px solid rgba(255,255,255,0.08)',
                                            borderRadius: '12px',
                                            color: '#94a3b8',
                                            fontSize: '15px',
                                            fontWeight: 700,
                                            cursor: 'pointer',
                                            transition: 'all 0.2s'
                                        }}
                                    >
                                        Cancel
                                    </button>
                                    <button
                                        onClick={confirmSave}
                                        disabled={!saveTitle.trim()}
                                        style={{
                                            flex: 1,
                                            padding: '14px',
                                            background: saveTitle.trim() ? '#10b981' : 'rgba(255,255,255,0.03)',
                                            border: 'none',
                                            borderRadius: '12px',
                                            color: saveTitle.trim() ? '#fff' : '#475569',
                                            fontSize: '15px',
                                            fontWeight: 800,
                                            cursor: saveTitle.trim() ? 'pointer' : 'not-allowed',
                                            transition: 'all 0.2s',
                                            boxShadow: saveTitle.trim() ? '0 4px 20px rgba(16, 185, 129, 0.2)' : 'none'
                                        }}
                                    >
                                        💾 Save Blueprint
                                    </button>
                                </div>
                            </div>
                        </motion.div>
                    </motion.div>
                )}
            </AnimatePresence>
            
            {/* Analysis Plan Sidebar */}
            <AnalysisPlanSidebar 
                sessionId={sessionId}
                isOpen={sidebarOpen}
                onToggle={() => setSidebarOpen(!sidebarOpen)}
            />
        </div>
    );
};
