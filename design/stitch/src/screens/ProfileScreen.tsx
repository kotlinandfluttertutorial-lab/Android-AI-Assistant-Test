import BottomNav from '../components/BottomNav'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

const stats = [
  { label: 'Total Chats', value: '247', icon: '💬' },
  { label: 'Words Generated', value: '84.2K', icon: '✍️' },
  { label: 'PDFs Analyzed', value: '18', icon: '📄' },
  { label: 'Days Active', value: '63', icon: '🔥' },
]

const menuItems = [
  { icon: '👑', label: 'Upgrade to Pro', desc: 'Unlock unlimited access', action: 'settings' as Screen, badge: 'PRO', highlight: true },
  { icon: '⚙️', label: 'Settings', desc: 'Preferences & privacy', action: 'settings' as Screen },
  { icon: '🔔', label: 'Notifications', desc: 'Manage your alerts', action: 'settings' as Screen },
  { icon: '🔒', label: 'Privacy & Security', desc: 'Data & permissions', action: 'settings' as Screen },
  { icon: '🎨', label: 'Appearance', desc: 'Theme & display', action: 'settings' as Screen },
  { icon: '❓', label: 'Help & Support', desc: 'FAQ and contact us', action: 'settings' as Screen },
  { icon: '⭐', label: 'Rate the App', desc: 'Share your feedback', action: 'settings' as Screen },
]

export default function ProfileScreen({ navigate, darkMode, navTab, onTabChange }: Props) {
  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  return (
    <div style={{ background: bg, minHeight: 768, paddingBottom: 88 }}>
      {/* Header with avatar */}
      <div style={{ background: 'linear-gradient(160deg, #6750A4 0%, #9C89C4 100%)', padding: '24px 20px 32px', position: 'relative', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', top: -30, right: -30, width: 140, height: 140, borderRadius: '50%', background: 'rgba(255,255,255,0.08)' }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <div style={{ position: 'relative' }}>
            <div style={{ width: 72, height: 72, borderRadius: 36, background: 'linear-gradient(135deg, #D0BCFF, #9C89C4)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 28, fontWeight: 700, color: '#381E72', border: '3px solid rgba(255,255,255,0.3)' }}>
              F
            </div>
            <div style={{ position: 'absolute', bottom: 2, right: 2, width: 16, height: 16, borderRadius: 8, background: '#386A20', border: '2px solid white' }} />
          </div>
          <div>
            <h2 style={{ margin: 0, fontSize: 22, fontWeight: 700, color: '#FFFFFF' }}>Firoj Khan</h2>
            <p style={{ margin: '2px 0', fontSize: 14, color: 'rgba(255,255,255,0.8)' }}>firoj.khan@gmail.com</p>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: 'rgba(255,255,255,0.2)', borderRadius: 12, padding: '3px 10px', marginTop: 4 }}>
              <span style={{ fontSize: 11 }}>⚡</span>
              <span style={{ fontSize: 12, color: '#FFFFFF', fontWeight: 600 }}>Free Plan</span>
            </div>
          </div>
        </div>
      </div>

      {/* Stats */}
      <div style={{ margin: '-16px 20px 20px', background: card, borderRadius: 20, padding: '16px', boxShadow: '0 4px 20px rgba(0,0,0,0.1)', border: `1px solid ${border}` }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 0 }}>
          {stats.map((stat, i) => (
            <div key={i} style={{ textAlign: 'center', padding: '8px 4px', borderRight: i < 3 ? `1px solid ${border}` : 'none' }}>
              <div style={{ fontSize: 18, marginBottom: 4 }}>{stat.icon}</div>
              <div style={{ fontSize: 16, fontWeight: 700, color: text, lineHeight: 1 }}>{stat.value}</div>
              <div style={{ fontSize: 10, color: sub, marginTop: 3, lineHeight: 1.3 }}>{stat.label}</div>
            </div>
          ))}
        </div>
      </div>

      {/* Menu */}
      <div style={{ padding: '0 20px' }}>
        <div style={{ background: card, borderRadius: 20, border: `1px solid ${border}`, overflow: 'hidden' }}>
          {menuItems.map((item, i) => (
            <button
              key={i}
              onClick={() => navigate(item.action)}
              style={{
                display: 'flex', alignItems: 'center', gap: 14,
                padding: '14px 16px', width: '100%',
                background: item.highlight ? '#F3EDF7' : 'none',
                border: 'none',
                borderBottom: i < menuItems.length - 1 ? `1px solid ${border}` : 'none',
                cursor: 'pointer', textAlign: 'left',
              }}
            >
              <div style={{ width: 40, height: 40, borderRadius: 14, background: item.highlight ? '#E8DEF8' : darkMode ? '#49454F' : '#F3EDF7', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 20, flexShrink: 0 }}>
                {item.icon}
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 15, fontWeight: 600, color: item.highlight ? '#6750A4' : text }}>{item.label}</span>
                  {item.badge && <span style={{ padding: '2px 8px', borderRadius: 8, background: '#6750A4', color: '#fff', fontSize: 10, fontWeight: 700 }}>{item.badge}</span>}
                </div>
                <p style={{ margin: '2px 0 0', fontSize: 12, color: sub }}>{item.desc}</p>
              </div>
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke={sub} strokeWidth="1.8" strokeLinecap="round"><path d="M6 3l5 5-5 5" /></svg>
            </button>
          ))}
        </div>

        <button onClick={() => navigate('login')} style={{ width: '100%', height: 52, borderRadius: 26, background: '#FFD7D4', border: '1.5px solid #F2B8B5', color: '#B3261E', fontSize: 15, fontWeight: 600, cursor: 'pointer', marginTop: 16 }}>
          Sign Out
        </button>

        <p style={{ textAlign: 'center', fontSize: 12, color: sub, marginTop: 16 }}>AI Assistant v2.4.1 • Made with ❤️</p>
      </div>

      <BottomNav navTab={navTab} onTabChange={onTabChange} darkMode={darkMode} navigate={navigate} />
    </div>
  )
}
