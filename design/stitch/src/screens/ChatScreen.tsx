import { useState, useRef, useEffect } from 'react'
import type { Screen, NavTab } from '../App'

interface Props {
  navigate: (s: Screen) => void
  darkMode: boolean
  navTab: NavTab
  onTabChange: (t: NavTab) => void
}

interface Message {
  id: number
  role: 'user' | 'ai'
  text: string
  time: string
}

const suggestions = [
  'Explain quantum computing simply',
  'Write a Python web scraper',
  'Summarize this article',
  'Help me debug my code',
]

const initialMessages: Message[] = [
  { id: 1, role: 'ai', text: "Hello! I'm your AI Assistant. How can I help you today? 😊", time: '9:40 AM' },
  { id: 2, role: 'user', text: 'Can you explain how neural networks work?', time: '9:41 AM' },
  {
    id: 3, role: 'ai',
    text: "Neural networks are computational models inspired by the human brain. Here's a breakdown:\n\n**Structure:**\n- Input layer → Hidden layers → Output layer\n- Each layer contains interconnected nodes (neurons)\n\n**How they learn:**\n1. Data flows forward through layers\n2. Each neuron applies a weight and activation function\n3. The network compares output to expected results\n4. Backpropagation adjusts weights to minimize error\n\n```python\nimport torch.nn as nn\n\nmodel = nn.Sequential(\n    nn.Linear(784, 128),\n    nn.ReLU(),\n    nn.Linear(128, 10)\n)\n```\n\nWant me to dive deeper into any specific aspect?",
    time: '9:41 AM'
  },
]

