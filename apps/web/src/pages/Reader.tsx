import {useCallback, useEffect, useState} from 'react'
import type {BookListItem} from '@reader/api-client'
import {api} from '../client'
import {FoliateViewHost} from '../reader/FoliateViewHost'
import type {FoliateView, RelocateDetail, TocItem} from '../reader/foliate-types'
import {loadSettings, saveSettings, THEME_COLORS, type ReaderSettings} from '../reader/settings'
import {FOLIATE_VIEW_URL, loadFoliateModule} from '../reader/foliate-urls'

/**
 * 阅读器(M1-05):书源文件经 api-client 带 token 拉取 → foliate-js 渲染;
 * 目录从 EPUB 原文件解析嵌套结构(D-40 不入库);字号/主题/翻页方式仅存 localStorage(FR-201)。
 * M1-07/08 在此之上接划线与进度。
 */
export function Reader({bookId, onExit}: { bookId: number; onExit: () => void }) {
    const [meta, setMeta] = useState<BookListItem | null>(null)
    const [status, setStatus] = useState<'loading' | 'ready' | ('error' & string) | 'error'>('loading')
    const [error, setError] = useState<string | null>(null)
    const [toc, setToc] = useState<TocItem[]>([])
    const [tocOpen, setTocOpen] = useState(false)
    const [settings, setSettings] = useState<ReaderSettings>(() => loadSettings())
    const [progressLabel, setProgressLabel] = useState<string>('')
    const [view, setView] = useState<FoliateView | null>(null)

    // 打开书:详情(标题)+ 书源文件 → makeBook → view.open
    const openBook = useCallback(async (v: FoliateView) => {
        setView(v)
        setStatus('loading')
        setError(null)
        try {
            const [books, blob] = await Promise.all([api.listBooks(), api.fetchBookFile(bookId)])
            const item = books.find(b => b.id === bookId)
            if (item) setMeta(item)

            const {makeBook} = await loadFoliateModule<{ makeBook: (f: File) => Promise<unknown> }>(FOLIATE_VIEW_URL)
            const file = new File([blob], `book-${bookId}.epub`, {type: 'application/epub+zip'})
            const book = await makeBook(file)
            await v.open(book)
            await v.init({showTextStart: true}) // 接续进度是 M1-08 的事

            v.addEventListener('relocate', e => {
                const detail = (e as CustomEvent<RelocateDetail>).detail
                if (detail?.fraction != null) {
                    setProgressLabel(`${Math.round(detail.fraction * 100)}%`)
                }
            })
            setToc((v.book?.toc ?? []) as TocItem[])
            setStatus('ready')
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e))
            setStatus('error')
        }
    }, [bookId])

    // 设置变化即时生效并持久化(仅 localStorage,FR-201)
    useEffect(() => {
        saveSettings(settings)
        document.documentElement.dataset.readerTheme = settings.theme
        const renderer = view?.renderer
        if (!renderer) return
        renderer.setAttribute('flow', settings.flow)
        const {fg, bg} = THEME_COLORS[settings.theme]
        renderer.setStyles(`:root { font-size: ${settings.fontSize}% !important; color: ${fg}; background: ${bg}; }`)
    }, [settings, view, status]) // status:书就绪后再刷一次样式

    const changeSettings = (patch: Partial<ReaderSettings>) => setSettings(s => ({...s, ...patch}))

    return (
        <main className="reader" data-theme={settings.theme} data-testid="reader-root">
            <header className="reader-header">
                <button onClick={onExit} data-testid="back-to-library">← 书库</button>
                <h1 className="reader-title">{meta?.title ?? '阅读器'}</h1>
                <span className="reader-progress" data-testid="reader-progress-label">{progressLabel}</span>
                <button onClick={() => setTocOpen(o => !o)} data-testid="toc-toggle">目录</button>
                <label className="setting">
                    字号
                    <button
                        onClick={() => changeSettings({fontSize: Math.max(50, settings.fontSize - 10)})}
                        data-testid="font-decrease">A−
                    </button>
                    <span data-testid="font-size-label">{settings.fontSize}%</span>
                    <button
                        onClick={() => changeSettings({fontSize: Math.min(300, settings.fontSize + 10)})}
                        data-testid="font-increase">A+
                    </button>
                </label>
                <label className="setting">
                    主题
                    <select
                        value={settings.theme}
                        onChange={e => changeSettings({theme: e.target.value as ReaderSettings['theme']})}
                        data-testid="theme-select"
                    >
                        <option value="light">亮</option>
                        <option value="sepia">纸黄</option>
                        <option value="dark">暗</option>
                    </select>
                </label>
                <label className="setting">
                    翻页
                    <select
                        value={settings.flow}
                        onChange={e => changeSettings({flow: e.target.value as ReaderSettings['flow']})}
                        data-testid="flow-select"
                    >
                        <option value="paginated">分页</option>
                        <option value="scrolled">滚动</option>
                    </select>
                </label>
            </header>

            <div className="reader-body">
                {tocOpen && (
                    <nav className="toc-panel" data-testid="toc-panel">
                        <TocTree items={toc} onNavigate={href => {
                            void view?.goTo(href)
                        }}/>
                    </nav>
                )}
                <div className="reader-main">
                    {status === 'loading' && <p className="hint">打开书籍中…</p>}
                    {status === 'error' && <p className="error" role="alert">打开失败:{error}</p>}
                    <FoliateViewHost onReady={v => void openBook(v)}/>
                </div>
            </div>
        </main>
    )
}

/** 嵌套目录树(D-40:导航视图,不入库、不走后端)。 */
function TocTree({items, onNavigate}: { items: TocItem[]; onNavigate: (href: string) => void }) {
    if (items.length === 0) return <p className="hint">本书无目录</p>
    return (
        <ul className="toc-tree">
            {items.map((item, i) => (
                <li key={i}>
                    <a
                        role="button"
                        tabIndex={0}
                        onClick={() => item.href && onNavigate(item.href)}
                        onKeyDown={e => e.key === 'Enter' && item.href && onNavigate(item.href)}
                        data-testid="toc-item"
                    >
                        {item.label ?? '(无标题)'}
                    </a>
                    {item.subitems?.length ? <TocTree items={item.subitems} onNavigate={onNavigate}/> : null}
                </li>
            ))}
        </ul>
    )
}
