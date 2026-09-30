import { useState } from 'react'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

const sampleCode = `def calculate_fibonacci(n: int) -> list[int]:
    """Generate fibonacci sequence up to n terms."""
    if n <= 0:
        return []
    elif n == 1:
        return [0]

    fib = [0, 1]
    for i in range(2, n):
        fib.append(fib[i-1] + fib[i-2])

    return fib

# Usage
result = calculate_fibonacci(10)
print(result)`

const languages = ['Python', 'JavaScript', 'TypeScript', 'Kotlin', 'Swift', 'Go', 'Rust', 'Java']

const actions = [
  { id: 'explain', label: 'Explain', icon: '💡', color: '#6750A4' },
  { id: 'fix', label: 'Fix Bugs', icon: '🐛', color: '#B3261E' },
  { id: 'optimize', label: 'Optimize', icon: '⚡', color: '#0061A4' },
  { id: 'test', label: 'Gen Tests', icon: '🧪', color: '#386A20' },
]

const aiResponses: Record<string, string> = {
  explain: "This Python function generates a Fibonacci sequence:\n\n• Takes integer `n` as input\n• Handles edge cases (n≤0 returns empty list)\n• Uses iterative approach for O(n) time complexity\n• Space complexity is O(n) to store the sequence\n\nThe function is clean and well-documented with type hints.",
  fix: "The code looks correct! Minor suggestions:\n• Add input validation: `if not isinstance(n, int): raise TypeError`\n• Consider using generators for large sequences to reduce memory\n• The docstring could include return type description",
  optimize: "For memory optimization, use a generator:\n\n```python\ndef fibonacci_gen(n):\n    a, b = 0, 1\n    for _ in range(n):\n        yield a\n        a, b = b, a + b\n```\n\nThis uses O(1) space instead of O(n).",
  test: "```python\nimport pytest\n\ndef test_fibonacci_basic():\n    assert calculate_fibonacci(5) == [0,1,1,2,3]\n\ndef test_fibonacci_empty():\n    assert calculate_fibonacci(0) == []\n    assert calculate_fibonacci(-1) == []\n\ndef test_fibonacci_single():\n    assert calculate_fibonacci(1) == [0]\n```",
}

