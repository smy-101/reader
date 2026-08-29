import {useCallback, useEffect, useRef, useState} from 'react'
import type {BookListItem, ChatMessage, ChatSession, Citation} from '@reader/api-client'
import {api} from '../client'
import {ChatComposer} from './chat/ChatComposer'
import {MessageBubble} from './chat/MessageBubble'
import {citationsToRefs, CitationBar, type CitationView} from './chat/CitationBar'

/**
 * 跨书 AI 伴读面板(S4):书库页全局入口打开,与阅读器内 AI 面板同构——
 * 会话列表(最近活跃)+ 消息流(含引用条)+ 输入框 + 重命名/删除(FR-304)。
 * 提问走全局端点(全库就绪书集合纯向量检索,D-36),恒为检索式;引用随 meta 事件
 * 在流式开始前可见,携带书名与章节;点击引用打开对应的书(onOpenBook 经应用顶层
 * 状态转入阅读器后执行 S3 同款定位);书已删除的引用降级为「原书已删除」占位不可点(D-33)。
 */
export function GlobalAiPanel({books, onOpenBook, onClose}: {
    /** 书库列表:引用降级裁决(书不在列表 = 已删除)与入口上下文同源 */
    books: BookListItem[]
    onOpenBook: (bookId: number, citation: CitationView) => void
    onClose: () => void
}) {
    const [sessions, setSessions] = useState<ChatSession[]>([])
    const [activeId, setActiveId] = useState<number | null>(null)
    const [messages, setMessages] = useState<ChatMessage[]>([])
    const [input, setInput] = useState('')
    const [streamText, setStreamText] = useState<string | null>(null)
    /** 流式文本镜像(updater 外读,避免 setState 嵌套副作用;StrictMode 下 updater 双调会重复落消息) */
    const streamTextRef = useRef('')
    const [askError, setAskError] = useState<string | null>(null)
    const [note, setNote] = useState<string | null>(null)
    const [renaming, setRenaming] = useState<number | null>(null)
    const [renameText, setRenameText] = useState('')
    const messagesEndRef = useRef<HTMLDivElement | null>(null)
    /** 本次流式的检索引用(随 meta 下发;流式开始前即可渲染) */
    const [liveCitations, setLiveCitations] = useState<Citation[] | null>(null)
    /** meta 引用镜像(onDone 落助手消息 refs,与后端落库同形) */
    const liveCitationsRef = useRef<Citation[] | null>(null)

    const activeSession = sessions.find(s => s.id === activeId) ?? null
    const streaming = streamText !== null

    /** 引用降级裁决(D-33):引用里的书不在书库列表 → 已删除,占位且不可跳。 */
    const isBookAvailable = useCallback(
        (bookId: number) => books.some(b => b.id === bookId), [books])

    const loadMessages = useCallback(async (sessionId: number) => {
        try {
            setMessages(await api.listSessionMessages(sessionId))
        } catch (e) {
            setAskError(e instanceof Error ? e.message : String(e))
        }
    }, [])

    // 打开面板:跨书会话列表 + 最近活跃会话的消息;StrictMode 双调用下只引导一次
    const bootstrappedRef = useRef(false)
    useEffect(() => {
        if (bootstrappedRef.current) return
        bootstrappedRef.current = true
        void (async () => {
            try {
                const list = await api.listGlobalSessions()
                setSessions(list)
                const first = list[0]
                if (first) {
                    setActiveId(first.id)
                    await loadMessages(first.id)
                }
            } catch (e) {
                setAskError(e instanceof Error ? e.message : String(e))
            }
        })()
    }, [loadMessages])

    // 重开面板收敛在途回复(D-44 无推送口径):点击跨书引用会带着进行中的流切进阅读器,
    // 重开时末条若仍是 user(回复在途/流被遗留),轮询直至助手消息落库(上限 30s);
    // 本地面板正在流式或已收敛时自然停。
    const pendingTriesRef = useRef(0)
    useEffect(() => {
        const last = messages.length > 0 ? messages[messages.length - 1] : null
        const pendingReply = last != null && last.role === 'user'
        if (!pendingReply) {
            pendingTriesRef.current = 0 // 已收敛(或无消息):重置
            return
        }
        if (streaming || activeId == null || pendingTriesRef.current >= 60) return // 60 次(30s)后放弃
        const sessionId = activeId
        const timer = setTimeout(() => {
            pendingTriesRef.current += 1
            void loadMessages(sessionId)
        }, 500)
        return () => clearTimeout(timer)
    }, [messages, streaming, activeId, loadMessages])

    // 流式期间与消息变化时滚动到底
    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({block: 'nearest'})
    }, [messages, streamText])

    async function send() {
        const content = input.trim()
        if (!content || streamText !== null) return
        setAskError(null)
        setNote(null)

        const tempId = -Date.now()
        setMessages(prev => [...prev, {
            id: tempId, sessionId: activeId ?? 0, role: 'user' as const,
            content, refs: null, createdAt: '',
        }])
        setInput('')
        streamTextRef.current = ''
        setStreamText('')
        setLiveCitations([]) // 先挂空引用条(流式开始前占位)
        liveCitationsRef.current = null

        try {
            await api.askGlobalStream({content, sessionId: activeId}, {
                onMeta: meta => {
                    setMessages(prev => prev.map(m => (m.id === tempId ? {...m, id: meta.userMessageId} : m)))
                    setActiveId(meta.sessionId)
                    liveCitationsRef.current = meta.citations ?? null
                    setLiveCitations(meta.citations ?? null)
                    // 新消息刷新活跃度:该会话置顶,面板次序与"最近活跃"口径一致
                    setSessions(prev => prev.some(s => s.id === meta.sessionId)
                        ? [
                            {...prev.find(s => s.id === meta.sessionId)!, title: meta.sessionTitle},
                            ...prev.filter(s => s.id !== meta.sessionId),
                        ]
                        : [{id: meta.sessionId, bookId: null, title: meta.sessionTitle, createdAt: '', updatedAt: ''}, ...prev])
                },
                onDelta: text => {
                    streamTextRef.current += text
                    setStreamText(streamTextRef.current)
                },
                onDone: done => {
                    const content = streamTextRef.current
                    if (content) {
                        // 检索引用随助手消息展示(与后端落库 refs 同形,刷新后仍在)
                        setMessages(msgs => [...msgs, {
                            id: done.assistantMessageId, sessionId: activeId ?? 0,
                            role: 'assistant' as const, content, refs: citationsToRefs(liveCitationsRef.current), createdAt: '',
                        }])
                    }
                    setLiveCitations(null)
                    setStreamText(null)
                    if (done.note) setNote(done.note)
                },
                onError: message => {
                    const partial = streamTextRef.current
                    if (partial) {
                        // 中断:已到内容照常展示(与后端落库口径一致)
                        setMessages(msgs => [...msgs, {
                            id: -Date.now(), sessionId: activeId ?? 0,
                            role: 'assistant' as const, content: partial, refs: null, createdAt: '',
                        }])
                    }
                    setStreamText(null)
                    setAskError(message)
                },
            })
        } catch (e) {
            setStreamText(null)
            setLiveCitations(null)
            setAskError(e instanceof Error ? e.message : String(e))
        }
    }

    async function startNewSession() {
        setActiveId(null)
        setMessages([])
        setAskError(null)
        setNote(null)
        setLiveCitations(null)
    }

    async function confirmRename(sessionId: number) {
        const title = renameText.trim()
        if (!title) return
        try {
            const updated = await api.renameSession(sessionId, title)
            setSessions(prev => prev.map(s => (s.id === sessionId ? updated : s)))
        } catch (e) {
            setAskError(e instanceof Error ? e.message : String(e))
        } finally {
            setRenaming(null)
        }
    }

    async function removeSession(sessionId: number) {
        try {
            await api.deleteSession(sessionId)
            const rest = sessions.filter(s => s.id !== sessionId)
            setSessions(rest)
            if (activeId === sessionId) {
                setActiveId(rest[0]?.id ?? null)
                if (rest[0]) await loadMessages(rest[0].id)
                else setMessages([])
            }
        } catch (e) {
            setAskError(e instanceof Error ? e.message : String(e))
        }
    }

    async function openSession(sessionId: number) {
        if (streaming || sessionId === activeId) return
        setActiveId(sessionId)
        setAskError(null)
        setNote(null)
        setLiveCitations(null)
        await loadMessages(sessionId)
    }

    /** 点击引用:打开对应的书进入阅读器(章 + 摘录定位由应用顶层状态传递,03 票)。 */
    function jump(citation: CitationView) {
        if (citation.bookId == null) return
        onOpenBook(citation.bookId, citation)
    }

    return (
        <div className="dialog-backdrop global-ai-backdrop">
            <div className="dialog global-ai-dialog" data-testid="global-ai-panel">
                <div className="ai-panel-header">
                    <h2>AI 伴读 · 跨书</h2>
                    <button onClick={() => void startNewSession()} data-testid="global-ai-new-session">新会话</button>
                    <button onClick={onClose} data-testid="global-ai-close">关闭</button>
                </div>

                <div className="ai-sessions" data-testid="global-ai-session-list">
                    {sessions.map(s => (
                        <div key={s.id}
                             className={`ai-session-item ${s.id === activeId ? 'active' : ''}`}
                             data-testid="global-ai-session-item">
                            {renaming === s.id ? (
                                <>
                                    <input autoFocus value={renameText}
                                           onChange={e => setRenameText(e.target.value)}
                                           onKeyDown={e => e.key === 'Enter' && void confirmRename(s.id)}
                                           data-testid="global-ai-rename-input"/>
                                    <button onClick={() => void confirmRename(s.id)}
                                            data-testid="global-ai-rename-confirm">确定</button>
                                    <button onClick={() => setRenaming(null)}>取消</button>
                                </>
                            ) : (
                                <>
                                    <button className="ai-session-title" onClick={() => void openSession(s.id)}
                                            data-testid="global-ai-session-title">{s.title}</button>
                                    <button onClick={() => {
                                        setRenaming(s.id)
                                        setRenameText(s.title)
                                    }} data-testid="global-ai-rename-button">重命名</button>
                                    <button onClick={() => void removeSession(s.id)}
                                            data-testid="global-ai-delete-session">删除</button>
                                </>
                            )}
                        </div>
                    ))}
                    {sessions.length === 0 && <p className="hint">还没有跨书会话;提问即创建。</p>}
                </div>

                <div className="ai-messages" data-testid="global-ai-messages">
                    {messages.map(m => <MessageBubble key={m.id} message={m} onJump={jump} isBookAvailable={isBookAvailable}/>)}
                    {liveCitations !== null && (
                        <CitationBar citations={liveCitations} onJump={jump} testid="global-ai-live-citations"/>
                    )}
                    {streaming && (
                        <div className="ai-msg assistant" data-testid="global-ai-streaming">
                            <div className="ai-msg-content">{streamText || '…'}</div>
                        </div>
                    )}
                    {messages.length === 0 && !streaming && (
                        <p className="hint">问点什么吧,比如“这两本书怎么看同一个问题”。</p>
                    )}
                    <div ref={messagesEndRef}/>
                </div>

                {note && <p className="ai-note" data-testid="global-ai-note">{note}</p>}
                {askError && <p className="error" role="alert" data-testid="global-ai-error">{askError}</p>}

                <div className="ai-input-area">
                    <ChatComposer
                        value={input}
                        onChange={setInput}
                        onSend={send}
                        streaming={streaming}
                        placeholder={activeSession ? '继续这段跨书讨论…' : '就整个书库提问(自动创建跨书会话)'}
                        testPrefix="global-ai"/>
                </div>
            </div>
        </div>
    )
}
