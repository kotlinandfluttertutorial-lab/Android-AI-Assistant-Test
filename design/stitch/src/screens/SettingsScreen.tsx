import { useState } from 'react'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  setDarkMode: (v: boolean) => void
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

export default function SettingsScreen({ navigate, darkMode, setDarkMode }: Props) {
  const [notifications, setNotifications] = useState(true)
  const [voiceEnabled, setVoiceEnabled] = useState(true)
  const [dataSharing, setDataSharing] = useState(false)
  const [language, setLanguage] = useState('English')
  const [apiKey, setApiKey] = useState('')

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  return (
    <div style={{ background: bg, minHeight: 768 }}>
      {/* Header */}
      <div style={{ padding: '12px 16px', borderBottom: `1px solid ${border}`, display: 'flex', alignItems: 'center', gap: 12, background: card }}>
        <button onClick={() => navigate('profile')} style={{ width: 36, height: 36, borderRadius: 18, background: darkMode ? '#49454F' : '#F3EDF7', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke={text} strokeWidth="2" strokeLinecap="round"><path d="M12 5l-5 5 5 5" /></svg>
        </button>
        <h2 style={{ margin: 0, fontSize: 18, fontWeight: 700, color: text }}>Settings</h2>
      </div>

      <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* Appearance */}
        <SettingsSection title="Appearance" icon="🎨" card={card} border={border} text={text} sub={sub}>
          <SettingRow label="Dark Mode" sub="Switch to dark theme" card={card} border={border} text={text} subText={sub} last>
            <Toggle value={darkMode} onChange={setDarkMode} />
          </SettingRow>
          <SettingRow label="Language" sub={language} card={card} border={border} text={text} subText={sub} last>
            <select value={language} onChange={e => setLanguage(e.target.value)} style={{ background: 'none', border: 'none', color: '#6750A4', fontSize: 14, fontWeight: 500, cursor: 'pointer', outline: 'none', fontFamily: 'inherit' }}>
              {['English', 'Spanish', 'French', 'German', 'Japanese', 'Chinese', 'Arabic'].map(l => <option key={l}>{l}</option>)}
            </select>
          </SettingRow>
        </SettingsSection>

        {/* Notifications */}
        <SettingsSection title="Notifications" icon="🔔" card={card} border={border} text={text} sub={sub}>
          <SettingRow label="Push Notifications" sub="Receive AI response alerts" card={card} border={border} text={text} subText={sub} last>
            <Toggle value={notifications} onChange={setNotifications} />
          </SettingRow>
        </SettingsSection>

        {/* Voice */}
        <SettingsSection title="Voice" icon="🎙️" card={card} border={border} text={text} sub={sub}>
          <SettingRow label="Voice Assistant" sub="Enable voice interactions" card={card} border={border} text={text} subText={sub}>
            <Toggle value={voiceEnabled} onChange={setVoiceEnabled} />
          </SettingRow>
          <SettingRow label="Voice Speed" sub="Normal" card={card} border={border} text={text} subText={sub} last>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke={sub} strokeWidth="1.8" strokeLinecap="round"><path d="M6 3l5 5-5 5" /></svg>
          </SettingRow>
        </SettingsSection>

        {/* Privacy */}
        <SettingsSection title="Privacy" icon="🔒" card={card} border={border} text={text} sub={sub}>
          <SettingRow label="Data Sharing" sub="Help improve AI models" card={card} border={border} text={text} subText={sub}>
            <Toggle value={dataSharing} onChange={setDataSharing} />
          </SettingRow>
          <SettingRow label="Clear History" sub="Delete all conversations" card={card} border={border} text={text} subText={sub} last danger>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="#B3261E" strokeWidth="1.8" strokeLinecap="round"><path d="M6 3l5 5-5 5" /></svg>
          </SettingRow>
        </SettingsSection>

        {/* API Key */}
        <SettingsSection title="API Key" icon="🔑" card={card} border={border} text={text} sub={sub}>
          <div style={{ padding: '12px 16px', borderBottom: `1px solid ${border}` }}>
            <p style={{ margin: '0 0 8px', fontSize: 13, color: sub }}>Your Gemini API Key</p>
            <input
              type="password"
              value={apiKey}
              onChange={e => setApiKey(e.target.value)}
              placeholder="AIza..."
              style={{ width: '100%', height: 44, borderRadius: 12, background: darkMode ? '#1C1B1F' : '#F3EDF7', border: `1px solid ${border}`, padding: '0 14px', fontSize: 14, color: text, outline: 'none', fontFamily: 'monospace', boxSizing: 'border-box' }}
            />
          </div>
          <div style={{ padding: '12px 16px' }}>
            <button style={{ width: '100%', height: 44, borderRadius: 22, background: '#6750A4', border: 'none', color: '#fff', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>
              Save API Key
            </button>
          </div>
        </SettingsSection>

        {/* About */}
        <SettingsSection title="About" icon="ℹ️" card={card} border={border} text={text} sub={sub}>
          <SettingRow label="Version" sub="2.4.1 (Build 241)" card={card} border={border} text={text} subText={sub} />
          <SettingRow label="Terms of Service" sub="Read our terms" card={card} border={border} text={text} subText={sub} />
          <SettingRow label="Privacy Policy" sub="How we handle data" card={card} border={border} text={text} subText={sub} last />
        </SettingsSection>
      </div>
    </div>
  )
}

function SettingsSection({ title, icon, card, border, sub, children }: { title: string; icon: string; card: string; border: string; text?: string; sub: string; children: React.ReactNode }) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, padding: '0 4px' }}>
        <span style={{ fontSize: 14 }}>{icon}</span>
        <span style={{ fontSize: 13, fontWeight: 600, color: sub, textTransform: 'uppercase', letterSpacing: 0.8 }}>{title}</span>
      </div>
      <div style={{ background: card, borderRadius: 16, border: `1px solid ${border}`, overflow: 'hidden' }}>
        {children}
      </div>
    </div>
  )
}

function SettingRow({ label, sub, border, text, subText, last, danger, children }: { label: string; sub: string; card?: string; border: string; text: string; subText: string; last?: boolean; danger?: boolean; children?: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', padding: '13px 16px', borderBottom: last ? 'none' : `1px solid ${border}` }}>
      <div style={{ flex: 1 }}>
        <p style={{ margin: 0, fontSize: 15, fontWeight: 500, color: danger ? '#B3261E' : text }}>{label}</p>
        {sub && <p style={{ margin: '2px 0 0', fontSize: 12, color: subText }}>{sub}</p>}
      </div>
      {children}
    </div>
  )
}

function Toggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      onClick={() => onChange(!value)}
      style={{
        width: 48, height: 28, borderRadius: 14,
        background: value ? '#6750A4' : '#CAC4D0',
        border: 'none', cursor: 'pointer',
        position: 'relative', transition: 'background 0.2s', flexShrink: 0,
      }}
    >
      <div style={{
        position: 'absolute', top: 3,
        left: value ? 22 : 3,
        width: 22, height: 22, borderRadius: 11,
        background: '#FFFFFF',
        transition: 'left 0.2s',
        boxShadow: '0 1px 4px rgba(0,0,0,0.2)',
      }} />
    </button>
  )
}
