import { useState } from 'react'
import BottomNav from '../components/BottomNav'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

const chats = [
  { id: 1, title: 'React performance optimization', preview: 'Use useMemo for expensive calculations', time: '2m ago', pinned: true, icon: '💬' },
  { id: 2, title: 'Write a cover letter for Google', preview: "Here's a professional cover letter...", time: '1h ago', pinned: true, icon: '📝' },
  { id: 3, title: 'Explain quantum computing simply', preview: 'Quantum computers use qubits instead of...', time: '3h ago', pinned: false, icon: '🔬' },
  { id: 4, title: 'Python web scraper tutorial', preview: 'import requests, BeautifulSoup...', time: 'Yesterday', pinned: false, icon: '⌨️' },
  { id: 5, title: 'Marketing strategy for SaaS', preview: 'Focus on product-led growth...', time: 'Yesterday', pinned: false, icon: '📊' },
  { id: 6, title: 'Fix TypeScript errors in my code', preview: 'The issue is a missing type annotation...', time: '2 days ago', pinned: false, icon: '🐛' },
  { id: 7, title: 'Healthy meal prep ideas', preview: 'Here are 7 nutritious meal prep recipes...', time: '3 days ago', pinned: false, icon: '🥗' },
  { id: 8, title: 'How to negotiate salary', preview: 'Start by researching market rates...', time: '1 week ago', pinned: false, icon: '💰' },
]

