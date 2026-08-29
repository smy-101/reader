import {useCallback, useEffect, useRef} from 'react'
import type {AskEvents, BookListItem} from '@reader/api-client'
import {api} from '../client'
import {ChatComposer} from './chat/ChatComposer'
import {MessageBubble} from './chat/MessageBubble'
import {CitationBar, type CitationView} from './chat/CitationBar'
import {SessionList} from './chat/SessionList'
import {useChatPanel, type ChatPanelAsk} from './chat/useChatPanel'

/**
 * 跨书 AI 伴读面板(S4):书库页全局入口打开,与阅读器内 AI 面板同构——
 * 会话列表(最近活跃)+ 消息流(含引用条)+ 输入框 + 重命名/删除(FR-304);
 * 会话创建无显式按钮,提问自动路由落最近活跃跨书会话(D-32 精神,§9 决议)。
 * 提问走全局端点(全库就绪书集合纯向量检索,D-36),恒为检索式;引用随 meta 事件
 * 在流式开始前可见,携带书名与章节;点击引用打开对应的书(onOpenBook 经应用顶层
 * 状态转入阅读器后执行 S3 同款定位);书已删除的引用降级为「原书已删除」占位不可点(D-33)。
 * 状态机与会话列表为共用件(chat/useChatPanel + SessionList),与书级面板同构。
 */
export function GlobalAiPanel({books, onOpenBook, onClose}: {
    /** 书库列表:引用降级裁决(书不在列表 = 已删除)与入口上下文同源 */
    books: BookListItem[]
    onOpenBook: (bookId: number, citation: CitationView) => void
    onClose: () => void
}) {
    const panel = useChatPanel({
        listSessions: api.listGlobalSessions,
        loadSessionMessages: api.listSessionMessages,
        renameSession: api.renameSession,
        deleteSession: api.deleteSession,
        newSessionBookId: null,
    })
    const {
        sessions, activeId, activeSession, messages, input, setInput,
        streaming, streamText, liveCitations, askError, note,
        renaming, setRenaming, renameText, setRenameText, messagesEndRef,
        bootstrap, send, confirmRename, removeSession, openSession, loadMessages,
    } = panel

    /** 引用降级裁决(D-33):引用里的书不在书库列表 → 已删除,占位且不可跳。 */
    const isBookAvailable = useCallback(
        (bookId: number) => books.some(b => b.id === bookId), [books])

    // 打开面板引导(共用件)
    useEffect(() => {
        void bootstrap()
    }, [bootstrap])

    // 重开面板收敛在途回复(D-44 无推送口径):点击跨书引用会带着进行中的流切进阅读器,
    // 重开时末条若仍是 user(回复在途/流被遗留),轮询直至助手消息落库(上限 30s);
    // 本地面板正在流式、已收敛或已显式报错时自然停。
    const pendingTriesRef = useRef(0)
    useEffect(() => {
        const last = messages.length > 0 ? messages[messages.length - 1] : null
        const pendingReply = last != null && last.role === 'user'
        if (!pendingReply) {
            pendingTriesRef.current = 0 // 已收敛(或无消息):重置
            return
        }
        if (streaming || activeId == null || askError != null || pendingTriesRef.current >= 60) return
        const sessionId = activeId
        const timer = setTimeout(() => {
            pendingTriesRef.current += 1
            void loadMessages(sessionId)
        }, 500)
        return () => clearTimeout(timer)
    }, [messages, streaming, activeId, askError, loadMessages])

    /** 面板差异注入:跨书端点(恒检索式,无 selection/目标章)。 */
    async function prepareAsk(): Promise<ChatPanelAsk> {
        const content = input.trim()
        return {
            invoke: (events: AskEvents) => api.askGlobalStream({content, sessionId: activeId}, events),
            optimisticRefs: null,
            citationsExpected: true,
        }
    }

    /** 点击引用:打开对应的书进入阅读器(章 + 摘录定位由应用顶层状态传递)。 */
    function jump(citation: CitationView) {
        if (citation.bookId == null) return
        onOpenBook(citation.bookId, citation)
    }

    return (
        <div className="dialog-backdrop global-ai-backdrop">
            <div className="dialog global-ai-dialog" data-testid="global-ai-panel">
                <div className="ai-panel-header">
                    <h2>AI 伴读 · 跨书</h2>
                    <button onClick={onClose} data-testid="global-ai-close">关闭</button>
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
                    emptyHint="还没有跨书会话;提问即创建。"
                    testPrefix="global-ai"/>

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
                        onSend={() => send(prepareAsk)}
                        streaming={streaming}
                        placeholder={activeSession ? '继续这段跨书讨论…' : '就整个书库提问(自动创建跨书会话)'}
                        testPrefix="global-ai"/>
                </div>
            </div>
        </div>
    )
}
