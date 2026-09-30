import { useEffect } from 'react'
import type { Screen } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
}

export default function SplashScreen({ navigate }: Props) {
  useEffect(() => {
    const t = setTimeout(() => navigate('onboarding'), 2800)
    return () => clearTimeout(t)
  }, [navigate])

  return (
    <div style={{
      height: '100%',
      minHeight: 768,
      background: 'linear-gradient(160deg, #1a0533 0%, #381E72 40%, #6750A4 75%, #9C89C4 100%)',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 24,
      position: 'relative',
      overflow: 'hidden',
    }}>
      {/* Ambient orbs */}
      <div style={{ position: 'absolute', top: -60, right: -60, width: 220, height: 220, borderRadius: '50%', background: 'rgba(208,188,255,0.12)', filter: 'blur(40px)' }} />
      <div style={{ position: 'absolute', bottom: 80, left: -80, width: 280, height: 280, borderRadius: '50%', background: 'rgba(103,80,164,0.3)', filter: 'blur(60px)' }} />

      {/* Logo mark */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 20, animation: 'fadeIn 0.8s ease forwards' }}>
        <div style={{
          width: 88,
          height: 88,
          borderRadius: 28,
          background: 'rgba(255,255,255,0.12)',
          backdropFilter: 'blur(20px)',
          border: '1px solid rgba(255,255,255,0.2)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
        }}>
          <AILogoMark />
        </div>

        <div style={{ textAlign: 'center' }}>
          <h1 style={{ margin: 0, fontSize: 32, fontWeight: 700, color: '#FFFFFF', letterSpacing: -0.5, lineHeight: 1 }}>
            AI Assistant
          </h1>
          <p style={{ margin: '6px 0 0', fontSize: 14, color: 'rgba(255,255,255,0.6)', letterSpacing: 0.5 }}>
            Your intelligent companion
          </p>
        </div>
      </div>

      {/* Loading dots */}
      <div style={{ display: 'flex', gap: 8, marginTop: 48, animation: 'fadeIn 1s 0.5s ease forwards', opacity: 0 }}>
        {[0, 1, 2].map(i => (
          <div key={i} style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            background: 'rgba(255,255,255,0.8)',
            animation: `typing-dot 1.4s ${i * 0.2}s ease-in-out infinite`,
          }} />
        ))}
      </div>

      {/* Bottom label */}
      <div style={{ position: 'absolute', bottom: 48, fontSize: 12, color: 'rgba(255,255,255,0.35)', letterSpacing: 1.5, textTransform: 'uppercase' }}>
        Powered by Gemini
      </div>
    </div>
  )
}

function AILogoMark() {
  return (
    <svg width="44" height="44" viewBox="0 0 44 44" fill="none">
      <path d="M22 8c-1.5 0-2.7 1.5-2.7 3.3 0 1.1.4 2 1 2.6C14.8 15.6 11 19.5 11 24.3c0 5.4 4.9 9.7 11 9.7s11-4.3 11-9.7c0-4.8-3.8-8.7-9.3-10.4.6-.6 1-1.5 1-2.6C24.7 9.5 23.5 8 22 8z" fill="white" opacity="0.9" />
      <circle cx="17" cy="23" r="2" fill="#6750A4" />
      <circle cx="27" cy="23" r="2" fill="#6750A4" />
      <path d="M17 28c1.3 1.5 3 2.3 5 2.3s3.7-.8 5-2.3" stroke="#6750A4" strokeWidth="1.5" strokeLinecap="round" fill="none" />
    </svg>
  )
}
