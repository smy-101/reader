import {useCallback, useEffect, useMemo, useState} from 'react'
import type {BookListItem} from '@reader/api-client'
import {api} from '../client'
import {BookCard} from '../components/BookCard'
import {ConnectionSettings} from '../components/ConnectionSettings'
import {DeleteBookDialog} from '../components/DeleteBookDialog'
import {GlobalAiPanel} from '../components/GlobalAiPanel'
import {ModelSettingsPanel} from '../components/ModelSettingsPanel'
import {UploadPanel, type UploadResult} from '../components/UploadPanel'
import type {CitationView} from '../components/chat/CitationBar'

/** 书库页(FR-103/106):封面、标题、作者、进度;标题/作者即时过滤;单/批量上传;
 * S4 增全局「AI 伴读」入口(未配置 embedding / 全库无就绪书时隐藏,FR-403)。 */
export function Library({onOpen}: { onOpen: (bookId: number, jump?: CitationView) => void }) {
    const [books, setBooks] = useState<BookListItem[]>([])
    const [loadError, setLoadError] = useState<string | null>(null)
    const [loading, setLoading] = useState(true)
    const [filter, setFilter] = useState('')
    const [settingsOpen, setSettingsOpen] = useState(false)
    const [modelSettingsOpen, setModelSettingsOpen] = useState(false)
    const [pendingDelete, setPendingDelete] = useState<BookListItem | null>(null)
    /** 当前配置的 embedding 模型:未配置(空)时嵌入状态卡整体隐藏(FR-403),
     * 配置时也供状态卡裁决“模型已更换,需重新嵌入”(US 13);全局 AI 入口显隐同源 */
    const [embeddingModel, setEmbeddingModel] = useState<string | null>(null)
    const [globalAiOpen, setGlobalAiOpen] = useState(false)

    const refreshEmbeddingConfigured = useCallback(async () => {
        try {
            setEmbeddingModel((await api.getModelSettings()).embeddingModel ?? null)
        } catch {
            setEmbeddingModel(null) // 设置读不到按未配置处理(隐藏不报错)
        }
    }, [])

    useEffect(() => {
        void refreshEmbeddingConfigured()
    }, [refreshEmbeddingConfigured])

    const refresh = useCallback(async () => {
        setLoading(true)
        try {
            setBooks(await api.listBooks())
            setLoadError(null)
        } catch (e) {
            setLoadError(e instanceof Error ? e.message : String(e))
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        void refresh()
    }, [refresh])

    const filtered = useMemo(() => {
        const q = filter.trim().toLowerCase()
        if (!q) return books
        return books.filter(b =>
            b.title.toLowerCase().includes(q)
            || (b.author ?? '').toLowerCase().includes(q))
    }, [books, filter])

    // ---- S4 全局 AI 入口显隐(FR-403;与嵌入状态卡同源消费书库列表的就绪摘要,US 25) ----
    /** 已配置 embedding 且全库至少一本就绪时入口可见;就绪摘要随列表响应携带,不逐书轮询 */
    const globalAiReady = embeddingModel != null && books.some(b => b.embedding?.ready)

    // 全库无就绪书但仍有书在嵌入中:每 2s 重拉列表直至收敛(完成即亮;无推送,v1 口径 D-44)
    useEffect(() => {
        if (embeddingModel == null || globalAiReady) return
        const inProgress = books.some(b => b.embedding?.status === 'pending' || b.embedding?.status === 'running')
        if (!inProgress) return
        const timer = setTimeout(() => void refresh(), 2000)
        return () => clearTimeout(timer)
    }, [books, embeddingModel, globalAiReady, refresh])

    const handleUploaded = useCallback((results: UploadResult[]) => {
        // 任一本成功(新增或已在书库)都刷新列表;结果明细由 UploadPanel 展示
        if (results.some(r => r.status === 'added' || r.status === 'duplicate')) void refresh()
    }, [refresh])

    return (
        <main className="library">
            <header className="library-header">
                <h1>书库</h1>
                <div className="header-actions">
                    <UploadPanel onDone={handleUploaded} onOpenBook={bookId => onOpen(bookId)}/>
                    {globalAiReady && (
                        <button
                            className="link-button"
                            onClick={() => setGlobalAiOpen(true)}
                            title="不打开书,就整个书库提问(S4 跨书对比)"
                            data-testid="global-ai-entry"
                        >
                            AI 伴读
                        </button>
                    )}
                    <button
                        className="link-button"
                        onClick={() => setSettingsOpen(true)}
                        data-testid="connection-settings-open"
                    >
                        连接设置
                    </button>
                    <button
                        className="link-button"
                        onClick={() => setModelSettingsOpen(true)}
                        data-testid="model-settings-open"
                    >
                        AI 设置
                    </button>
                </div>
            </header>

            <input
                className="filter-input"
                type="search"
                placeholder="按标题或作者过滤…"
                value={filter}
                onChange={e => setFilter(e.target.value)}
                aria-label="按标题或作者过滤"
                data-testid="filter-input"
            />

            {loadError && (
                <div className="load-error" role="alert">
                    <p className="error">{loadError}</p>
                    <button className="link-button" onClick={() => setSettingsOpen(true)}
                            data-testid="error-settings-open">
                        打开连接设置
                    </button>
                </div>
            )}
            {loading && books.length === 0 && <p className="hint">加载书库中…</p>}
            {!loading && books.length === 0 && !loadError && (
                <p className="hint" data-testid="empty-library">书库还是空的,上传第一本 EPUB 吧</p>
            )}

            <section className="book-grid" aria-label="书籍列表">
                {filtered.map(book => (
                    <BookCard
                        key={book.id}
                        book={book}
                        onOpen={() => onOpen(book.id)}
                        onDelete={() => setPendingDelete(book)}
                        embeddingModel={embeddingModel}
                        onEmbeddingSettled={() => void refresh()}
                    />
                ))}
            </section>
            {!loading && books.length > 0 && filtered.length === 0 && (
                <p className="hint" data-testid="no-match">没有匹配“{filter}”的书</p>
            )}

            {settingsOpen && <ConnectionSettings onClose={() => setSettingsOpen(false)}/>}
            {modelSettingsOpen && <ModelSettingsPanel onClose={() => {
                setModelSettingsOpen(false)
                void refreshEmbeddingConfigured() // 配置变化 → 状态卡/入口显隐随之更新(FR-403)
                void refresh() // 就绪摘要与模型配置相关,一并重拉
            }}/>}
            {globalAiOpen && (
                <GlobalAiPanel
                    books={books}
                    onOpenBook={(bookId, citation) => onOpen(bookId, citation)}
                    onClose={() => setGlobalAiOpen(false)}/>
            )}
            {pendingDelete && (
                <DeleteBookDialog
                    book={pendingDelete}
                    onDone={() => {
                        setPendingDelete(null)
                        void refresh() // 删除后书库即时移出(FR-104)
                    }}
                    onCancel={() => setPendingDelete(null)}
                />
            )}
        </main>
    )
}