export default function CodeScreen({ navigate, darkMode }: Props) {
  const [code, setCode] = useState(sampleCode)
  const [lang, setLang] = useState('Python')
  const [result, setResult] = useState('')
  const [activeAction, setActiveAction] = useState('')
  const [loading, setLoading] = useState(false)
  const [showLangPicker, setShowLangPicker] = useState(false)

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'

  const runAction = (action: string) => {
    setActiveAction(action)
    setLoading(true)
    setResult('')
    setTimeout(() => {
      setResult(aiResponses[action] || '')
      setLoading(false)
    }, 1400)
  }

  return (
    <div style={{ background: bg, minHeight: 768, display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '12px 16px', borderBottom: `1px solid ${border}`, display: 'flex', alignItems: 'center', gap: 12, background: card }}>
        <button onClick={() => navigate('home')} style={{ width: 36, height: 36, borderRadius: 18, background: darkMode ? '#49454F' : '#F3EDF7', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke={text} strokeWidth="2" strokeLinecap="round"><path d="M12 5l-5 5 5 5" /></svg>
        </button>
        <div style={{ flex: 1 }}>
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 700, color: text }}>Code Assistant</h2>
        </div>
        <button onClick={() => setShowLangPicker(!showLangPicker)} style={{ padding: '6px 14px', borderRadius: 16, background: '#E8DEF8', border: '1px solid #D0BCFF', cursor: 'pointer', fontSize: 13, fontWeight: 600, color: '#6750A4' }}>
          {lang} ▾
        </button>
      </div>

      {/* Language picker */}
      {showLangPicker && (
        <div style={{ background: card, borderBottom: `1px solid ${border}`, padding: '8px 12px', display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {languages.map(l => (
            <button key={l} onClick={() => { setLang(l); setShowLangPicker(false) }} style={{ padding: '5px 12px', borderRadius: 14, background: l === lang ? '#6750A4' : darkMode ? '#49454F' : '#F3EDF7', border: 'none', color: l === lang ? '#fff' : text, fontSize: 12, fontWeight: 500, cursor: 'pointer' }}>
              {l}
            </button>
          ))}
        </div>
      )}

      <div style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column' }}>
        {/* Code editor */}
        <div style={{ margin: '16px 16px 0', borderRadius: 16, overflow: 'hidden', border: `1px solid ${border}` }}>
          <div style={{ background: '#1e1e2e', padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 6 }}>
            {['#ff5f57', '#ffbd2e', '#28ca41'].map((c, i) => <div key={i} style={{ width: 10, height: 10, borderRadius: 5, background: c }} />)}
            <span style={{ marginLeft: 8, fontSize: 12, color: '#888', fontFamily: 'monospace' }}>main.{lang === 'Python' ? 'py' : lang === 'Kotlin' ? 'kt' : lang === 'Swift' ? 'swift' : 'js'}</span>
          </div>
          <textarea
            value={code}
            onChange={e => setCode(e.target.value)}
            style={{
              width: '100%', minHeight: 200,
              background: '#1e1e2e',
              color: '#cdd6f4',
              fontFamily: 'monospace',
              fontSize: 12.5,
              lineHeight: 1.7,
              padding: '12px 16px',
              border: 'none',
              outline: 'none',
              resize: 'vertical',
              boxSizing: 'border-box',
              display: 'block',
            }}
          />
        </div>

        {/* Action buttons */}
        <div style={{ padding: '12px 16px', display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8 }}>
          {actions.map(action => (
            <button
              key={action.id}
              onClick={() => runAction(action.id)}
              style={{
                padding: '10px 6px',
                borderRadius: 14,
                background: activeAction === action.id ? action.color : card,
                border: `1.5px solid ${activeAction === action.id ? action.color : border}`,
                cursor: 'pointer',
                display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
              }}
            >
              <span style={{ fontSize: 20 }}>{action.icon}</span>
              <span style={{ fontSize: 11, fontWeight: 600, color: activeAction === action.id ? '#fff' : text }}>{action.label}</span>
            </button>
          ))}
        </div>

        {/* Result */}
        {(loading || result) && (
          <div style={{ margin: '0 16px 16px', borderRadius: 16, background: card, border: `1px solid ${border}`, padding: '14px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <span style={{ fontSize: 16 }}>✨</span>
              <span style={{ fontSize: 13, fontWeight: 700, color: text }}>AI Analysis</span>
            </div>
            {loading ? (
              <div style={{ display: 'flex', gap: 5 }}>{[0,1,2].map(i => <div key={i} style={{ width: 7, height: 7, borderRadius: '50%', background: '#6750A4', animation: `typing-dot 1.4s ${i*0.2}s ease-in-out infinite` }} />)}</div>
            ) : (
              <CodeFormattedText text={result} text_color={sub} />
            )}
          </div>
        )}

        {/* Copy/Share row */}
        {result && (
          <div style={{ padding: '0 16px 20px', display: 'flex', gap: 10 }}>
            <button style={{ flex: 1, height: 44, borderRadius: 22, background: card, border: `1.5px solid ${border}`, color: text, fontSize: 13, fontWeight: 500, cursor: 'pointer' }}>📋 Copy Code</button>
            <button style={{ flex: 1, height: 44, borderRadius: 22, background: '#6750A4', border: 'none', color: '#fff', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>↗ Share</button>
          </div>
        )}
      </div>
    </div>
  )
}

function CodeFormattedText({ text, text_color }: { text: string; text_color: string }) {
  const parts = text.split(/(```[\s\S]*?```)/g)
  return (
    <div style={{ fontSize: 13, lineHeight: 1.6, color: text_color }}>
      {parts.map((part, i) => {
        if (part.startsWith('```') && part.endsWith('```')) {
          return (
            <pre key={i} style={{ background: '#1e1e2e', color: '#cdd6f4', borderRadius: 10, padding: '10px 14px', fontSize: 12, overflow: 'auto', margin: '8px 0', fontFamily: 'monospace' }}>
              {part.slice(3, -3).replace(/^\w+\n/, '')}
            </pre>
          )
        }
        return <span key={i} style={{ whiteSpace: 'pre-wrap' }}>{part}</span>
      })}
    </div>
  )
}
