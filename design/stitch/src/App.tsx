import { useState } from 'react'
import SplashScreen from './screens/SplashScreen'
import OnboardingScreen from './screens/OnboardingScreen'
import LoginScreen from './screens/LoginScreen'
import HomeScreen from './screens/HomeScreen'
import ChatScreen from './screens/ChatScreen'
import VoiceScreen from './screens/VoiceScreen'
import PDFScreen from './screens/PDFScreen'
import ImageScreen from './screens/ImageScreen'
import CodeScreen from './screens/CodeScreen'
import ToolsScreen from './screens/ToolsScreen'
import HistoryScreen from './screens/HistoryScreen'
import ProfileScreen from './screens/ProfileScreen'
import SettingsScreen from './screens/SettingsScreen'

export type Screen =
  | 'splash' | 'onboarding' | 'login'
  | 'home' | 'chat' | 'voice' | 'pdf' | 'image' | 'code'
  | 'tools' | 'history' | 'profile' | 'settings'

export type NavTab = 'home' | 'chats' | 'tools' | 'profile'

export default function App() {
  const [screen, setScreen] = useState<Screen>('splash')
  const [darkMode, setDarkMode] = useState(false)
  const [navTab, setNavTab] = useState<NavTab>('home')

  const navigate = (s: Screen) => setScreen(s)

  const handleTabChange = (tab: NavTab) => {
    setNavTab(tab)
    if (tab === 'home') navigate('home')
    else if (tab === 'chats') navigate('history')
    else if (tab === 'tools') navigate('tools')
    else if (tab === 'profile') navigate('profile')
  }

  const screenProps = { navigate, darkMode, setDarkMode, navTab, onTabChange: handleTabChange }

  const renderScreen = () => {
    switch (screen) {
      case 'splash': return <SplashScreen {...screenProps} />
      case 'onboarding': return <OnboardingScreen {...screenProps} />
      case 'login': return <LoginScreen {...screenProps} />
      case 'home': return <HomeScreen {...screenProps} />
      case 'chat': return <ChatScreen {...screenProps} />
      case 'voice': return <VoiceScreen {...screenProps} />
      case 'pdf': return <PDFScreen {...screenProps} />
      case 'image': return <ImageScreen {...screenProps} />
      case 'code': return <CodeScreen {...screenProps} />
      case 'tools': return <ToolsScreen {...screenProps} />
      case 'history': return <HistoryScreen {...screenProps} />
      case 'profile': return <ProfileScreen {...screenProps} />
      case 'settings': return <SettingsScreen {...screenProps} />
      default: return <HomeScreen {...screenProps} />
    }
  }

  return (
    <div className={darkMode ? 'dark' : ''} style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', width: '100%', background: '#0F0F0F', padding: '20px' }}>
      {/* Phone frame */}
      <div style={{
        width: 375,
        height: 812,
        borderRadius: 44,
        overflow: 'hidden',
        position: 'relative',
        flexShrink: 0,
        boxShadow: '0 40px 80px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.08), inset 0 0 0 2px rgba(255,255,255,0.12)',
        background: darkMode ? '#1C1B1F' : '#FFFBFE',
      }}>
        {/* Status bar */}
        <div style={{
          height: 44,
          background: darkMode ? '#1C1B1F' : '#FFFBFE',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 24px',
          position: 'absolute',
          top: 0, left: 0, right: 0,
          zIndex: 100,
          fontSize: 12,
          fontWeight: 600,
          color: darkMode ? '#E6E1E5' : '#1C1B1F',
        }}>
          <span>9:41</span>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            <SignalIcon />
            <WifiIcon />
            <BatteryIcon />
          </div>
        </div>

        {/* Screen content */}
        <div style={{ position: 'absolute', inset: 0, top: 44, overflowY: 'auto', overflowX: 'hidden' }} key={screen} className="screen-enter">
          {renderScreen()}
        </div>
      </div>
    </div>
  )
}

function SignalIcon() {
  return (
    <svg width="17" height="12" viewBox="0 0 17 12" fill="currentColor">
      <rect x="0" y="8" width="3" height="4" rx="1" opacity="1" />
      <rect x="4.5" y="5" width="3" height="7" rx="1" opacity="1" />
      <rect x="9" y="2" width="3" height="10" rx="1" opacity="1" />
      <rect x="13.5" y="0" width="3" height="12" rx="1" opacity="0.4" />
    </svg>
  )
}

function WifiIcon() {
  return (
    <svg width="16" height="12" viewBox="0 0 16 12" fill="currentColor">
      <path d="M8 9.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z" />
      <path d="M3.5 6.5a6.5 6.5 0 0 1 9 0" strokeWidth="1.5" stroke="currentColor" fill="none" strokeLinecap="round" opacity="0.8" />
      <path d="M1 4A10 10 0 0 1 15 4" strokeWidth="1.5" stroke="currentColor" fill="none" strokeLinecap="round" opacity="0.4" />
    </svg>
  )
}

function BatteryIcon() {
  return (
    <svg width="25" height="12" viewBox="0 0 25 12" fill="currentColor">
      <rect x="0" y="1" width="21" height="10" rx="3" stroke="currentColor" strokeWidth="1.2" fill="none" />
      <rect x="22" y="4" width="2.5" height="4" rx="1" opacity="0.4" />
      <rect x="1.5" y="2.5" width="17" height="7" rx="2" opacity="0.9" />
    </svg>
  )
}
