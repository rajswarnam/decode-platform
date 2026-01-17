import { useState } from 'react';
import { motion } from 'framer-motion';
import { X, Loader2, RefreshCw, Info } from 'lucide-react';

interface RefineDialogProps {
    blueprintPath: string;
    project: string;
    onClose: () => void;
    onRefineComplete: (result: RefineResult, updateExisting: boolean) => void;
}

interface RefineResult {
    status: string;
    newBlueprintPath?: string;
    version?: number;
    codeChanges?: {
        detected: boolean;
        newSymbols: number;
        modifiedSymbols: number;
        deletedSymbols: number;
        summary?: string;
    };
    message?: string;
}

export const RefineDialog = ({ blueprintPath, project, onClose, onRefineComplete }: RefineDialogProps) => {
    const [refinementPrompt, setRefinementPrompt] = useState('');
    const [updateExisting, setUpdateExisting] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleRefine = async () => {
        setLoading(true);
        setError(null);
        try {
            const response = await fetch('http://localhost:8082/api/v1/explore/refine-blueprint', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    blueprintPath,
                    refinementPrompt,
                    project,
                    updateExisting
                })
            });

            const result: RefineResult = await response.json();

            if (result.status === 'success') {
                onRefineComplete(result, updateExisting);
                onClose();
            } else {
                console.error('Refinement failed:', result.message);
                setError(result.message || 'Refinement failed. Please try again.');
            }
        } catch (error) {
            console.error('Refinement failed:', error);
            setError('Refinement failed. Please try again.');
        } finally {
            setLoading(false);
        }
    };

    return (
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
            onClick={onClose}
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
                    maxWidth: '600px',
                    width: '100%',
                    boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.5)'
                }}
            >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                        <div style={{ padding: '10px', background: 'rgba(16, 185, 129, 0.1)', borderRadius: '12px', border: '1px solid rgba(16, 185, 129, 0.2)' }}>
                            <RefreshCw size={24} className={loading ? "animate-spin" : ""} color="#10b981" />
                        </div>
                        <div>
                            <h2 style={{ margin: 0, color: '#fff', fontSize: '20px', fontWeight: 800 }}>
                                Refine Blueprint
                            </h2>
                            <p style={{ margin: '4px 0 0 0', color: '#94a3b8', fontSize: '13px' }}>
                                Add details or update context based on latest code
                            </p>
                        </div>
                    </div>
                    <button onClick={onClose} style={{ background: 'transparent', border: 'none', color: '#475569', cursor: 'pointer' }}>
                        <X size={24} />
                    </button>
                </div>

                <div style={{ marginBottom: '24px' }}>
                    <label style={{ display: 'block', color: '#e2e8f0', fontSize: '14px', fontWeight: 600, marginBottom: '8px' }}>
                        What additional details do you want to add?
                    </label>
                    <textarea
                        value={refinementPrompt}
                        onChange={(e) => setRefinementPrompt(e.target.value)}
                        placeholder="e.g., Add payload schemas for external service calls, or update the security section..."
                        rows={4}
                        style={{
                            width: '100%',
                            padding: '12px 16px',
                            background: 'rgba(255,255,255,0.03)',
                            border: '1px solid rgba(255,255,255,0.08)',
                            borderRadius: '12px',
                            color: '#fff',
                            fontSize: '15px',
                            fontWeight: 500,
                            outline: 'none',
                            resize: 'vertical',
                            fontFamily: 'Inter, sans-serif'
                        }}
                    />
                </div>

                <div style={{ marginBottom: '24px', padding: '16px', background: 'rgba(16, 185, 129, 0.05)', borderRadius: '12px', border: '1px solid rgba(16, 185, 129, 0.2)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' }}>
                        <Info size={16} color="#10b981" />
                        <span style={{ color: '#10b981', fontSize: '13px', fontWeight: 700 }}>Smart Refinement Active</span>
                    </div>
                    <p style={{ color: '#94a3b8', fontSize: '13px', margin: 0, lineHeight: '1.5' }}>
                        The system will automatically detect code changes since this blueprint was created and incorporate them into the refined version.
                    </p>
                </div>

                <div style={{ marginBottom: '32px' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '12px', cursor: 'pointer', padding: '12px', borderRadius: '12px', background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.05)' }}>
                        <input
                            type="checkbox"
                            checked={updateExisting}
                            onChange={(e) => setUpdateExisting(e.target.checked)}
                            style={{ cursor: 'pointer', width: '16px', height: '16px', accentColor: '#10b981' }}
                        />
                        <div>
                            <span style={{ color: '#e2e8f0', fontSize: '14px', fontWeight: 600, display: 'block' }}>
                                Overwrite existing blueprint
                            </span>
                            <span style={{ color: '#64748b', fontSize: '12px' }}>
                                If unchecked, a new version (v2, v3...) will be created
                            </span>
                        </div>
                    </label>
                </div>

                {error && (
                    <div style={{ marginBottom: '16px', color: '#f87171', fontSize: '12px' }}>
                        {error}
                    </div>
                )}

                <div style={{ display: 'flex', gap: '12px' }}>
                    <button
                        onClick={onClose}
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
                        onClick={handleRefine}
                        disabled={!refinementPrompt.trim() || loading}
                        style={{
                            flex: 1,
                            padding: '14px',
                            background: refinementPrompt.trim() && !loading ? '#10b981' : 'rgba(255,255,255,0.03)',
                            border: 'none',
                            borderRadius: '12px',
                            color: refinementPrompt.trim() && !loading ? '#fff' : '#475569',
                            fontSize: '15px',
                            fontWeight: 800,
                            cursor: refinementPrompt.trim() && !loading ? 'pointer' : 'not-allowed',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: '8px',
                            boxShadow: refinementPrompt.trim() && !loading ? '0 4px 12px rgba(16, 185, 129, 0.2)' : 'none',
                            transition: 'all 0.2s'
                        }}
                    >
                        {loading ? (
                            <>
                                <Loader2 size={18} className="animate-spin" />
                                Refining...
                            </>
                        ) : (
                            <>
                                <RefreshCw size={18} />
                                Start Refinement
                            </>
                        )}
                    </button>
                </div>
            </motion.div>
        </motion.div>
    );
};
