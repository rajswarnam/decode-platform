import { ShieldAlert, CheckCircle2 } from 'lucide-react';

interface Candidate {
    id: string;
    name: string;
    path: string;
    type: string;
    affinity: number;
}

interface Props {
    tag: string;
    candidates: Candidate[];
    onSelect: (id: string) => void;
}

export const AmbiguityResolver = ({ tag, candidates, onSelect }: Props) => {
    return (
        <div className="glass-panel" style={{ padding: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '20px' }}>
                <div style={{ padding: '8px', borderRadius: '50%', background: 'rgba(245,158,11,0.1)' }}>
                    <ShieldAlert size={20} color="#f59e0b" />
                </div>
                <div>
                    <h3 style={{ fontSize: '16px', fontWeight: 600 }}>Ambiguity Detected: {tag}</h3>
                    <p style={{ fontSize: '12px', color: '#94a3b8' }}>Multiple candidates found. Select the correct logical match.</p>
                </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {candidates.map((c) => (
                    <div
                        key={c.id}
                        className="glass-panel"
                        style={{
                            padding: '12px',
                            background: 'rgba(255,255,255,0.02)',
                            cursor: 'pointer',
                            border: '1px solid rgba(255,255,255,0.05)',
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center'
                        }}
                        onClick={() => onSelect(c.id)}
                    >
                        <div>
                            <div style={{ fontWeight: 600, fontSize: '13px', color: '#f8fafc' }}>{c.name}</div>
                            <div style={{ fontSize: '10px', color: '#64748b' }}>{c.path}</div>
                            <div style={{ display: 'flex', gap: '8px', marginTop: '4px' }}>
                                <span style={{ fontSize: '9px', background: 'rgba(255,255,255,0.05)', padding: '2px 4px', borderRadius: '3px' }}>
                                    TYPE: {c.type}
                                </span>
                            </div>
                        </div>
                        <div style={{ textAlign: 'right' }}>
                            <div style={{ fontSize: '14px', fontWeight: 'bold', color: c.affinity >= 0.8 ? '#10b981' : '#f59e0b' }}>
                                {(c.affinity * 100).toFixed(0)}%
                            </div>
                            <div style={{ fontSize: '8px', color: '#64748b' }}>AFFINITY</div>
                            <button
                                style={{
                                    marginTop: '8px',
                                    padding: '4px 8px',
                                    fontSize: '10px',
                                    background: 'var(--primary)',
                                    color: 'white',
                                    border: 'none',
                                    borderRadius: '4px',
                                    display: 'flex',
                                    alignItems: 'center',
                                    gap: '4px'
                                }}
                            >
                                <CheckCircle2 size={10} /> Resolve
                            </button>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};
