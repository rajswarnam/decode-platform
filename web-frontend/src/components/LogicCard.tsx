import { useState } from 'react';
import { ShieldCheck, ShieldAlert, Code, ChevronDown, ChevronUp, FileSearch } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

interface Props {
    mapping: {
        attributeTag: string;
        businessDescription?: string;
        confidenceScore: number;
        mappingStrategy: string;
        externalLayerRef: string;
        rawDataPath: string;
        sourceSnippet?: string;
    };
}

export const LogicCard = ({ mapping }: Props) => {
    const [expanded, setExpanded] = useState(false);
    const isHighTrust = mapping.confidenceScore >= 0.8;

    return (
        <div className="glass-panel" style={{ padding: '16px', marginBottom: '16px', transition: 'all 0.3s' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                        {isHighTrust ? <ShieldCheck size={18} color="#10b981" /> : <ShieldAlert size={18} color="#f59e0b" />}
                        <span style={{ fontWeight: 600, fontSize: '14px', color: '#f8fafc' }}>{mapping.attributeTag}</span>
                        <span style={{
                            fontSize: '10px',
                            padding: '2px 6px',
                            borderRadius: '4px',
                            background: isHighTrust ? 'rgba(16,185,129,0.1)' : 'rgba(245,158,11,0.1)',
                            color: isHighTrust ? '#10b981' : '#f59e0b',
                            border: `1px solid ${isHighTrust ? 'rgba(16,185,129,0.2)' : 'rgba(245,158,11,0.2)'}`
                        }}>
                            {mapping.mappingStrategy}
                        </span>
                    </div>
                    <p style={{ color: '#94a3b8', fontSize: '13px', lineHeight: '1.4' }}>
                        {mapping.businessDescription || `Business logic mapping for ${mapping.attributeTag} across system boundaries.`}
                    </p>
                </div>
                <div style={{ textAlign: 'right' }}>
                    <div style={{ fontSize: '18px', fontWeight: 'bold', color: isHighTrust ? '#10b981' : '#f59e0b' }}>
                        {(mapping.confidenceScore * 100).toFixed(0)}%
                    </div>
                    <div style={{ fontSize: '9px', color: '#64748b', textTransform: 'uppercase' }}>Confidence</div>
                </div>
            </div>

            <div style={{ marginTop: '12px', display: 'flex', gap: '16px', fontSize: '11px', color: '#64748b' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                    <Code size={12} /> {mapping.externalLayerRef || 'N/A'}
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                    <FileSearch size={12} /> {mapping.rawDataPath || 'No Path'}
                </div>
            </div>

            <button
                onClick={() => setExpanded(!expanded)}
                style={{
                    marginTop: '16px',
                    width: '100%',
                    background: 'rgba(255,255,255,0.03)',
                    border: '1px solid var(--border-light)',
                    borderRadius: '8px',
                    padding: '8px',
                    color: '#94a3b8',
                    fontSize: '12px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: '8px'
                }}
            >
                {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                {expanded ? 'Hide Evidence' : 'View Source Evidence'}
            </button>

            <AnimatePresence>
                {expanded && (
                    <motion.div
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: 'auto', opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        style={{ overflow: 'hidden' }}
                    >
                        <div style={{
                            marginTop: '12px',
                            background: '#0a0b10',
                            padding: '12px',
                            borderRadius: '8px',
                            fontFamily: 'monospace',
                            fontSize: '12px',
                            color: '#10b981',
                            border: '1px solid rgba(16,185,129,0.1)',
                            whiteSpace: 'pre-wrap'
                        }}>
                            {mapping.sourceSnippet || `// Source Snippet from MinIO\n// File: ${mapping.rawDataPath}\n\n[DETERMINISTIC EVIDENCE NOT LOADED]`}
                        </div>
                    </motion.div>
                )}
            </AnimatePresence>
        </div>
    );
};