export default function ChatScreen({ navigate, darkMode }: Props) {
  const [messages, setMessages] = useState<Message[]>(initialMessages)
  const [input, setInput] = useState('')
  const [isTyping, setIsTyping] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  const bg = darkMode ? '#1C1B1F' : '#FFFBFE'
  const card = darkMode ? '#2B2930' : '#FFFFFF'
  const text = darkMode ? '#E6E1E5' : '#1C1B1F'
  const sub = darkMode ? '#CAC4D0' : '#49454F'
  const border = darkMode ? '#49454F' : '#E7E0EC'
  const inputBg = darkMode ? '#2B2930' : '#F3EDF7'

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, isTyping])

  const sendMessage = () => {
    if (!input.trim()) return
    const userMsg: Message = { id: Date.now(), role: 'user', text: input, time: '9:42 AM' }
    setMessages(prev => [...prev, userMsg])
    setInput('')
    setIsTyping(true)
    setTimeout(() => {
      setIsTyping(false)
      setMessages(prev => [...prev, {
        id: Date.now() + 1,
        role: 'ai',
        text: "That's a great question! I'm processing your request and here's what I think...\n\nBased on the context you've provided, I can offer several insights that might help you move forward effectively.",
        time: '9:42 AM',
      }])
    }, 2000)
  }

  return (
    <div style={{ background: bg, height: '100%', minHeight: 768, display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '12px 16px', borderBottom: `1px solid ${border}`, display: 'flex', alignItems: 'center', gap: 12, background: card }}>
        <button onClick={() => navigate('home')} style={{ width: 36, height: 36, borderRadius: 18, background: darkMode ? '#49454F' : '#F3EDF7', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke={text} strokeWidth="2" strokeLinecap="round"><path d="M12 5l-5 5 5 5" /></svg>
        </button>
        <div style={{ width: 40, height: 40, borderRadius: 20, background: 'linear-gradient(135deg, #6750A4, #9C89C4)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ fontSize: 20 }}>🤖</span>
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 15, fontWeight: 600, color: text }}>AI Assistant</div>
          <div style={{ fontSize: 12, color: '#386A20', display: 'flex', alignItems: 'center', gap: 4 }}>
            <div style={{ width: 6, height: 6, borderRadius: 3, background: '#386A20' }} />
            Online
          </div>
        </div>
        <button style={{ width: 36, height: 36, borderRadius: 18, background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke={sub} strokeWidth="1.8"><circle cx="10" cy="4" r="1.5" fill={sub} /><circle cx="10" cy="10" r="1.5" fill={sub} /><circle cx="10" cy="16" r="1.5" fill={sub} /></svg>
        </button>
      </div>

      {/* Messages */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px 16px 8px' }}>
        {messages.map(msg => (
          <div key={msg.id} style={{ marginBottom: 16, display: 'flex', flexDirection: msg.role === 'user' ? 'row-reverse' : 'row', gap: 10, alignItems: 'flex-end' }}>
            {msg.role === 'ai' && (
              <div style={{ width: 32, height: 32, borderRadius: 16, background: 'linear-gradient(135deg, #6750A4, #9C89C4)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, fontSize: 14 }}>🤖</div>
            )}
            <div style={{ maxWidth: '75%' }}>
              <div style={{
                padding: '12px 16px',
                borderRadius: msg.role === 'user' ? '20px 20px 4px 20px' : '20px 20px 20px 4px',
                background: msg.role === 'user' ? '#6750A4' : card,
                border: msg.role === 'user' ? 'none' : `1px solid ${border}`,
                boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
              }}>
                <FormattedText text={msg.text} isUser={msg.role === 'user'} text_color={text} />
              </div>
              {msg.role === 'ai' && (
                <div style={{ display: 'flex', gap: 12, marginTop: 6, padding: '0 4px' }}>
                  {['📋', '🔄', '👍', '👎'].map((emoji, i) => (
                    <button key={i} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 13, opacity: 0.6 }}>{emoji}</button>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}

        {isTyping && (
          <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end', marginBottom: 16 }}>
            <div style={{ width: 32, height: 32, borderRadius: 16, background: 'linear-gradient(135deg, #6750A4, #9C89C4)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14 }}>🤖</div>
            <div style={{ padding: '14px 16px', borderRadius: '20px 20px 20px 4px', background: card, border: `1px solid ${border}`, display: 'flex', gap: 5, alignItems: 'center' }}>
              {[0, 1, 2].map(i => (
                <div key={i} style={{ width: 7, height: 7, borderRadius: '50%', background: '#6750A4', animation: `typing-dot 1.4s ${i * 0.2}s ease-in-out infinite` }} />
              ))}
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Suggestions */}
      {messages.length <= 3 && (
        <div style={{ padding: '0 16px 12px', display: 'flex', gap: 8, overflowX: 'auto' }}>
          {suggestions.map((s, i) => (
            <button key={i} onClick={() => setInput(s)} style={{ flexShrink: 0, padding: '8px 14px', borderRadius: 20, background: card, border: `1px solid ${border}`, fontSize: 12, color: '#6750A4', cursor: 'pointer', fontWeight: 500 }}>
              {s}
            </button>
          ))}
        </div>
      )}

      {/* Input */}
      <div style={{ padding: '8px 16px 20px', borderTop: `1px solid ${border}`, background: card }}>
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, background: inputBg, borderRadius: 24, padding: '8px 8px 8px 16px', border: `1.5px solid ${border}` }}>
          <div style={{ display: 'flex', gap: 4 }}>
            {['📎', '📷', '🎙️'].map((icon, i) => (
              <button key={i} style={{ width: 32, height: 32, borderRadius: 16, background: 'none', border: 'none', cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{icon}</button>
            ))}
          </div>
          <textarea
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && !e.shiftKey && (e.preventDefault(), sendMessage())}
            placeholder="Message AI Assistant..."
            rows={1}
            style={{ flex: 1, background: 'none', border: 'none', outline: 'none', fontSize: 15, color: text, resize: 'none', fontFamily: 'inherit', lineHeight: 1.5, maxHeight: 100 }}
          />
          <button
            onClick={sendMessage}
            style={{ width: 40, height: 40, borderRadius: 20, background: input.trim() ? '#6750A4' : '#CAC4D0', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, transition: 'background 0.2s' }}
          >
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="white" strokeWidth="2.2" strokeLinecap="round"><path d="M2 9h14M9 2l7 7-7 7" /></svg>
          </button>
        </div>
      </div>
    </div>
  )
}

function FormattedText({ text, isUser, text_color }: { text: string; isUser: boolean; text_color: string }) {
  const parts = text.split(/(```[\s\S]*?```|\*\*[^*]+\*\*)/g)
  return (
    <div style={{ fontSize: 14, lineHeight: 1.6, color: isUser ? '#FFFFFF' : text_color }}>
      {parts.map((part, i) => {
        if (part.startsWith('```') && part.endsWith('```')) {
          const code = part.slice(3, -3).replace(/^\w+\n/, '')
          return (
            <pre key={i} style={{ background: 'rgba(0,0,0,0.1)', borderRadius: 8, padding: '10px 12px', fontSize: 12, overflow: 'auto', margin: '8px 0', fontFamily: 'monospace', color: isUser ? '#FFFFFF' : text_color }}>
              {code}
            </pre>
          )
        }
        if (part.startsWith('**') && part.endsWith('**')) {
          return <strong key={i}>{part.slice(2, -2)}</strong>
        }
        return <span key={i} style={{ whiteSpace: 'pre-wrap' }}>{part}</span>
      })}
    </div>
  )
}
