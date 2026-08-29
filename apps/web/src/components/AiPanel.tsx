import {useCallback, useEffect, useRef, useState} from 'react'
import type {AskEvents, AskInput, ChapterSummary, ChatRef} from '@reader/api-client'
import {api} from '../client'
import type {FoliateView, RelocateDetail} from '../reader/foliate-types'
import {jumpToCitation} from '../reader/locate'
import {ChatComposer} from './chat/ChatComposer'
import {MessageBubble} from './chat/MessageBubble'
import {CitationBar} from './chat/CitationBar'
import {SessionList} from './chat/SessionList'
import {useChatPanel, type ChatPanelAsk} from './chat/useChatPanel'

/**
 * AI 伴读面板(M3-04,S2;M4-05,S3;S4 抽出共用件):该书会话列表 + 消息流(含引用展示)
 * + 输入框;回复流式增量渲染,错误显式提示不悬挂(FR-303);会话可重命名/删除(FR-304)。
 * 目标章映射(D-31):缺省携带当前阅读位置所在章(经 relocate 详情的 section index
 * 对应 foliate sections[index].id 与后端 chapter.href 后缀匹配),显式点名章用显式值。
 * S3 定位原文(M4-05):该书嵌入完成才显示「定位原文」入口(FR-403);检索引用随
 * meta 事件在流式开始前即可见,点击跳转到对应章节并尝试以摘录文字定位(未命中停章首)。
 * 状态机与会话列表为共用件(chat/useChatPanel + SessionList),跨书面板同构复用。
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
    const [, setChapters] = useState<ChapterSummary[]>([])
    /** 章节表加载 promise(发送时 await 它,避免目标章映射在加载完成前落空) */
    const chaptersReadyRef = useRef<Promise<ChapterSummary[]> | null>(null)

    // ---- S3 定位原文(M4-05) ----
    /** 该书是否嵌入完成(当前模型):入口显隐的唯一裁决(FR-403;null = 还在判定) */
    const [embeddingReady, setEmbeddingReady] = useState<boolean | null>(null)
    /** S3 提问模式:开启后发送显式检索式提问 */
    const [retrievalMode, setRetrievalMode] = useState(false)

    const panel = useChatPanel({
        listSessions: () => api.listSessions(bookId),
        loadSessionMessages: api.listSessionMessages,
        renameSession: api.renameSession,
        deleteSession: api.deleteSession,
        newSessionBookId: bookId,
    })
    const {
        sessions, activeId, activeSession, messages, input, setInput,
        streaming, streamText, liveCitations, askError, note,
        renaming, setRenaming, renameText, setRenameText, messagesEndRef,
        bootstrap, send, startNewSession, confirmRename, removeSession, openSession,
    } = panel

    // 打开面板:共用引导 + 章节表(目标章映射用)
    useEffect(() => {
        void bootstrap()
        chaptersReadyRef.current = api.listChapters(bookId)
            .then(list => {
                setChapters(list)
                return list
            })
            .catch(() => [] as ChapterSummary[])
    }, [bookId, bootstrap])

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

    /** 面板差异注入:目标章/S1/S3 装配 + 书级端点调用。 */
    async function prepareAsk(): Promise<ChatPanelAsk> {
        // 章节表就绪后再映射目标章(打开面板后立刻提问也不丢章)
        const chaptersNow = await (chaptersReadyRef.current ?? Promise.resolve([] as ChapterSummary[]))
        const target = currentTargetChapter(chaptersNow)
        const selection = pendingSelection
        // S3 检索式提问(带选中文字时 S1 优先级最高,不受影响)
        const retrieval = retrievalMode && !selection
        const askInput: AskInput = {
            content: input.trim(),
            sessionId: activeId,
            chapterId: target?.chapterId ?? null,
            cfi: target?.cfi ?? null,
            selection: selection ? {text: selection.text, cfi: selection.cfi} : null,
            retrieval,
        }

        const optimisticRefs: ChatRef[] = []
        if (selection) {
            optimisticRefs.push({type: 'selection', text: selection.text, cfi: selection.cfi ?? undefined})
        }
        if (target) {
            const ch = chaptersNow.find(c => c.id === target.chapterId)
            optimisticRefs.push({type: 'chapter', chapterId: target.chapterId, chapterTitle: ch?.title ?? null, seq: ch?.seq})
        }
        onClearPending()
        return {
            invoke: (events: AskEvents) => api.askStream(bookId, askInput, events),
            optimisticRefs: optimisticRefs.length ? optimisticRefs : null,
            citationsExpected: retrieval,
        }
    }

    /** 点击引用跳转:到对应章节 + 尝试以摘录文字定位(命中滚动到命中处,未命中停章首)。 */
    async function jump(citation: { chapterId?: number; excerpt?: string }) {
        const chaptersNow = await (chaptersReadyRef.current ?? Promise.resolve([] as ChapterSummary[]))
        if (!view) return
        await jumpToCitation(view, chaptersNow, citation)
    }

    return (
        <aside className="ai-panel" data-testid="ai-panel">
            <div className="ai-panel-header">
                <h2>AI 伴读</h2>
                <button onClick={startNewSession} data-testid="ai-new-session">新会话</button>
                <button onClick={onClose} data-testid="ai-panel-close">关闭</button>
            </div>

            <SessionList
                sessions={sessions} activeId={activeId}
                renaming={renaming} renameText={renameText}
                onRenameText={setRenameText}
                onConfirmRename={confirmRename}
                onStartRename={s => {
                    setRenaming(s.id)
                    setRenameText(s.title)
                }}
                onCancelRename={() => setRenaming(null)}
                onOpen={openSession}
                onRemove={removeSession}
                emptyHint="本书还没有会话;提问即创建。"
                testPrefix="ai"/>

            <div className="ai-messages" data-testid="ai-messages">
                {messages.map(m => <MessageBubble key={m.id} message={m} onJump={jump}/>)}
                {liveCitations !== null && (
                    <CitationBar citations={liveCitations} onJump={jump} testid="ai-live-citations"/>
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
                <ChatComposer
                    value={input}
                    onChange={setInput}
                    onSend={() => send(prepareAsk)}
                    streaming={streaming}
                    placeholder={retrievalMode
                        ? '问“作者在哪讨论过…”(检索原文定位)'
                        : activeSession ? '继续这段讨论…' : '向 AI 提问(自动创建会话)'}
                    testPrefix="ai">
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
                </ChatComposer>
            </div>
        </aside>
    )
}
