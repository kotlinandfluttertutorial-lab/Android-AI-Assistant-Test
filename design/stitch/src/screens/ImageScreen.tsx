import { useState } from 'react'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

export default function ImageScreen({ navigate, darkMode }: Props) {
  const [view, setView] = useState<'picker' | 'analyzing' | 'result'>('picker')

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  const analyze = () => {
    setView('analyzing')
    setTimeout(() => setView('result'), 2200)
  }

  return (
    <div style={{ background: bg, minHeight: 768, display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '12px 16px', borderBottom: `1px solid ${border}`, display: 'flex', alignItems: 'center', gap: 12, background: card }}>
        <button onClick={() => navigate('home')} style={{ width: 36, height: 36, borderRadius: 18, background: darkMode ? '#49454F' : '#F3EDF7', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke={text} strokeWidth="2" strokeLinecap="round"><path d="M12 5l-5 5 5 5" /></svg>
        </button>
        <div>
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 700, color: text }}>Image Assistant</h2>
          <p style={{ margin: 0, fontSize: 12, color: sub }}>Analyze, describe & extract text</p>
        </div>
      </div>

      <div style={{ flex: 1, padding: '20px 16px' }}>
        {view === 'picker' && (
          <>
            {/* Action buttons */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 20 }}>
              <button onClick={analyze} style={{ padding: '20px', borderRadius: 20, background: '#E8DEF8', border: '1.5px solid #D0BCFF', cursor: 'pointer', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: 36 }}>📷</span>
                <span style={{ fontSize: 14, fontWeight: 600, color: '#6750A4' }}>Take Photo</span>
              </button>
              <button onClick={analyze} style={{ padding: '20px', borderRadius: 20, background: card, border: `1.5px solid ${border}`, cursor: 'pointer', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: 36 }}>🖼️</span>
                <span style={{ fontSize: 14, fontWeight: 600, color: text }}>Gallery</span>
              </button>
            </div>

            {/* Sample images */}
            <h3 style={{ margin: '0 0 12px', fontSize: 15, fontWeight: 600, color: text }}>Try with a sample</h3>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8 }}>
              {[
                'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=200&h=200&fit=crop&auto=format',
                'https://images.unsplash.com/photo-1555041469-a586c61ea9bc?w=200&h=200&fit=crop&auto=format',
                'https://images.unsplash.com/photo-1558618666-fcd25c85cd64?w=200&h=200&fit=crop&auto=format',
                'https://images.unsplash.com/photo-1485827404703-89b55fcc595e?w=200&h=200&fit=crop&auto=format',
                'https://images.unsplash.com/photo-1526374965328-7f61d4dc18c5?w=200&h=200&fit=crop&auto=format',
                'https://images.unsplash.com/photo-1551434678-e076c223a692?w=200&h=200&fit=crop&auto=format',
              ].map((url, i) => (
                <button key={i} onClick={analyze} style={{ aspectRatio: '1', borderRadius: 12, overflow: 'hidden', border: 'none', cursor: 'pointer', padding: 0 }}>
                  <img src={url} alt="Sample" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                </button>
              ))}
            </div>
          </>
        )}

        {view === 'analyzing' && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 500, gap: 24 }}>
            <div style={{ position: 'relative', width: 120, height: 120 }}>
              <div style={{ position: 'absolute', inset: 0, borderRadius: '50%', border: '3px solid #E8DEF8' }} />
              <div style={{ position: 'absolute', inset: 0, borderRadius: '50%', border: '3px solid transparent', borderTopColor: '#6750A4', animation: 'spin-slow 1s linear infinite' }} />
              <div style={{ position: 'absolute', inset: 16, background: '#E8DEF8', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 36 }}>🔍</div>
            </div>
            <div style={{ textAlign: 'center' }}>
              <p style={{ margin: 0, fontSize: 18, fontWeight: 600, color: text }}>Analyzing image...</p>
              <p style={{ margin: '6px 0 0', fontSize: 14, color: sub }}>Detecting objects, text & context</p>
            </div>
            {/* Progress steps */}
            <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 8 }}>
              {['Object detection', 'Text extraction (OCR)', 'Scene analysis', 'Generating description'].map((step, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', borderRadius: 12, background: card, border: `1px solid ${border}`, opacity: i < 3 ? 1 : 0.5 }}>
                  <div style={{ width: 20, height: 20, borderRadius: 10, background: i < 3 ? '#386A20' : '#CAC4D0', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11 }}>
                    {i < 3 ? '✓' : ''}
                  </div>
                  <span style={{ fontSize: 13, color: text }}>{step}</span>
                  {i === 3 && <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>{[0,1,2].map(j => <div key={j} style={{ width: 5, height: 5, borderRadius: '50%', background: '#6750A4', animation: `typing-dot 1.4s ${j*0.2}s ease-in-out infinite` }} />)}</div>}
                </div>
              ))}
            </div>
          </div>
        )}

        {view === 'result' && (
          <>
            {/* Image preview */}
            <div style={{ borderRadius: 20, overflow: 'hidden', marginBottom: 16, position: 'relative' }}>
              <img
                src="https://images.unsplash.com/photo-1485827404703-89b55fcc595e?w=375&h=240&fit=crop&auto=format"
                alt="Analyzed"
                style={{ width: '100%', height: 200, objectFit: 'cover', display: 'block' }}
              />
              <div style={{ position: 'absolute', top: 10, right: 10, padding: '4px 12px', borderRadius: 12, background: 'rgba(0,0,0,0.6)', color: '#fff', fontSize: 12 }}>
                AI Analysis ✓
              </div>
            </div>

            {/* Results */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <ResultCard title="Description" icon="📝" color="#6750A4" bg="#E8DEF8" content="A modern robotics laboratory showing a humanoid robot arm in action. The image depicts advanced automation technology with precise mechanical components." />
              <ResultCard title="Detected Objects" icon="🔍" color="#0061A4" bg="#D3E4FF" content="Robot arm (94%), Circuit boards (87%), Laboratory equipment (82%), Safety guards (76%), Control panels (71%)" />
              <ResultCard title="Extracted Text (OCR)" icon="📄" color="#386A20" bg="#D6EDCC" content="'FANUC R-2000iC Series' • 'Max Payload: 165kg' • 'DO NOT ENTER WHEN OPERATING'" />

              {/* Action buttons */}
              <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                <button onClick={() => navigate('chat')} style={{ flex: 1, height: 48, borderRadius: 24, background: '#6750A4', border: 'none', color: '#fff', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Ask about this</button>
                <button onClick={() => setView('picker')} style={{ flex: 1, height: 48, borderRadius: 24, background: card, border: `1.5px solid ${border}`, color: text, fontSize: 14, fontWeight: 500, cursor: 'pointer' }}>New Image</button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function ResultCard({ title, icon, color, bg, content }: { title: string; icon: string; color: string; bg: string; content: string }) {
  return (
    <div style={{ borderRadius: 16, overflow: 'hidden' }}>
      <div style={{ background: bg, padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 16 }}>{icon}</span>
        <span style={{ fontSize: 13, fontWeight: 700, color }}>{title}</span>
      </div>
      <div style={{ background: '#FFFFFF', padding: '12px 14px', border: `1px solid ${bg}`, borderTop: 'none', borderBottomLeftRadius: 16, borderBottomRightRadius: 16 }}>
        <p style={{ margin: 0, fontSize: 13, color: '#49454F', lineHeight: 1.6 }}>{content}</p>
      </div>
    </div>
  )
}
