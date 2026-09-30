import type { NavTab, Screen } from '../App'

interface Props {
  navTab: NavTab
  onTabChange: (tab: NavTab) => void
  darkMode: boolean
  navigate: (s: Screen) => void
}

const tabs: { id: NavTab; label: string; icon: React.FC<{ active: boolean }> }[] = [
  { id: 'home', label: 'Home', icon: HomeIcon },
  { id: 'chats', label: 'Chats', icon: ChatsIcon },
  { id: 'tools', label: 'AI Tools', icon: ToolsIcon },
  { id: 'profile', label: 'Profile', icon: ProfileIcon },
]

export default function BottomNav({ navTab, onTabChange, darkMode }: Props) {
  const bg = darkMode ? '#2B2930' : '#FFFFFF'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  return (
    <div style={{
      position: 'absolute',
      bottom: 0, left: 0, right: 0,
      height: 80,
      background: bg,
      borderTop: `1px solid ${border}`,
      display: 'flex',
      alignItems: 'center',
      paddingBottom: 12,
      zIndex: 50,
    }}>
      {tabs.map(tab => {
        const active = navTab === tab.id
        const Icon = tab.icon
        const color = active ? '#6750A4' : (darkMode ? '#CAC4D0' : '#79747E')
        return (
          <button
            key={tab.id}
            onClick={() => onTabChange(tab.id)}
            style={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 4,
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              padding: '8px 0',
            }}
          >
            <div style={{
              position: 'relative',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}>
              {active && (
                <div style={{
                  position: 'absolute',
                  width: 64,
                  height: 32,
                  background: '#E8DEF8',
                  borderRadius: 16,
                }} />
              )}
              <div style={{ position: 'relative', zIndex: 1 }}>
                <Icon active={active} />
              </div>
            </div>
            <span style={{ fontSize: 11, fontWeight: active ? 600 : 400, color, lineHeight: 1 }}>
              {tab.label}
            </span>
          </button>
        )
      })}
    </div>
  )
}

function HomeIcon({ active }: { active: boolean }) {
  const c = active ? '#6750A4' : '#79747E'
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill={active ? c : 'none'} stroke={c} strokeWidth="1.8">
      <path d="M3 12L12 3l9 9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5 10v9a1 1 0 001 1h4v-5h4v5h4a1 1 0 001-1v-9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function ChatsIcon({ active }: { active: boolean }) {
  const c = active ? '#6750A4' : '#79747E'
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill={active ? c : 'none'} stroke={c} strokeWidth="1.8">
      <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function ToolsIcon({ active }: { active: boolean }) {
  const c = active ? '#6750A4' : '#79747E'
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill={active ? '#E8DEF8' : 'none'} stroke={c} strokeWidth="1.8">
      <rect x="3" y="3" width="7" height="7" rx="2" />
      <rect x="14" y="3" width="7" height="7" rx="2" />
      <rect x="3" y="14" width="7" height="7" rx="2" />
      <rect x="14" y="14" width="7" height="7" rx="2" />
    </svg>
  )
}

function ProfileIcon({ active }: { active: boolean }) {
  const c = active ? '#6750A4' : '#79747E'
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill={active ? c : 'none'} stroke={c} strokeWidth="1.8">
      <circle cx="12" cy="8" r="4" />
      <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7" strokeLinecap="round" />
    </svg>
  )
}
