import BottomNav from '../components/BottomNav'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  setDarkMode: (v: boolean) => void
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

const quickActions = [
  { id: 'chat' as Screen, icon: '💬', label: 'Chat', color: '#6750A4', bg: '#E8DEF8' },
  { id: 'voice' as Screen, icon: '🎙️', label: 'Voice', color: '#0061A4', bg: '#D3E4FF' },
  { id: 'pdf' as Screen, icon: '📄', label: 'PDF', color: '#B3261E', bg: '#FFD7D4' },
  { id: 'image' as Screen, icon: '🖼️', label: 'Image', color: '#386A20', bg: '#D6EDCC' },
  { id: 'code' as Screen, icon: '⌨️', label: 'Code', color: '#7D5260', bg: '#FFD8E4' },
  { id: 'tools' as Screen, icon: '✨', label: 'Translate', color: '#6750A4', bg: '#F3EDF7' },
  { id: 'tools' as Screen, icon: '📝', label: 'Notes', color: '#386A20', bg: '#D6EDCC' },
  { id: 'tools' as Screen, icon: '🔧', label: 'Tools', color: '#7D5260', bg: '#FFD8E4' },
]

const recentChats = [
  { title: 'React performance optimization', preview: 'Use useMemo for expensive calculations...', time: '2m ago', icon: '💬' },
  { title: 'Write a cover letter', preview: 'Here\'s a professional cover letter...', time: '1h ago', icon: '📝' },
  { title: 'Explain quantum computing', preview: 'Quantum computers use qubits...', time: '3h ago', icon: '🤖' },
  { title: 'Python data analysis', preview: 'import pandas as pd...', time: 'Yesterday', icon: '⌨️' },
]

export default function HomeScreen({ navigate, darkMode, setDarkMode, navTab, onTabChange }: Props) {
  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  return (
    <div style={{ background: bg, minHeight: 768, paddingBottom: 88 }}>
      {/* Top bar */}
      <div style={{ padding: '12px 20px 8px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <p style={{ margin: 0, fontSize: 13, color: sub }}>Good morning</p>
          <h2 style={{ margin: 0, fontSize: 22, fontWeight: 700, color: text }}>Hello, Firoj 👋</h2>
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <button
            onClick={() => setDarkMode(!darkMode)}
            style={{ width: 40, height: 40, borderRadius: 20, background: card, border: `1px solid ${border}`, cursor: 'pointer', fontSize: 18, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          >
            {darkMode ? '☀️' : '🌙'}
          </button>
          <div style={{ width: 40, height: 40, borderRadius: 20, background: 'linear-gradient(135deg, #6750A4, #9C89C4)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontWeight: 700, fontSize: 16 }}>
            F
          </div>
        </div>
      </div>

      {/* Search bar */}
      <div style={{ padding: '12px 20px' }}>
        <button
          onClick={() => navigate('chat')}
          style={{
            width: '100%', height: 52, borderRadius: 26,
            background: card, border: `1.5px solid ${border}`,
            display: 'flex', alignItems: 'center', gap: 12,
            padding: '0 16px', cursor: 'pointer', textAlign: 'left',
            boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
          }}
        >
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="#79747E" strokeWidth="1.8" strokeLinecap="round">
            <circle cx="9" cy="9" r="5.5" />
            <path d="M13.5 13.5l3 3" />
          </svg>
          <span style={{ fontSize: 15, color: '#79747E', flex: 1 }}>Ask me anything...</span>
          <div style={{ width: 32, height: 32, borderRadius: 16, background: '#6750A4', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round">
              <path d="M2 8h12M9 3l5 5-5 5" />
            </svg>
          </div>
        </button>
      </div>

      {/* Featured banner */}
      <div style={{ margin: '4px 20px 16px', borderRadius: 20, background: 'linear-gradient(135deg, #6750A4 0%, #9C89C4 100%)', padding: '20px 20px', position: 'relative', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', top: -20, right: -20, width: 100, height: 100, borderRadius: '50%', background: 'rgba(255,255,255,0.1)' }} />
        <div style={{ position: 'absolute', bottom: -30, right: 20, width: 80, height: 80, borderRadius: '50%', background: 'rgba(255,255,255,0.08)' }} />
        <p style={{ margin: '0 0 4px', fontSize: 12, color: 'rgba(255,255,255,0.7)', fontWeight: 500, textTransform: 'uppercase', letterSpacing: 1 }}>New</p>
        <h3 style={{ margin: '0 0 6px', fontSize: 18, fontWeight: 700, color: '#FFFFFF', position: 'relative' }}>Gemini 2.0 Ultra</h3>
        <p style={{ margin: 0, fontSize: 13, color: 'rgba(255,255,255,0.8)', lineHeight: 1.5 }}>Now with improved reasoning and multimodal capabilities</p>
        <button onClick={() => navigate('chat')} style={{ marginTop: 14, padding: '8px 18px', borderRadius: 20, background: 'rgba(255,255,255,0.2)', border: '1px solid rgba(255,255,255,0.3)', color: '#FFFFFF', fontSize: 13, fontWeight: 600, cursor: 'pointer', backdropFilter: 'blur(10px)' }}>
          Try now →
        </button>
      </div>

      {/* Quick actions */}
      <div style={{ padding: '0 20px 8px' }}>
        <h3 style={{ margin: '0 0 14px', fontSize: 16, fontWeight: 600, color: text }}>Quick Actions</h3>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10 }}>
          {quickActions.map((action, i) => (
            <button
              key={i}
              onClick={() => navigate(action.id)}
              style={{
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8,
                padding: '14px 8px', borderRadius: 16,
                background: card, border: `1px solid ${border}`,
                cursor: 'pointer', transition: 'transform 0.15s',
              }}
            >
              <div style={{ width: 40, height: 40, borderRadius: 14, background: action.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 20 }}>
                {action.icon}
              </div>
              <span style={{ fontSize: 11, fontWeight: 500, color: text, textAlign: 'center' }}>{action.label}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Recent conversations */}
      <div style={{ padding: '16px 20px 0' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: text }}>Recent Chats</h3>
          <button onClick={() => onTabChange('chats')} style={{ background: 'none', border: 'none', color: '#6750A4', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>See all</button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {recentChats.map((chat, i) => (
            <button
              key={i}
              onClick={() => navigate('chat')}
              style={{
                display: 'flex', alignItems: 'center', gap: 12,
                padding: '14px 16px', borderRadius: 16,
                background: card, border: `1px solid ${border}`,
                cursor: 'pointer', textAlign: 'left', width: '100%',
              }}
            >
              <div style={{ width: 40, height: 40, borderRadius: 20, background: '#E8DEF8', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 18, flexShrink: 0 }}>
                {chat.icon}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
                  <span style={{ fontSize: 14, fontWeight: 600, color: text, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }}>{chat.title}</span>
                  <span style={{ fontSize: 11, color: sub, flexShrink: 0, marginLeft: 8 }}>{chat.time}</span>
                </div>
                <p style={{ margin: 0, fontSize: 12, color: sub, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{chat.preview}</p>
              </div>
            </button>
          ))}
        </div>
      </div>

      <BottomNav navTab={navTab} onTabChange={onTabChange} darkMode={darkMode} navigate={navigate} />
    </div>
  )
}
