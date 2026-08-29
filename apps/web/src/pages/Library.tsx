import {useCallback, useEffect, useMemo, useState} from 'react'
import type {BookListItem} from '@reader/api-client'
import {api} from '../client'
import {BookCard} from '../components/BookCard'
import {ConnectionSettings} from '../components/ConnectionSettings'
import {ModelSettingsPanel} from '../components/ModelSettingsPanel'
import {UploadPanel, type UploadResult} from '../components/UploadPanel'

/** 书库页(FR-103/106):封面、标题、作者、进度;标题/作者即时过滤;单/批量上传。 */
export function Library({onOpen}: { onOpen: (bookId: number) => void }) {
    const [books, setBooks] = useState<BookListItem[]>([])
    const [loadError, setLoadError] = useState<string | null>(null)
    const [loading, setLoading] = useState(true)
    const [filter, setFilter] = useState('')
    const [settingsOpen, setSettingsOpen] = useState(false)
    const [modelSettingsOpen, setModelSettingsOpen] = useState(false)

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

    const handleUploaded = useCallback((results: UploadResult[]) => {
        // 任一本成功(新增或已在书库)都刷新列表;结果明细由 UploadPanel 展示
        if (results.some(r => r.status === 'added' || r.status === 'duplicate')) void refresh()
    }, [refresh])

    return (
        <main className="library">
            <header className="library-header">
                <h1>书库</h1>
                <div className="header-actions">
                    <UploadPanel onDone={handleUploaded} onOpenBook={onOpen}/>
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
                    <BookCard key={book.id} book={book} onOpen={() => onOpen(book.id)}/>
                ))}
            </section>
            {!loading && books.length > 0 && filtered.length === 0 && (
                <p className="hint" data-testid="no-match">没有匹配“{filter}”的书</p>
            )}

            {settingsOpen && <ConnectionSettings onClose={() => setSettingsOpen(false)}/>}
            {modelSettingsOpen && <ModelSettingsPanel onClose={() => setModelSettingsOpen(false)}/>}
        </main>
    )
}
