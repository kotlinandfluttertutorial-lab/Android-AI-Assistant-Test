import { useState } from 'react'
import type { Screen } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
}

export default function LoginScreen({ navigate, darkMode }: Props) {
  const [mode, setMode] = useState<'login' | 'signup' | 'forgot'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [showPass, setShowPass] = useState(false)

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#CAC4D0'
  const inputBg = darkMode ? '#1C1B1F' : '#F3EDF7'

  return (
    <div style={{ minHeight: 768, background: bg, padding: '24px 24px 48px' }}>
      {/* Header */}
      <div style={{ textAlign: 'center', padding: '24px 0 32px' }}>
        <div style={{
          width: 64, height: 64, borderRadius: 20,
          background: 'linear-gradient(135deg, #6750A4, #9C89C4)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          margin: '0 auto 16px', boxShadow: '0 8px 24px rgba(103,80,164,0.3)',
        }}>
          <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
            <path d="M16 6c-1 0-1.8 1-1.8 2.2 0 .8.3 1.4.7 1.8C10.6 11.1 8 14 8 17.4c0 3.8 3.6 6.8 8 6.8s8-3 8-6.8c0-3.4-2.6-6.3-6.9-7.4.4-.4.7-1 .7-1.8C17.8 7 17 6 16 6z" fill="white" />
            <circle cx="13" cy="17" r="1.5" fill="#6750A4" />
            <circle cx="19" cy="17" r="1.5" fill="#6750A4" />
            <path d="M13 20.5c.9 1 2.1 1.5 3 1.5s2.1-.5 3-1.5" stroke="#6750A4" strokeWidth="1.2" strokeLinecap="round" fill="none" />
          </svg>
        </div>
        <h1 style={{ margin: 0, fontSize: 26, fontWeight: 700, color: text, letterSpacing: -0.3 }}>
          {mode === 'login' ? 'Welcome back' : mode === 'signup' ? 'Create account' : 'Reset password'}
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: 14, color: sub }}>
          {mode === 'login' ? 'Sign in to continue' : mode === 'signup' ? 'Join millions of users' : 'We\'ll send you a reset link'}
        </p>
      </div>

      {/* Google button */}
      {mode !== 'forgot' && (
        <button
          onClick={() => navigate('home')}
          style={{
            width: '100%', height: 52, borderRadius: 26,
            background: card, border: `1.5px solid ${border}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12,
            cursor: 'pointer', marginBottom: 20,
            boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
          }}
        >
          <GoogleIcon />
          <span style={{ fontSize: 15, fontWeight: 600, color: text }}>Continue with Google</span>
        </button>
      )}

      {/* Divider */}
      {mode !== 'forgot' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20 }}>
          <div style={{ flex: 1, height: 1, background: border }} />
          <span style={{ fontSize: 12, color: sub }}>or</span>
          <div style={{ flex: 1, height: 1, background: border }} />
        </div>
      )}

      {/* Form */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {mode === 'signup' && (
          <Input label="Full name" value={name} onChange={setName} placeholder="Firoj Khan" inputBg={inputBg} text={text} sub={sub} border={border} />
        )}
        <Input label="Email address" value={email} onChange={setEmail} placeholder="you@example.com" type="email" inputBg={inputBg} text={text} sub={sub} border={border} />
        {mode !== 'forgot' && (
          <div style={{ position: 'relative' }}>
            <Input label="Password" value={password} onChange={setPassword} placeholder="••••••••" type={showPass ? 'text' : 'password'} inputBg={inputBg} text={text} sub={sub} border={border} />
            <button
              onClick={() => setShowPass(!showPass)}
              style={{ position: 'absolute', right: 16, top: 38, background: 'none', border: 'none', cursor: 'pointer', color: sub, fontSize: 12 }}
            >
              {showPass ? 'Hide' : 'Show'}
            </button>
          </div>
        )}
      </div>

      {mode === 'login' && (
        <button onClick={() => setMode('forgot')} style={{ background: 'none', border: 'none', color: '#6750A4', fontSize: 13, fontWeight: 500, cursor: 'pointer', marginTop: 8, padding: 0 }}>
          Forgot password?
        </button>
      )}

      <button
        onClick={() => navigate('home')}
        style={{
          width: '100%', height: 52, borderRadius: 26,
          background: '#6750A4', border: 'none', cursor: 'pointer',
          color: '#FFFFFF', fontSize: 16, fontWeight: 600,
          marginTop: 24, boxShadow: '0 4px 16px rgba(103,80,164,0.35)',
        }}
      >
        {mode === 'login' ? 'Sign in' : mode === 'signup' ? 'Create account' : 'Send reset link'}
      </button>

      {mode !== 'forgot' ? (
        <p style={{ textAlign: 'center', marginTop: 20, fontSize: 14, color: sub }}>
          {mode === 'login' ? "Don't have an account? " : 'Already have an account? '}
          <button
            onClick={() => setMode(mode === 'login' ? 'signup' : 'login')}
            style={{ background: 'none', border: 'none', color: '#6750A4', fontWeight: 600, cursor: 'pointer', fontSize: 14 }}
          >
            {mode === 'login' ? 'Sign up' : 'Sign in'}
          </button>
        </p>
      ) : (
        <button onClick={() => setMode('login')} style={{ background: 'none', border: 'none', color: '#6750A4', fontWeight: 600, cursor: 'pointer', fontSize: 14, display: 'block', margin: '16px auto 0' }}>
          ← Back to sign in
        </button>
      )}
    </div>
  )
}

function Input({ label, value, onChange, placeholder, type = 'text', inputBg, text, sub, border }: {
  label: string; value: string; onChange: (v: string) => void; placeholder: string;
  type?: string; inputBg: string; text: string; sub: string; border: string;
}) {
  return (
    <div>
      <label style={{ display: 'block', fontSize: 13, fontWeight: 500, color: sub, marginBottom: 6 }}>{label}</label>
      <input
        type={type}
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        style={{
          width: '100%', height: 52, borderRadius: 14,
          background: inputBg, border: `1.5px solid ${border}`,
          padding: '0 16px', fontSize: 15, color: text,
          outline: 'none', boxSizing: 'border-box',
          fontFamily: 'inherit',
        }}
      />
    </div>
  )
}

function GoogleIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20">
      <path d="M19.6 10.23c0-.68-.06-1.36-.18-2H10v3.8h5.4a4.6 4.6 0 01-2 3.02v2.5h3.23c1.9-1.74 3-4.3 3-7.32z" fill="#4285F4" />
      <path d="M10 20c2.7 0 4.96-.9 6.62-2.44l-3.23-2.5a6.01 6.01 0 01-8.96-3.16H1.08v2.58A10 10 0 0010 20z" fill="#34A853" />
      <path d="M4.43 11.9A6.04 6.04 0 014.43 8.1V5.52H1.08a10 10 0 000 8.96l3.35-2.58z" fill="#FBBC05" />
      <path d="M10 4c1.47 0 2.8.5 3.83 1.5L16.7 2.66A10 10 0 001.08 5.52L4.43 8.1C5.1 6.15 7.37 4 10 4z" fill="#EA4335" />
    </svg>
  )
}
