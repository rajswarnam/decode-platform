import { PieChart, Pie, Cell, ResponsiveContainer, Label } from 'recharts';

interface Props {
    score: number; // 0.0 to 1.0
}

export const TrustScoreGauge = ({ score }: Props) => {
    const percentage = score > 1 ? Math.round(score) : Math.round(score * 100);
    const data = [
        { name: 'Trust', value: percentage },
        { name: 'Gap', value: 100 - percentage },
    ];

    const getColor = (s: number) => {
        if (s >= 0.8) return '#10b981';
        if (s >= 0.5) return '#f59e0b';
        return '#ef4444';
    };

    return (
        <div style={{ width: '100%', height: 200, position: 'relative' }}>
            <ResponsiveContainer>
                <PieChart>
                    <Pie
                        data={data}
                        cx="50%"
                        cy="50%"
                        innerRadius={60}
                        outerRadius={80}
                        startAngle={180}
                        endAngle={0}
                        paddingAngle={0}
                        dataKey="value"
                    >
                        <Cell fill={getColor(score)} />
                        <Cell fill="rgba(255,255,255,0.05)" />
                        <Label
                            value={`${percentage}%`}
                            position="centerTop"
                            style={{ fontSize: '24px', fontWeight: 'bold', fill: '#f8fafc', fontFamily: 'Outfit' }}
                            dy={-10}
                        />
                    </Pie>
                </PieChart>
            </ResponsiveContainer>
            <div style={{
                textAlign: 'center',
                fontSize: '12px',
                color: '#94a3b8',
                marginTop: '-50px',
                textTransform: 'uppercase',
                letterSpacing: '1px'
            }}>
                Trust Reliability
            </div>
        </div>
    );
};
