import { useState, useEffect } from 'react';
import ReactFlow, { Background, Controls } from 'reactflow';
import 'reactflow/dist/style.css';
import { api } from '../services/api';

interface Props {
    projectName: string;
}

export const LineageGraph = ({ projectName }: Props) => {
    const [nodes, setNodes] = useState<any[]>([]);
    const [edges, setEdges] = useState<any[]>([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!projectName) return;

        setLoading(true);
        api.getLineage(projectName)
            .then(res => {
                const rawNodes = res.data.nodes || [];
                const rawLinks = res.data.links || [];

                // Transform nodes to ReactFlow format with layout
                const formattedNodes = rawNodes.map((n: any, i: number) => ({
                    id: n.id,
                    data: { label: n.label },
                    position: { x: 250 + Math.cos(i) * 150, y: 150 + Math.sin(i) * 150 }, // Simple circular layout
                    type: i === 0 ? 'input' : 'default',
                    style: { background: '#1e293b', color: '#f8fafc', border: '1px solid #334155', width: 150 }
                }));

                // Transform edges
                const formattedEdges = rawLinks.map((l: any, i: number) => ({
                    id: `e-${i}`,
                    source: l.source,
                    target: l.target,
                    label: l.label,
                    animated: true,
                    style: { stroke: '#10b981' }
                }));

                setNodes(formattedNodes);
                setEdges(formattedEdges);
            })
            .catch(err => {
                console.error("Failed to fetch lineage", err);
                setNodes([]);
                setEdges([]);
            })
            .finally(() => setLoading(false));
    }, [projectName]);

    return (
        <div className="glass-panel" style={{ width: '100%', height: '400px', overflow: 'hidden', position: 'relative' }}>
            <div style={{ padding: '12px', borderBottom: '1px solid var(--border-light)', fontSize: '12px', fontWeight: 600, color: '#94a3b8', display: 'flex', justifyContent: 'space-between' }}>
                <span>Semantic Lineage View</span>
                {loading && <span style={{ color: 'var(--primary)', fontSize: '10px' }}>Loading real-time trace...</span>}
            </div>
            <div style={{ width: '100%', height: '350px' }}>
                {nodes.length > 0 ? (
                    <ReactFlow
                        nodes={nodes}
                        edges={edges}
                        fitView
                        style={{ background: 'transparent' }}
                    >
                        <Background color="#334155" gap={20} />
                        <Controls />
                    </ReactFlow>
                ) : (
                    <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#64748b', fontSize: '13px' }}>
                        {loading ? 'Analyzing Project Graph...' : `No semantic mapping found for "${projectName}"`}
                    </div>
                )}
            </div>
        </div>
    );
};