export default function HistoryScreen({ navigate, darkMode, navTab, onTabChange }: Props) {
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<'all' | 'pinned'>('all')
  const [actionMenu, setActionMenu] = useState<number | null>(null)

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  const filtered = chats.filter(c => {
    if (filter === 'pinned' && !c.pinned) return false
    if (search && !c.title.toLowerCase().includes(search.toLowerCase())) return false
    return true
  })

  const today = filtered.filter(c => c.time.includes('m ago') || c.time.includes('h ago'))
  const yesterday = filtered.filter(c => c.time === 'Yesterday')
  const older = filtered.filter(c => c.time.includes('days ago') || c.time.includes('week ago'))

  return (
    <div style={{ background: bg, minHeight: 768, paddingBottom: 88 }}>
      {/* Header */}
      <div style={{ padding: '16px 20px 12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
          <h2 style={{ margin: 0, fontSize: 22, fontWeight: 700, color: text }}>Chat History</h2>
          <button onClick={() => navigate('chat')} style={{ width: 40, height: 40, borderRadius: 20, background: '#6750A4', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="white" strokeWidth="2.2" strokeLinecap="round"><path d="M10 4v12M4 10h12" /></svg>
          </button>
        </div>

        {/* Search */}
        <div style={{ height: 48, borderRadius: 24, background: card, border: `1.5px solid ${border}`, display: 'flex', alignItems: 'center', gap: 10, padding: '0 16px', marginBottom: 12 }}>
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="#79747E" strokeWidth="1.8" strokeLinecap="round">
            <circle cx="8" cy="8" r="5" /><path d="M12 12l3 3" />
          </svg>
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search conversations..."
            style={{ flex: 1, background: 'none', border: 'none', outline: 'none', fontSize: 14, color: text, fontFamily: 'inherit' }}
          />
        </div>

        {/* Filters */}
        <div style={{ display: 'flex', gap: 8 }}>
          {(['all', 'pinned'] as const).map(f => (
            <button key={f} onClick={() => setFilter(f)} style={{ padding: '6px 16px', borderRadius: 16, background: filter === f ? '#6750A4' : card, border: `1px solid ${filter === f ? '#6750A4' : border}`, color: filter === f ? '#fff' : sub, fontSize: 13, fontWeight: 500, cursor: 'pointer', textTransform: 'capitalize' }}>
              {f === 'all' ? 'All Chats' : '📌 Pinned'}
            </button>
          ))}
        </div>
      </div>

      {/* Chat groups */}
      <div style={{ padding: '0 20px' }}>
        {today.length > 0 && <ChatGroup label="Today" chats={today} card={card} text={text} sub={sub} border={border} navigate={navigate} actionMenu={actionMenu} setActionMenu={setActionMenu} />}
        {yesterday.length > 0 && <ChatGroup label="Yesterday" chats={yesterday} card={card} text={text} sub={sub} border={border} navigate={navigate} actionMenu={actionMenu} setActionMenu={setActionMenu} />}
        {older.length > 0 && <ChatGroup label="Earlier" chats={older} card={card} text={text} sub={sub} border={border} navigate={navigate} actionMenu={actionMenu} setActionMenu={setActionMenu} />}
        {filtered.length === 0 && (
          <div style={{ textAlign: 'center', padding: '60px 20px', color: sub }}>
            <div style={{ fontSize: 48, marginBottom: 16 }}>💬</div>
            <p style={{ margin: 0, fontSize: 16, fontWeight: 600, color: text }}>No chats found</p>
            <p style={{ margin: '6px 0 0', fontSize: 14 }}>Try a different search term</p>
          </div>
        )}
      </div>

      <BottomNav navTab={navTab} onTabChange={onTabChange} darkMode={darkMode} navigate={navigate} />
    </div>
  )
}

function ChatGroup({ label, chats, card, text, sub, border, navigate, actionMenu, setActionMenu }: any) {
  return (
    <div style={{ marginBottom: 20 }}>
      <p style={{ margin: '0 0 8px', fontSize: 12, fontWeight: 600, color: sub, textTransform: 'uppercase', letterSpacing: 0.8 }}>{label}</p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {(chats as any[]).map((chat) => (
          <div key={chat.id} style={{ position: 'relative' }}>
            <button
              onClick={() => navigate('chat')}
              onContextMenu={e => { e.preventDefault(); setActionMenu(chat.id) }}
              style={{
                display: 'flex', alignItems: 'center', gap: 12,
                padding: '12px 14px', borderRadius: 14,
                background: card, border: `1px solid ${border}`,
                cursor: 'pointer', textAlign: 'left', width: '100%',
              }}
            >
              <div style={{ width: 40, height: 40, borderRadius: 20, background: '#E8DEF8', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 18, flexShrink: 0 }}>
                {chat.icon}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 3 }}>
                  <span style={{ fontSize: 14, fontWeight: 600, color: text, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }}>
                    {chat.pinned && <span style={{ marginRight: 4 }}>📌</span>}
                    {chat.title}
                  </span>
                  <span style={{ fontSize: 11, color: sub, flexShrink: 0, marginLeft: 8 }}>{chat.time}</span>
                </div>
                <p style={{ margin: 0, fontSize: 12, color: sub, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{chat.preview}</p>
              </div>
              <button onClick={e => { e.stopPropagation(); setActionMenu(actionMenu === chat.id ? null : chat.id) }} style={{ width: 28, height: 28, borderRadius: 14, background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                <svg width="16" height="16" viewBox="0 0 16 16" fill={sub}><circle cx="8" cy="3" r="1.5" /><circle cx="8" cy="8" r="1.5" /><circle cx="8" cy="13" r="1.5" /></svg>
              </button>
            </button>
            {actionMenu === chat.id && (
              <div style={{ position: 'absolute', right: 0, top: '100%', zIndex: 50, background: card, border: `1px solid ${border}`, borderRadius: 14, padding: '6px', boxShadow: '0 8px 24px rgba(0,0,0,0.15)', minWidth: 150, marginTop: 4 }}>
                {[{ icon: '📌', label: 'Pin chat' }, { icon: '✏️', label: 'Rename' }, { icon: '🗑️', label: 'Delete', danger: true }].map((item, i) => (
                  <button key={i} onClick={() => setActionMenu(null)} style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '10px 12px', borderRadius: 10, background: 'none', border: 'none', cursor: 'pointer', fontSize: 13, color: item.danger ? '#B3261E' : text }}>
                    <span>{item.icon}</span>{item.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
