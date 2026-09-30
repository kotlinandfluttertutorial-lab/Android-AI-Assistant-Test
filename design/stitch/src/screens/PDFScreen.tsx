import { useState } from 'react'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

const docs = [
  { name: 'Q4 Financial Report.pdf', size: '2.4 MB', pages: 48, date: 'Jul 12' },
  { name: 'Product Roadmap 2025.pdf', size: '1.1 MB', pages: 24, date: 'Jul 10' },
  { name: 'Research Paper - AI.pdf', size: '3.8 MB', pages: 62, date: 'Jul 8' },
]

const summary = `This Q4 Financial Report covers the company's performance across all major business segments from October to December 2024.

**Key Highlights:**
• Revenue increased 23% YoY to $2.4B
• Operating margin improved to 18.5%
• International markets now represent 42% of total revenue
• R&D investment grew by 31% to support AI initiatives

The report indicates strong growth in cloud services (+45%) while traditional licensing revenue declined 8%.`

export default function PDFScreen({ navigate, darkMode }: Props) {
  const [selected, setSelected] = useState<number | null>(null)
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState('')
  const [loading, setLoading] = useState(false)

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  const ask = () => {
    if (!question.trim()) return
    setLoading(true)
    setTimeout(() => {
      setAnswer(`Based on the Q4 Financial Report, ${question.toLowerCase().includes('revenue') ? 'total revenue for Q4 2024 reached $2.4 billion, representing a 23% year-over-year increase. Cloud services were the primary growth driver.' : 'the document contains detailed analysis across multiple financial metrics. The operating margin improved significantly to 18.5% driven by operational efficiencies and product mix optimization.'}`)
      setLoading(false)
    }, 1500)
  }

  return (
    <div style={{ background: bg, minHeight: 768, display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '12px 16px', borderBottom: `1px solid ${border}`, display: 'flex', alignItems: 'center', gap: 12, background: card }}>
        <button onClick={() => navigate('home')} style={{ width: 36, height: 36, borderRadius: 18, background: darkMode ? '#49454F' : '#F3EDF7', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke={text} strokeWidth="2" strokeLinecap="round"><path d="M12 5l-5 5 5 5" /></svg>
        </button>
        <div>
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 700, color: text }}>PDF Assistant</h2>
          <p style={{ margin: 0, fontSize: 12, color: sub }}>Analyze any document with AI</p>
        </div>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '16px' }}>
        {selected === null ? (
          <>
            {/* Upload button */}
            <button style={{
              width: '100%', padding: '24px', borderRadius: 20,
              border: `2px dashed #6750A4`, background: '#F3EDF7',
              cursor: 'pointer', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, marginBottom: 20,
            }}>
              <div style={{ width: 56, height: 56, borderRadius: 20, background: '#E8DEF8', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 28 }}>📤</div>
              <div>
                <p style={{ margin: 0, fontSize: 15, fontWeight: 600, color: '#6750A4' }}>Upload PDF</p>
                <p style={{ margin: '4px 0 0', fontSize: 13, color: sub }}>Tap to browse or drag & drop</p>
              </div>
            </button>

            {/* Document list */}
            <h3 style={{ margin: '0 0 12px', fontSize: 15, fontWeight: 600, color: text }}>Recent Documents</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {docs.map((doc, i) => (
                <button key={i} onClick={() => setSelected(i)} style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '14px 16px', borderRadius: 16, background: card, border: `1px solid ${border}`, cursor: 'pointer', textAlign: 'left', width: '100%' }}>
                  <div style={{ width: 44, height: 52, borderRadius: 8, background: '#FFD7D4', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, fontSize: 24 }}>📄</div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <p style={{ margin: 0, fontSize: 14, fontWeight: 600, color: text, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{doc.name}</p>
                    <p style={{ margin: '4px 0 0', fontSize: 12, color: sub }}>{doc.pages} pages • {doc.size} • {doc.date}</p>
                  </div>
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke={sub} strokeWidth="1.8" strokeLinecap="round"><path d="M6 3l5 5-5 5" /></svg>
                </button>
              ))}
            </div>
          </>
        ) : (
          <>
            {/* PDF viewer mockup */}
            <div style={{ borderRadius: 16, overflow: 'hidden', border: `1px solid ${border}`, marginBottom: 16 }}>
              <div style={{ background: '#B3261E', padding: '12px 16px', display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: 20 }}>📄</span>
                <div>
                  <p style={{ margin: 0, fontSize: 13, fontWeight: 600, color: '#FFFFFF' }}>{docs[selected].name}</p>
                  <p style={{ margin: 0, fontSize: 11, color: 'rgba(255,255,255,0.7)' }}>{docs[selected].pages} pages</p>
                </div>
                <button onClick={() => setSelected(null)} style={{ marginLeft: 'auto', background: 'rgba(255,255,255,0.2)', border: 'none', borderRadius: 8, color: '#fff', padding: '4px 10px', cursor: 'pointer', fontSize: 12 }}>Close</button>
              </div>
              {/* Preview pages */}
              <div style={{ background: '#f5f5f5', padding: '12px', display: 'flex', gap: 8, overflowX: 'auto' }}>
                {[1, 2, 3].map(p => (
                  <div key={p} style={{ flexShrink: 0, width: 80, height: 110, background: '#FFFFFF', borderRadius: 6, border: '1px solid #ddd', display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 4 }}>
                    <div style={{ width: '70%', height: 8, background: '#eee', borderRadius: 2 }} />
                    <div style={{ width: '90%', height: 4, background: '#f0f0f0', borderRadius: 2 }} />
                    <div style={{ width: '80%', height: 4, background: '#f0f0f0', borderRadius: 2 }} />
                    <div style={{ width: '60%', height: 4, background: '#f0f0f0', borderRadius: 2 }} />
                    <span style={{ fontSize: 10, color: '#999', marginTop: 4 }}>p.{p}</span>
                  </div>
                ))}
              </div>
            </div>

            {/* AI Summary */}
            <div style={{ background: card, borderRadius: 16, padding: '16px', border: `1px solid ${border}`, marginBottom: 14 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                <span style={{ fontSize: 16 }}>✨</span>
                <h4 style={{ margin: 0, fontSize: 14, fontWeight: 700, color: text }}>AI Summary</h4>
              </div>
              <FormattedText text={summary} color={sub} />
            </div>

            {/* Q&A */}
            <div style={{ background: card, borderRadius: 16, padding: '14px 16px', border: `1px solid ${border}` }}>
              <h4 style={{ margin: '0 0 12px', fontSize: 14, fontWeight: 700, color: text }}>Ask about this document</h4>
              <div style={{ display: 'flex', gap: 8 }}>
                <input
                  value={question}
                  onChange={e => setQuestion(e.target.value)}
                  placeholder="What was the revenue growth?"
                  onKeyDown={e => e.key === 'Enter' && ask()}
                  style={{ flex: 1, height: 44, borderRadius: 22, background: darkMode ? '#1C1B1F' : '#F3EDF7', border: `1px solid ${border}`, padding: '0 14px', fontSize: 14, color: text, outline: 'none', fontFamily: 'inherit' }}
                />
                <button onClick={ask} style={{ width: 44, height: 44, borderRadius: 22, background: '#6750A4', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round"><path d="M2 9h14M9 2l7 7-7 7" /></svg>
                </button>
              </div>
              {loading && <div style={{ marginTop: 12, display: 'flex', gap: 5, alignItems: 'center' }}>{[0,1,2].map(i => <div key={i} style={{ width: 7, height: 7, borderRadius: '50%', background: '#6750A4', animation: `typing-dot 1.4s ${i*0.2}s ease-in-out infinite` }} />)}</div>}
              {answer && !loading && (
                <div style={{ marginTop: 12, padding: '12px', borderRadius: 12, background: '#E8DEF8', fontSize: 13, color: '#21005D', lineHeight: 1.6 }}>
                  {answer}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function FormattedText({ text, color }: { text: string; color: string }) {
  const lines = text.split('\n')
  return (
    <div style={{ fontSize: 13, lineHeight: 1.6, color }}>
      {lines.map((line, i) => {
        if (line.startsWith('**') && line.endsWith('**')) return <strong key={i} style={{ display: 'block', marginTop: 4 }}>{line.slice(2,-2)}</strong>
        if (line.startsWith('•')) return <div key={i} style={{ marginLeft: 8 }}>{line}</div>
        return <span key={i} style={{ display: 'block' }}>{line}</span>
      })}
    </div>
  )
}
