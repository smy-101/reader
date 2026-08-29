import type {ChatRef} from '@reader/api-client'

/**
 * 引用条与消息气泡的共用视图形状(M4-05 抽取;S4 增书字段):
 * 书级 S3 引用不填 bookId/bookTitle(既有形状不受影响);跨书引用填,
 * deleted 由调用方按"书不在书库列表"裁决(D-33 占位渲染用)。
 */
export interface CitationView {
    chapterId?: number
    chapterSeq?: number
    chapterTitle?: string | null
    chunkSeq?: number
    excerpt?: string
    bookId?: number | null
    bookTitle?: string | null
    /** 端上裁决:引用指向的书已删除(渲染「原书已删除」占位、禁跳转) */
    deleted?: boolean
}

/** 引用条(S3/S4):书名(跨书)+ 章节标题 + 原文摘录,点击跳转;流式开始前(meta 后)即可见。 */
export function CitationBar({citations, onJump, testid = 'ai-citations'}: {
    citations: Array<CitationView>
    onJump: (c: CitationView) => void
    testid?: string
}) {
    return (
        <div className="ai-citations" data-testid={testid}>
            {citations.map((c, i) => (
                <button
                    key={`${c.bookId ?? 0}-${c.chapterId ?? 0}-${i}`}
                    className={`ai-citation ${c.deleted ? 'deleted' : ''}`}
                    onClick={() => !c.deleted && onJump(c)}
                    disabled={c.deleted}
                    data-testid="ai-citation"
                    data-book-id={c.bookId ?? undefined}
                    data-chapter-id={c.chapterId}
                >
                    <span className="ai-citation-chapter" data-testid="ai-citation-chapter">
                        {c.deleted
                            ? `《${c.bookTitle ?? '未知书籍'}》(原书已删除)`
                            : `${c.bookTitle ? `《${c.bookTitle}》` : ''}第${c.chapterSeq || '?'}章${c.chapterTitle ? ` · ${c.chapterTitle}` : ''}`}
                    </span>
                    <span className="ai-citation-excerpt">{clampExcerpt(c.excerpt ?? '')}</span>
                </button>
            ))}
        </div>
    )
}

export function clampExcerpt(text: string): string {
    const compact = text.replaceAll(/\s+/g, ' ').trim()
    return compact.length > 60 ? compact.slice(0, 60) + '…' : compact
}

/** 引用条 → 消息 refs(与后端落库同形,乐观 UI 与重拉会话一致;跨书含书字段)。 */
export function citationsToRefs(citations: Array<CitationView> | null): ChatRef[] | null {
    if (!citations || citations.length === 0) return null
    return citations.map(c => ({
        type: 'retrieval' as const,
        chapterId: c.chapterId,
        chapterTitle: c.chapterTitle,
        chapterSeq: c.chapterSeq,
        chunkSeq: c.chunkSeq,
        excerpt: c.excerpt,
        ...(c.bookId != null ? {bookId: c.bookId} : {}),
        ...(c.bookTitle != null ? {bookTitle: c.bookTitle} : {}),
    }))
}
