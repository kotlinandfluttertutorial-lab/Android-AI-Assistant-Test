import { useState, useEffect } from 'react'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

export default function VoiceScreen({ navigate, darkMode }: Props) {
  const [state, setState] = useState<'idle' | 'listening' | 'processing' | 'speaking'>('idle')
  const [transcript, setTranscript] = useState('')
  const [response, setResponse] = useState('')

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  useEffect(() => {
    if (state === 'listening') {
      const t = setTimeout(() => {
        setTranscript("What's the weather like in New York today?")
        setState('processing')
      }, 2500)
      return () => clearTimeout(t)
    }
    if (state === 'processing') {
      const t = setTimeout(() => {
        setResponse("The weather in New York today is partly cloudy with a high of 72°F and a low of 58°F. There's a 20% chance of rain in the afternoon. Great day for a walk in Central Park!")
        setState('speaking')
      }, 1500)
      return () => clearTimeout(t)
    }
  }, [state])

  const toggle = () => {
    if (state === 'idle' || state === 'speaking') {
      setState('listening')
      setTranscript('')
      setResponse('')
    } else {
      setState('idle')
    }
  }

  const waveHeights = [0.3, 0.6, 0.9, 0.7, 1, 0.8, 0.5, 0.9, 0.6, 0.4, 0.7, 1, 0.8, 0.5, 0.3]

  return (
    <div style={{ background: bg, minHeight: 768, display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '12px 16px', display: 'flex', alignItems: 'center', gap: 12 }}>
        <button onClick={() => navigate('home')} style={{ width: 36, height: 36, borderRadius: 18, background: card, border: `1px solid ${border}`, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke={text} strokeWidth="2" strokeLinecap="round"><path d="M12 5l-5 5 5 5" /></svg>
        </button>
        <h2 style={{ margin: 0, fontSize: 18, fontWeight: 700, color: text, flex: 1 }}>Voice Assistant</h2>
      </div>

      {/* Main area */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '32px 24px' }}>
        {/* Status */}
        <p style={{ margin: '0 0 48px', fontSize: 14, color: sub, height: 20 }}>
          {state === 'idle' ? 'Tap to speak' : state === 'listening' ? 'Listening...' : state === 'processing' ? 'Processing...' : 'Speaking...'}
        </p>

        {/* Microphone button with pulse rings */}
        <div style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 48 }}>
          {state === 'listening' && (
            <>
              {[1, 2, 3].map(i => (
                <div key={i} style={{
                  position: 'absolute',
                  width: 88 + i * 40,
                  height: 88 + i * 40,
                  borderRadius: '50%',
                  background: 'rgba(103,80,164,0.15)',
                  animation: `pulse-ring 1.8s ${i * 0.3}s ease-out infinite`,
                }} />
              ))}
            </>
          )}
          <button
            onClick={toggle}
            style={{
              width: 96,
              height: 96,
              borderRadius: 48,
              background: state === 'idle' ? '#6750A4' : state === 'listening' ? '#B3261E' : state === 'processing' ? '#386A20' : '#0061A4',
              border: 'none',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: `0 8px 32px ${state === 'idle' ? 'rgba(103,80,164,0.4)' : 'rgba(0,0,0,0.3)'}`,
              transition: 'all 0.3s ease',
              position: 'relative',
              zIndex: 10,
            }}
          >
            <svg width="40" height="40" viewBox="0 0 40 40" fill="none">
              {state === 'idle' || state === 'speaking' ? (
                <>
                  <rect x="15" y="8" width="10" height="18" rx="5" fill="white" />
                  <path d="M8 22c0 6.6 5.4 12 12 12s12-5.4 12-12" stroke="white" strokeWidth="2.5" strokeLinecap="round" fill="none" />
                  <line x1="20" y1="34" x2="20" y2="38" stroke="white" strokeWidth="2.5" strokeLinecap="round" />
                </>
              ) : (
                <rect x="13" y="13" width="14" height="14" rx="3" fill="white" />
              )}
            </svg>
          </button>
        </div>

        {/* Sound wave visualization */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 3, height: 48, marginBottom: 40 }}>
          {waveHeights.map((h, i) => (
            <div key={i} style={{
              width: 4,
              borderRadius: 2,
              background: state === 'listening' ? '#6750A4' : state === 'speaking' ? '#0061A4' : '#CAC4D0',
              height: state !== 'idle' ? `${h * 100}%` : '20%',
              animation: state !== 'idle' ? `wave 1.2s ${i * 0.08}s ease-in-out infinite` : 'none',
              transition: 'height 0.3s ease',
            }} />
          ))}
        </div>

        {/* Transcript */}
        {transcript && (
          <div style={{ width: '100%', background: card, borderRadius: 20, padding: '16px 20px', border: `1px solid ${border}`, marginBottom: 16 }}>
            <p style={{ margin: '0 0 4px', fontSize: 11, fontWeight: 600, color: sub, textTransform: 'uppercase', letterSpacing: 1 }}>You said</p>
            <p style={{ margin: 0, fontSize: 16, color: text, lineHeight: 1.5 }}>{transcript}</p>
          </div>
        )}

        {/* AI Response */}
        {response && (
          <div style={{ width: '100%', background: '#E8DEF8', borderRadius: 20, padding: '16px 20px', border: '1px solid #D0BCFF' }}>
            <p style={{ margin: '0 0 4px', fontSize: 11, fontWeight: 600, color: '#6750A4', textTransform: 'uppercase', letterSpacing: 1 }}>AI Response</p>
            <p style={{ margin: 0, fontSize: 15, color: '#21005D', lineHeight: 1.6 }}>{response}</p>
          </div>
        )}
      </div>

      {/* Bottom actions */}
      <div style={{ padding: '0 24px 36px', display: 'flex', gap: 12 }}>
        <button onClick={() => navigate('chat')} style={{ flex: 1, height: 48, borderRadius: 24, background: card, border: `1.5px solid ${border}`, color: text, fontSize: 14, fontWeight: 500, cursor: 'pointer' }}>
          Switch to Chat
        </button>
        {response && (
          <button onClick={() => { setState('idle'); setTranscript(''); setResponse('') }} style={{ flex: 1, height: 48, borderRadius: 24, background: '#6750A4', border: 'none', color: '#fff', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>
            New Session
          </button>
        )}
      </div>
    </div>
  )
}
