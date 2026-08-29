import type {ChatMessage} from '@reader/api-client'
import {CitationBar, type CitationView} from './CitationBar'

/**
 * 消息气泡(M4-05 自 AiPanel 抽取;S4 增书字段透传):
 * 用户消息展示 selection/chapter 引用;助手消息的检索引用渲染为可点击引用条。
 * isBookAvailable:跨书面板传入(引用指向的书不在书库 → 降级占位,D-33);书级不传。
 */
export function MessageBubble({message, onJump, isBookAvailable}: {
    message: ChatMessage
    onJump: (c: CitationView) => void
    isBookAvailable?: (bookId: number) => boolean
}) {
    const isUser = message.role === 'user'
    const citations = (message.refs ?? [])
        .filter(r => r.type === 'retrieval' && r.chapterId != null)
        .map(r => ({
            chapterId: r.chapterId!,
            chapterSeq: r.chapterSeq ?? r.seq ?? 0,
            chapterTitle: r.chapterTitle ?? null,
            chunkSeq: r.chunkSeq ?? 0,
            excerpt: r.excerpt ?? '',
            bookId: r.bookId ?? null,
            bookTitle: r.bookTitle ?? null,
            deleted: r.bookId != null && isBookAvailable != null && !isBookAvailable(r.bookId),
        }))
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
                <CitationBar testid="ai-msg-citations" citations={citations} onJump={onJump}/>
            )}
            <div className="ai-msg-content" data-testid="ai-msg-content">{message.content}</div>
        </div>
    )
}
