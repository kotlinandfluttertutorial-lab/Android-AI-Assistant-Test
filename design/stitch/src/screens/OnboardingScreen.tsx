import { useState } from 'react'
import type { Screen } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
}

const slides = [
  {
    icon: '🤖',
    title: 'Meet Your AI\nAssistant',
    desc: 'Powered by advanced AI, I can help you write, analyze, code, translate, and much more — all in one place.',
    gradient: ['#6750A4', '#9C89C4'],
  },
  {
    icon: '⚡',
    title: 'Limitless\nCapabilities',
    desc: 'Chat, analyze PDFs, process images, write code, transcribe voice, and access 12+ specialized AI tools.',
    gradient: ['#7D5260', '#B26978'],
  },
  {
    icon: '🔒',
    title: 'Private &\nSecure',
    desc: 'Your conversations are encrypted and never shared. Your data stays yours — always.',
    gradient: ['#386A20', '#5A9E3C'],
  },
]

export default function OnboardingScreen({ navigate }: Props) {
  const [current, setCurrent] = useState(0)
  const slide = slides[current]

  return (
    <div style={{ height: '100%', minHeight: 768, background: '#FFFBFE', display: 'flex', flexDirection: 'column' }}>
      {/* Skip */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '16px 24px' }}>
        <button
          onClick={() => navigate('login')}
          style={{ background: 'none', border: 'none', color: '#6750A4', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}
        >
          Skip
        </button>
      </div>

      {/* Illustration area */}
      <div style={{
        flex: '0 0 340px',
        background: `linear-gradient(135deg, ${slide.gradient[0]}, ${slide.gradient[1]})`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        position: 'relative',
        overflow: 'hidden',
        margin: '0 24px',
        borderRadius: 28,
      }}>
        <div style={{ position: 'absolute', top: -40, right: -40, width: 180, height: 180, borderRadius: '50%', background: 'rgba(255,255,255,0.08)' }} />
        <div style={{ position: 'absolute', bottom: -60, left: -20, width: 200, height: 200, borderRadius: '50%', background: 'rgba(0,0,0,0.1)' }} />
        <div style={{ fontSize: 96, lineHeight: 1, position: 'relative', zIndex: 1, filter: 'drop-shadow(0 8px 24px rgba(0,0,0,0.2))' }} key={current} className="animate-fade-in">
          {slide.icon}
        </div>
      </div>

      {/* Content */}
      <div style={{ flex: 1, padding: '36px 32px 24px', display: 'flex', flexDirection: 'column' }} key={current} className="animate-slide-up">
        <h2 style={{
          margin: 0,
          fontSize: 30,
          fontWeight: 700,
          color: '#1C1B1F',
          lineHeight: 1.2,
          letterSpacing: -0.5,
          whiteSpace: 'pre-line',
        }}>
          {slide.title}
        </h2>
        <p style={{ margin: '16px 0 0', fontSize: 16, color: '#49454F', lineHeight: 1.6 }}>
          {slide.desc}
        </p>
      </div>

      {/* Dots + Next */}
      <div style={{ padding: '0 32px 48px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', gap: 8 }}>
          {slides.map((_, i) => (
            <div key={i} style={{
              width: i === current ? 24 : 8,
              height: 8,
              borderRadius: 4,
              background: i === current ? '#6750A4' : '#CAC4D0',
              transition: 'all 0.3s ease',
              cursor: 'pointer',
            }} onClick={() => setCurrent(i)} />
          ))}
        </div>

        <button
          onClick={() => current < slides.length - 1 ? setCurrent(current + 1) : navigate('login')}
          style={{
            width: 56,
            height: 56,
            borderRadius: 28,
            background: '#6750A4',
            border: 'none',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 4px 16px rgba(103,80,164,0.4)',
          }}
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M5 12h14M12 5l7 7-7 7" />
          </svg>
        </button>
      </div>

      {current === slides.length - 1 && (
        <div style={{ padding: '0 32px 32px' }}>
          <button
            onClick={() => navigate('login')}
            style={{
              width: '100%',
              height: 52,
              borderRadius: 26,
              background: '#6750A4',
              border: 'none',
              cursor: 'pointer',
              color: '#FFFFFF',
              fontSize: 16,
              fontWeight: 600,
              boxShadow: '0 4px 16px rgba(103,80,164,0.4)',
            }}
          >
            Get Started
          </button>
        </div>
      )}
    </div>
  )
}
