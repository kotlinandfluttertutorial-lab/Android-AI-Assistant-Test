import BottomNav from '../components/BottomNav'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

const tools = [
  { icon: '📋', label: 'Summarizer', desc: 'Condense any text', color: '#6750A4', bg: '#E8DEF8', screen: 'chat' as Screen },
  { icon: '🌍', label: 'Translator', desc: '100+ languages', color: '#0061A4', bg: '#D3E4FF', screen: 'chat' as Screen },
  { icon: '✏️', label: 'Grammar Check', desc: 'Fix errors instantly', color: '#386A20', bg: '#D6EDCC', screen: 'chat' as Screen },
  { icon: '📄', label: 'Resume Builder', desc: 'Stand out from the crowd', color: '#B3261E', bg: '#FFD7D4', screen: 'pdf' as Screen },
  { icon: '📧', label: 'Email Writer', desc: 'Professional emails fast', color: '#7D5260', bg: '#FFD8E4', screen: 'chat' as Screen },
  { icon: '🎙️', label: 'Meeting Notes', desc: 'Transcribe & summarize', color: '#0061A4', bg: '#D3E4FF', screen: 'voice' as Screen },
  { icon: '✅', label: 'To-Do Generator', desc: 'From text to tasks', color: '#386A20', bg: '#D6EDCC', screen: 'chat' as Screen },
  { icon: '⌨️', label: 'Code Generator', desc: 'Build anything with AI', color: '#1C1B1F', bg: '#E7E0EC', screen: 'code' as Screen },
  { icon: '🎨', label: 'Image Creator', desc: 'Generate stunning visuals', color: '#7D5260', bg: '#FFD8E4', screen: 'image' as Screen },
  { icon: '📊', label: 'Data Analyzer', desc: 'Insights from your data', color: '#6750A4', bg: '#E8DEF8', screen: 'pdf' as Screen },
  { icon: '🔍', label: 'Research Helper', desc: 'Deep dive any topic', color: '#0061A4', bg: '#D3E4FF', screen: 'chat' as Screen },
  { icon: '💰', label: 'Finance Advisor', desc: 'Smart money decisions', color: '#386A20', bg: '#D6EDCC', screen: 'chat' as Screen },
]

export default function ToolsScreen({ navigate, darkMode, navTab, onTabChange }: Props) {
  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  return (
    <div style={{ background: bg, minHeight: 768, paddingBottom: 88 }}>
      {/* Header */}
      <div style={{ padding: '16px 20px 8px' }}>
        <h2 style={{ margin: 0, fontSize: 24, fontWeight: 700, color: text }}>AI Tools</h2>
        <p style={{ margin: '4px 0 0', fontSize: 14, color: sub }}>Specialized AI for every task</p>
      </div>

      {/* Search */}
      <div style={{ padding: '8px 20px 16px' }}>
        <div style={{ height: 48, borderRadius: 24, background: card, border: `1.5px solid ${border}`, display: 'flex', alignItems: 'center', gap: 10, padding: '0 16px' }}>
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="#79747E" strokeWidth="1.8" strokeLinecap="round">
            <circle cx="8" cy="8" r="5" />
            <path d="M12 12l3 3" />
          </svg>
          <span style={{ fontSize: 14, color: '#79747E' }}>Search tools...</span>
        </div>
      </div>

      {/* Featured */}
      <div style={{ margin: '0 20px 20px', borderRadius: 20, background: 'linear-gradient(135deg, #7D5260 0%, #B26978 100%)', padding: '20px', position: 'relative', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', top: -20, right: -20, width: 100, height: 100, borderRadius: '50%', background: 'rgba(255,255,255,0.08)' }} />
        <p style={{ margin: 0, fontSize: 12, color: 'rgba(255,255,255,0.7)', textTransform: 'uppercase', letterSpacing: 1 }}>Featured</p>
        <h3 style={{ margin: '4px 0 6px', fontSize: 20, fontWeight: 700, color: '#FFFFFF' }}>Resume Builder AI</h3>
        <p style={{ margin: '0 0 14px', fontSize: 13, color: 'rgba(255,255,255,0.85)', lineHeight: 1.5 }}>Create ATS-optimized resumes tailored to any job description in minutes.</p>
        <button onClick={() => navigate('pdf')} style={{ padding: '8px 18px', borderRadius: 20, background: 'rgba(255,255,255,0.2)', border: '1px solid rgba(255,255,255,0.3)', color: '#FFFFFF', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
          Try now →
        </button>
      </div>

      {/* Tools grid */}
      <div style={{ padding: '0 20px' }}>
        <h3 style={{ margin: '0 0 14px', fontSize: 16, fontWeight: 600, color: text }}>All Tools</h3>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          {tools.map((tool, i) => (
            <button
              key={i}
              onClick={() => navigate(tool.screen)}
              style={{
                padding: '16px', borderRadius: 18,
                background: card, border: `1px solid ${border}`,
                cursor: 'pointer', textAlign: 'left',
                display: 'flex', flexDirection: 'column', gap: 10,
              }}
            >
              <div style={{ width: 44, height: 44, borderRadius: 14, background: tool.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 22 }}>
                {tool.icon}
              </div>
              <div>
                <p style={{ margin: 0, fontSize: 14, fontWeight: 700, color: text }}>{tool.label}</p>
                <p style={{ margin: '3px 0 0', fontSize: 12, color: sub, lineHeight: 1.4 }}>{tool.desc}</p>
              </div>
            </button>
          ))}
        </div>
      </div>

      <BottomNav navTab={navTab} onTabChange={onTabChange} darkMode={darkMode} navigate={navigate} />
    </div>
  )
}
