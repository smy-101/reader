import {useCallback, useEffect, useRef, useState} from 'react'
import type {AskEvents, ChatMessage, ChatRef, ChatSession, Citation} from '@reader/api-client'
import {citationsToRefs} from './CitationBar'

/** 一次提问的全部面板差异注入:端点调用、乐观 refs、是否先挂空引用条。 */
export interface ChatPanelAsk {
    /** SSE 调用(书级/跨书仅此处不同:输入形状与端点) */
    invoke: (events: AskEvents) => Promise<void>
    /** 乐观用户消息的 refs(书级:S1 选中 + 目标章;跨书:null) */
    optimisticRefs: ChatRef[] | null
    /** 本次提问是否检索式(先挂空引用条,流式开始前占位) */
    citationsExpected: boolean
}

/**
 * AI 面板共用状态机(S4 评审抽取:两面板此前整段重复):会话列表 + 消息流 + 输入 +
 * 流式(SSE 事件驱动的乐观 UI:tempId 落定后修正、会话置顶、引用随助手消息落形)
 * + 会话四件事(打开/新开/重命名/删除)+ 滚动跟随。两面板只注入各自的端点与
 * 新会话归属(bookId / null = 跨书);消息流/引用条/输入行(chat/*)完全共用。
 */
export function useChatPanel({
    listSessions,
    loadSessionMessages,
    renameSession,
    deleteSession,
    newSessionBookId,
}: {
    listSessions: () => Promise<ChatSession[]>
    loadSessionMessages: (sessionId: number) => Promise<ChatMessage[]>
    renameSession: (sessionId: number, title: string) => Promise<ChatSession>
    deleteSession: (sessionId: number) => Promise<void>
    /** 新会话归属:书级传 bookId,跨书面板传 null(乐观会话条目与后端同形) */
    newSessionBookId: number | null
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
    /** 本次流式的检索引用(随 meta 下发;流式开始前即可渲染) */
    const [liveCitations, setLiveCitations] = useState<Citation[] | null>(null)
    /** meta 引用镜像(onDone 落助手消息 refs,与后端落库同形) */
    const liveCitationsRef = useRef<Citation[] | null>(null)
    const messagesEndRef = useRef<HTMLDivElement | null>(null)

    const streaming = streamText !== null
    const activeSession = sessions.find(s => s.id === activeId) ?? null

    /** 拉取会话全部消息(打开会话一次拿齐;失败显式提示)。 */
    const loadMessages = useCallback(async (sessionId: number) => {
        try {
            setMessages(await loadSessionMessages(sessionId))
        } catch (e) {
            setAskError(e instanceof Error ? e.message : String(e))
        }
    }, [loadSessionMessages])

    // 打开面板引导:会话列表 + 最近活跃会话的消息;StrictMode 双调用下只引导一次
    // (迟到的回调会覆盖交互后的状态,如刚改完的会话名)
    const bootstrappedRef = useRef(false)
    const bootstrap = useCallback(async () => {
        if (bootstrappedRef.current) return
        bootstrappedRef.current = true
        try {
            const list = await listSessions()
            setSessions(list)
            const first = list[0]
            if (first) {
                setActiveId(first.id)
                await loadMessages(first.id)
            }
        } catch (e) {
            setAskError(e instanceof Error ? e.message : String(e))
        }
    }, [listSessions, loadMessages])

    // 流式期间与消息变化时滚动到底
    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({block: 'nearest'})
    }, [messages, streamText])

    /**
     * 发送一次提问:prepare 完成面板差异部分(目标章映射/S1 引用/端点调用等,可异步),
     * 返回 null 则放弃本次发送;其余(乐观消息、SSE 状态机、会话置顶、错误与说明)在此共用。
     */
    async function send(prepare: () => Promise<ChatPanelAsk | null>): Promise<void> {
        const content = input.trim()
        if (!content || streamText !== null) return
        setAskError(null)
        setNote(null)
        const ask = await prepare()
        if (ask == null) return

        // 乐观 UI:先显示本条提问(引用一并展示),meta 落定后修正 id
        const tempId = -Date.now()
        setMessages(prev => [...prev, {
            id: tempId, sessionId: activeId ?? 0, role: 'user' as const,
            content, refs: ask.optimisticRefs, createdAt: '',
        }])
        setInput('')
        streamTextRef.current = ''
        setStreamText('')
        setLiveCitations(ask.citationsExpected ? [] : null) // 检索式:先挂空引用条(流式开始前占位)
        liveCitationsRef.current = null

        try {
            await ask.invoke({
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
                        : [{
                            id: meta.sessionId, bookId: newSessionBookId,
                            title: meta.sessionTitle, createdAt: '', updatedAt: '',
                        }, ...prev])
                },
                onDelta: text => {
                    streamTextRef.current += text
                    setStreamText(streamTextRef.current)
                },
                onDone: done => {
                    const streamed = streamTextRef.current
                    if (streamed) {
                        // 检索引用随助手消息展示(与后端落库 refs 同形,刷新后仍在)
                        setMessages(msgs => [...msgs, {
                            id: done.assistantMessageId, sessionId: activeId ?? 0,
                            role: 'assistant' as const, content: streamed,
                            refs: citationsToRefs(liveCitationsRef.current), createdAt: '',
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

    /** 清空进行中的临时态(新开/切换会话时)。 */
    function clearTransient() {
        setAskError(null)
        setNote(null)
        setLiveCitations(null)
    }

    /** 新会话:只置空当前会话(不发请求;下次提问自动路由新建,标题取首条提问截断)。 */
    function startNewSession() {
        setActiveId(null)
        setMessages([])
        clearTransient()
    }

    /** 重命名(FR-304):成功就地更新列表条目。 */
    async function confirmRename(sessionId: number) {
        const title = renameText.trim()
        if (!title) return
        try {
            const updated = await renameSession(sessionId, title)
            setSessions(prev => prev.map(s => (s.id === sessionId ? updated : s)))
        } catch (e) {
            setAskError(e instanceof Error ? e.message : String(e))
        } finally {
            setRenaming(null)
        }
    }

    /** 删除会话(消息级联清);删除当前会话则落到剩余最近活跃。 */
    async function removeSession(sessionId: number) {
        try {
            await deleteSession(sessionId)
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

    /** 打开(切换)会话;流式期间不切换。 */
    async function openSession(sessionId: number) {
        if (streaming || sessionId === activeId) return
        setActiveId(sessionId)
        clearTransient()
        await loadMessages(sessionId)
    }

    return {
        // 状态
        sessions, activeId, activeSession, messages,
        input, setInput, streaming, streamText,
        liveCitations, askError, note,
        renaming, setRenaming, renameText, setRenameText,
        messagesEndRef,
        // 行为
        bootstrap, loadMessages, send,
        clearTransient, startNewSession, confirmRename, removeSession, openSession,
        setAskError,
    }
}
