import {useCallback, useEffect, useRef, useState} from 'react'
import type {AskInput, ChapterSummary, ChatMessage, ChatRef, ChatSession, Citation} from '@reader/api-client'
import {api} from '../client'
import type {FoliateView, RelocateDetail} from '../reader/foliate-types'

/**
 * AI 伴读面板(M3-04,S2;M4-05,S3):该书会话列表 + 消息流(含引用展示)+ 输入框;
 * 回复流式增量渲染,错误显式提示不悬挂(FR-303);会话可重命名/删除(FR-304)。
 * 目标章映射(D-31):缺省携带当前阅读位置所在章(经 relocate 详情的 section index
 * 对应 foliate sections[index].id 与后端 chapter.href 后缀匹配),显式点名章用显式值。
 * S3 定位原文(M4-05):该书嵌入完成才显示「定位原文」入口(FR-403);检索引用随
 * meta 事件在流式开始前即可见,点击跳转到对应章节并尝试以摘录文字定位(未命中停章首)。
 */

/** S1 预填:划选后从菜单“问 AI”带进面板的选中文字(05 交付)。 */
export interface PendingSelection {
    text: string
    cfi: string | null
}

export function AiPanel({bookId, view, relocateRef, pendingSelection, onClearPending, onClose}: {
    bookId: number
    view: FoliateView | null
    relocateRef: { current: RelocateDetail | null }
    pendingSelection: PendingSelection | null
    onClearPending: () => void
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
    const [, setChapters] = useState<ChapterSummary[]>([])
    const messagesEndRef = useRef<HTMLDivElement | null>(null)
    /** 章节表加载 promise(发送时 await 它,避免目标章映射在加载完成前落空) */
    const chaptersReadyRef = useRef<Promise<ChapterSummary[]> | null>(null)

    // ---- S3 定位原文(M4-05) ----
    /** 该书是否嵌入完成(当前模型):入口显隐的唯一裁决(FR-403;null = 还在判定) */
    const [embeddingReady, setEmbeddingReady] = useState<boolean | null>(null)
    /** S3 提问模式:开启后发送显式检索式提问 */
    const [retrievalMode, setRetrievalMode] = useState(false)
    /** 本次流式的检索引用(随 meta 下发;流式开始前即可渲染) */
    const [liveCitations, setLiveCitations] = useState<Citation[] | null>(null)
    /** meta 引用镜像(onDone 落助手消息 refs,与后端落库同形) */
    const liveCitationsRef = useRef<Citation[] | null>(null)

    const activeSession = sessions.find(s => s.id === activeId) ?? null
    const streaming = streamText !== null

    const loadMessages = useCallback(async (sessionId: number) => {
        try {
            setMessages(await api.listSessionMessages(sessionId))
        } catch (e) {
            setAskError(e instanceof Error ? e.message : String(e))
        }
    }, [])

    // 打开面板:会话列表 + 最近活跃会话的消息 + 章节表;StrictMode 双调用下只引导一次
    // (迟到的回调会覆盖交互后的状态,如刚改完的会话名)
    const bootstrappedRef = useRef(false)
    useEffect(() => {
        if (bootstrappedRef.current) return
        bootstrappedRef.current = true
        void (async () => {
            try {
                const list = await api.listSessions(bookId)
                setSessions(list)
                const first = list[0]
                if (first) {
                    setActiveId(first.id)
                    await loadMessages(first.id)
                }
            } catch (e) {
                setAskError(e instanceof Error ? e.message : String(e))
            }
            chaptersReadyRef.current = api.listChapters(bookId)
                .then(list => {
                    setChapters(list)
                    return list
                })
                .catch(() => [] as ChapterSummary[])
        })()
    }, [bookId, loadMessages])

    // 流式期间与消息变化时滚动到底
    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({block: 'nearest'})
    }, [messages, streamText])

    // S3 前置裁决:embedding 已配置且该书嵌入完成(当前模型)才显示「定位原文」;
    // 嵌入进行中每 2s 重查,完成即亮(未配置/未嵌入/模型已换 → 隐藏不报错,FR-403)
    useEffect(() => {
        let cancelled = false
        let timer: ReturnType<typeof setTimeout> | null = null
        const tick = async () => {
            try {
                const [settings, status] = await Promise.all([
                    api.getModelSettings(), api.getEmbeddingStatus(bookId)])
                if (cancelled) return
                const ready = settings.embeddingModel != null
                    && status.status === 'done'
                    && status.model === settings.embeddingModel
                setEmbeddingReady(ready)
                if (!ready && (status.status === 'pending' || status.status === 'running')) {
                    timer = setTimeout(() => void tick(), 2000)
                }
            } catch {
                if (!cancelled) setEmbeddingReady(false) // 读不到按不可用处理(隐藏)
            }
        }
        void tick()
        return () => {
            cancelled = true
            if (timer != null) clearTimeout(timer)
        }
    }, [bookId])

    /** 当前阅读位置 → 目标章(D-31):vendor lastLocation(每次 relocate 后更新)优先,
     * relocate 详情的 section index 对应 foliate sections[index].id,
     * 与后端 chapter.href 后缀匹配;chapters 未就绪时返回 null。 */
    const currentTargetChapter = useCallback((chaptersNow: ChapterSummary[]): { chapterId: number; cfi?: string } | null => {
        const detail = view?.lastLocation ?? relocateRef.current
        if (!detail) return null
        const sections = view?.book?.sections as { id?: string }[] | undefined
        const index = detail.section?.current
        const sectionId = sections && index != null ? sections[index]?.id : undefined
        if (!sectionId) return null
        const chapter = chaptersNow.find(c => c.href.endsWith(sectionId))
        return chapter ? {chapterId: chapter.id, cfi: detail.cfi ?? undefined} : null
    }, [view, relocateRef])

    async function send() {
        const content = input.trim()
        if (!content || streamText !== null) return
        setAskError(null)
        setNote(null)
        // 章节表就绪后再映射目标章(打开面板后立刻提问也不丢章)
        const chaptersNow = await (chaptersReadyRef.current ?? Promise.resolve([] as ChapterSummary[]))
        const target = currentTargetChapter(chaptersNow)
        const selection = pendingSelection
        // S3 检索式提问(带选中文字时 S1 优先级最高,不受影响)
        const retrieval = retrievalMode && !selection
        const askInput: AskInput = {
            content,
            sessionId: activeId,
            chapterId: target?.chapterId ?? null,
            cfi: target?.cfi ?? null,
            selection: selection ? {text: selection.text, cfi: selection.cfi} : null,
            retrieval,
        }

        // 乐观 UI:先显示本条提问(引用一并展示),meta 落定后修正 id
        const optimisticRefs: ChatRef[] = []
        if (selection) optimisticRefs.push({type: 'selection', text: selection.text, cfi: selection.cfi ?? undefined})
        if (target) {
            const ch = chaptersNow.find(c => c.id === target.chapterId)
            optimisticRefs.push({type: 'chapter', chapterId: target.chapterId, chapterTitle: ch?.title ?? null, seq: ch?.seq})
        }
        const tempId = -Date.now()
        setMessages(prev => [...prev, {
            id: tempId, sessionId: activeId ?? 0, role: 'user' as const,
            content, refs: optimisticRefs.length ? optimisticRefs : null, createdAt: '',
        }])
        setInput('')
        onClearPending()
        streamTextRef.current = ''
        setStreamText('')
        setLiveCitations(retrieval ? [] : null) // S3:先挂空引用条(流式开始前占位)
        liveCitationsRef.current = null

        try {
            await api.askStream(bookId, askInput, {
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
                        : [{id: meta.sessionId, bookId, title: meta.sessionTitle, createdAt: '', updatedAt: ''}, ...prev])
                },
                onDelta: text => {
                    streamTextRef.current += text
                    setStreamText(streamTextRef.current)
                },
                onDone: done => {
                    const content = streamTextRef.current
                    if (content) {
                        // S3 检索引用随助手消息展示(与后端落库 refs 同形,刷新后仍在)
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

    /** 点击引用跳转:到对应章节 + 尝试以摘录文字定位(命中滚动到命中处,未命中停章首)。 */
    async function jumpToCitation(citation: { chapterId?: number; chapterSeq?: number; chapterTitle?: string | null; excerpt?: string }) {
        const chaptersNow = await (chaptersReadyRef.current ?? Promise.resolve([] as ChapterSummary[]))
        const chapter = chaptersNow.find(c => c.id === citation.chapterId)
        if (!view || !chapter) return
        await view.goTo(chapter.href) // 跳到对应章节(经 foliate 既有导航能力)
        locateExcerpt(view, citation.excerpt ?? '')
    }

    return (
        <aside className="ai-panel" data-testid="ai-panel">
            <div className="ai-panel-header">
                <h2>AI 伴读</h2>
                <button onClick={() => void startNewSession()} data-testid="ai-new-session">新会话</button>
                <button onClick={onClose} data-testid="ai-panel-close">关闭</button>
            </div>

            <div className="ai-sessions" data-testid="ai-session-list">
                {sessions.map(s => (
                    <div key={s.id}
                         className={`ai-session-item ${s.id === activeId ? 'active' : ''}`}
                         data-testid="ai-session-item">
                        {renaming === s.id ? (
                            <>
                                <input autoFocus value={renameText}
                                       onChange={e => setRenameText(e.target.value)}
                                       onKeyDown={e => e.key === 'Enter' && void confirmRename(s.id)}
                                       data-testid="ai-rename-input"/>
                                <button onClick={() => void confirmRename(s.id)} data-testid="ai-rename-confirm">确定</button>
                                <button onClick={() => setRenaming(null)}>取消</button>
                            </>
                        ) : (
                            <>
                                <button className="ai-session-title" onClick={() => void openSession(s.id)}
                                        data-testid="ai-session-title">{s.title}</button>
                                <button onClick={() => {
                                    setRenaming(s.id)
                                    setRenameText(s.title)
                                }} data-testid="ai-rename-button">重命名</button>
                                <button onClick={() => void removeSession(s.id)} data-testid="ai-delete-session">删除</button>
                            </>
                        )}
                    </div>
                ))}
                {sessions.length === 0 && <p className="hint">本书还没有会话;提问即创建。</p>}
            </div>

            <div className="ai-messages" data-testid="ai-messages">
                {messages.map(m => <MessageBubble key={m.id} message={m} onJump={jumpToCitation}/>)}
                {liveCitations !== null && (
                    <CitationBar citations={liveCitations} onJump={jumpToCitation} testid="ai-live-citations"/>
                )}
                {streaming && (
                    <div className="ai-msg assistant" data-testid="ai-streaming">
                        <div className="ai-msg-content">{streamText || '…'}</div>
                    </div>
                )}
                {messages.length === 0 && !streaming && (
                    <p className="hint">问点什么吧,比如“总结一下这一章”。</p>
                )}
                <div ref={messagesEndRef}/>
            </div>

            {note && <p className="ai-note" data-testid="ai-note">{note}</p>}
            {askError && <p className="error" role="alert" data-testid="ai-error">{askError}</p>}

            <div className="ai-input-area">
                {pendingSelection && (
                    <blockquote className="ai-selection-quote" data-testid="ai-selection-quote">
                        “{pendingSelection.text}”
                        <button onClick={onClearPending} data-testid="ai-selection-clear">×
                        </button>
                    </blockquote>
                )}
                <div className="ai-input-row">
                    {embeddingReady && (
                        <button
                            className={`ai-retrieval-toggle ${retrievalMode ? 'on' : ''}`}
                            onClick={() => setRetrievalMode(v => !v)}
                            title="检索式提问:答案携带可点击的原文引用(S3)"
                            aria-pressed={retrievalMode}
                            data-testid="ai-retrieval-toggle">
                            {retrievalMode ? '定位原文:开' : '定位原文'}
                        </button>
                    )}
                    <textarea
                        value={input}
                        onChange={e => setInput(e.target.value)}
                        onKeyDown={e => {
                            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                                e.preventDefault()
                                void send()
                            }
                        }}
                        placeholder={retrievalMode
                            ? '问“作者在哪讨论过…”(检索原文定位)'
                            : activeSession ? '继续这段讨论…' : '向 AI 提问(自动创建会话)'}
                        data-testid="ai-input"
                        rows={2}
                        disabled={streaming}
                    />
                    <button className="primary" onClick={() => void send()} disabled={streaming || !input.trim()}
                            data-testid="ai-send">
                        {streaming ? '回复中…' : '发送'}
                    </button>
                </div>
            </div>
        </aside>
    )
}

function MessageBubble({message, onJump}: { message: ChatMessage; onJump: (c: {
    chapterId?: number; chapterSeq?: number; chapterTitle?: string | null; excerpt?: string
}) => void }) {
    const isUser = message.role === 'user'
    const citations = (message.refs ?? []).filter(r => r.type === 'retrieval' && r.chapterId != null)
    return (
        <div className={`ai-msg ${isUser ? 'user' : 'assistant'}`}
             data-testid={isUser ? 'ai-user-msg' : 'ai-assistant-msg'}>
            {message.refs?.map((ref, i) => (
                ref.type === 'selection'
                    ? <blockquote key={i} className="ai-msg-ref" data-testid="ai-msg-ref-selection">“{ref.text}”</blockquote>
                    : ref.type === 'retrieval' ? null : (
                        <div key={i} className="ai-msg-ref chapter" data-testid="ai-msg-ref-chapter">
                            引用章节:{ref.chapterTitle ?? `第 ${ref.seq ?? '?'} 章`}
                        </div>
                    )
            ))}
            {citations.length > 0 && (
                <CitationBar
                    testid="ai-msg-citations"
                    citations={citations.map((c, i) => ({
                        chapterId: c.chapterId!,
                        chapterSeq: c.chapterSeq ?? c.seq ?? 0,
                        chapterTitle: c.chapterTitle ?? null,
                        chunkSeq: c.chunkSeq ?? 0,
                        excerpt: c.excerpt ?? '',
                        __i: i,
                    }))}
                    onJump={onJump}/>
            )}
            <div className="ai-msg-content" data-testid="ai-msg-content">{message.content}</div>
        </div>
    )
}

/** 检索引用条(S3):章节标题 + 原文摘录,点击跳转;流式开始前(meta 后)即可见。 */
function CitationBar({citations, onJump, testid}: {
    citations: Array<{ chapterId: number; chapterSeq?: number; chapterTitle?: string | null; excerpt?: string; __i?: number }>
    onJump: (c: { chapterId?: number; chapterSeq?: number; chapterTitle?: string | null; excerpt?: string }) => void
    testid: string
}) {
    return (
        <div className="ai-citations" data-testid={testid}>
            {citations.map((c, i) => (
                <button
                    key={c.__i ?? i}
                    className="ai-citation"
                    onClick={() => onJump(c)}
                    data-testid="ai-citation"
                    data-chapter-id={c.chapterId}>
                    <span className="ai-citation-chapter" data-testid="ai-citation-chapter">
                        第{c.chapterSeq || '?'}章 {c.chapterTitle ? `· ${c.chapterTitle}` : ''}
                    </span>
                    <span className="ai-citation-excerpt">{clampExcerpt(c.excerpt ?? '')}</span>
                </button>
            ))}
        </div>
    )
}

function clampExcerpt(text: string): string {
    const compact = text.replaceAll(/\s+/g, ' ').trim()
    return compact.length > 60 ? compact.slice(0, 60) + '…' : compact
}

/** 章内摘录定位:清洗文本与渲染 DOM 无一一对应(D-40),不承诺 CFI 级精确定位;
 * 取摘录归一化前缀在正文文本节点中搜索,命中滚动到命中处,未命中停章首不报错。 */
function locateExcerpt(view: FoliateView, excerpt: string) {
    const probe = excerpt.replaceAll(/\s+/g, '').slice(0, 40)
    if (!probe) return
    setTimeout(() => {
        const contents = view.renderer?.getContents() ?? []
        for (const content of contents) {
            if (findTextAndScroll(content.doc, probe)) return
        }
    }, 120) // 等 goTo 后内容重绘
}

function findTextAndScroll(doc: Document, probe: string): boolean {
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
    let acc = ''
    let node: Node | null
    while ((node = walker.nextNode()) != null) {
        const text = node as Text
        acc += (text.data ?? '').replaceAll(/\s+/g, '')
        if (acc.includes(probe)) {
            // 命中:滚动到包含命中尾段的最近元素(近似定位,v1 口径)
            const el = text.parentElement ?? doc.body
            el.scrollIntoView({block: 'center', behavior: 'smooth'})
            return true
        }
        if (acc.length > 2_000_000) break // 防御超长章
    }
    return false
}

/** 引用条 → 消息 refs(与后端落库同形,乐观 UI 与重拉会话一致)。 */
function citationsToRefs(citations: Citation[] | null): ChatRef[] | null {
    if (!citations || citations.length === 0) return null
    return citations.map(c => ({
        type: 'retrieval' as const,
        chapterId: c.chapterId,
        chapterTitle: c.chapterTitle,
        chapterSeq: c.chapterSeq,
        chunkSeq: c.chunkSeq,
        excerpt: c.excerpt,
    }))
}
