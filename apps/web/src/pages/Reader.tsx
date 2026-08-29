import {useCallback, useEffect, useState} from 'react'
import type {BookListItem, Highlight} from '@reader/api-client'
import {api} from '../client'
import {FoliateViewHost} from '../reader/FoliateViewHost'
import type {FoliateView, RelocateDetail, TocItem} from '../reader/foliate-types'
import {loadSettings, saveSettings, THEME_COLORS, type ReaderSettings} from '../reader/settings'
import {FOLIATE_VIEW_URL, loadFoliateModule} from '../reader/foliate-urls'
import {useHighlights} from '../reader/useHighlights'
import {useAutoReportProgress} from '../reader/useProgress'
import {HighlightBar, HighlightListPanel} from '../components/Highlights'

/**
 * 阅读器(M1-05/07/08):渲染、目录、本地设置;选中划线(颜色/备注/删除)、全量拉取;进度接续与上报。
 */
export function Reader({bookId, onExit}: { bookId: number; onExit: () => void }) {
    const [meta, setMeta] = useState<BookListItem | null>(null)
    const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
    const [error, setError] = useState<string | null>(null)
    const [toc, setToc] = useState<TocItem[]>([])
    const [tocOpen, setTocOpen] = useState(false)
    const [highlightsOpen, setHighlightsOpen] = useState(false)
    const [settings, setSettings] = useState<ReaderSettings>(() => loadSettings())
    const [progressLabel, setProgressLabel] = useState<string>('')
    const [view, setView] = useState<FoliateView | null>(null)

    const hl = useHighlights(view, bookId, status === 'ready')
    useAutoReportProgress(view, bookId, status === 'ready')

    // 键盘翻页 + 滚动模式跨章(两个缺陷修复):
    // - 键盘:vendor 不自带键盘处理(上游演示页同在宿主接);正文聚焦在 iframe 内,
    //   键盘事件只到内容 doc,故 window 与每个 load 的 doc 都要挂;交互控件(按钮/目录项/输入)
    //   自带 Enter/Space 语义,不劫持。
    // - 跨章:滚动模式 vendor 现状是单章容器,滚到章底/章首无事发生;在边界继续滚轮时由
    //   宿主触发 prev/next,冷却期防触控板动量连跳;分页模式交给 vendor 自身 snap,不介入。
    useEffect(() => {
        if (!view) return
        const INTERACTIVE = 'input, textarea, select, [contenteditable="true"], a, button'
        const onKey = (e: KeyboardEvent) => {
            if (e.altKey || e.metaKey || e.ctrlKey) return
            if (e.target instanceof Element && e.target.closest(INTERACTIVE)) return
            const k = e.key
            const isPrev = k === 'ArrowLeft' || k === 'PageUp'
            const isNext = k === 'ArrowRight' || k === 'PageDown' || k === 'Enter' || k === ' '
            if (!isPrev && !isNext) return
            e.preventDefault()
            void (isPrev ? view.goLeft() : view.goRight())
        }
        let lastJump = 0
        const onWheel = (e: WheelEvent) => {
            const r = view.renderer
            if (!r?.scrolled || e.deltaY === 0) return
            const now = performance.now()
            if (now - lastJump < 350) return
            const forward = e.deltaY > 0
            const atBoundary = forward ? r.viewSize - r.end <= 2 : r.start <= 0
            if (!atBoundary) return
            lastJump = now
            void (forward ? view.next() : view.prev())
        }
        const onLoad = (e: Event) => {
            const {doc} = (e as CustomEvent<{ doc: Document }>).detail
            doc.addEventListener('keydown', onKey)
            doc.addEventListener('wheel', onWheel, {passive: true})
        }
        window.addEventListener('keydown', onKey)
        view.addEventListener('load', onLoad)
        // 光标在正文 iframe 内时 wheel 发生在内容 doc(上方 load 已挂);在 iframe 外的
        // 边距/背景上时事件经闭 shadow 重定向到本元素——两路都接才能全屏命中
        view.addEventListener('wheel', onWheel, {passive: true})
        return () => {
            window.removeEventListener('keydown', onKey)
            view.removeEventListener('load', onLoad)
            view.removeEventListener('wheel', onWheel)
        }
    }, [view])

    // 打开书:详情(标题)+ 书源文件 + 服务端进度(接续到上次位置,FR-203)→ makeBook → view.open
    const openBook = useCallback(async (v: FoliateView) => {
        setView(v)
        setStatus('loading')
        setError(null)
        try {
            const [detail, blob, progress] = await Promise.all([
                api.getBook(bookId), api.fetchBookFile(bookId), api.getProgress(bookId)])
            setMeta({
                id: detail.id,
                title: detail.title,
                author: detail.author,
                coverUrl: detail.coverUrl,
                progressPercent: progress?.percent ?? null,
            })

            const {makeBook} = await loadFoliateModule<{ makeBook: (f: File) => Promise<unknown> }>(FOLIATE_VIEW_URL)
            const file = new File([blob], `book-${bookId}.epub`, {type: 'application/epub+zip'})
            const book = await makeBook(file)
            await v.open(book)
            // 接续:有服务端进度则回放到该 CFI,否则从头开始(M1-08)
            await v.init(progress ? {lastLocation: progress.cfi} : {showTextStart: true})

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
                <button onClick={() => setHighlightsOpen(o => !o)} data-testid="highlights-toggle">
                    划线({hl.highlights.length})
                </button>
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
                {highlightsOpen && (
                    <HighlightListPanel
                        highlights={hl.highlights}
                        onJump={h => {
                            void view?.goTo(h.cfi)
                        }}
                        onEdit={h => hl.setEditing(h)}
                    />
                )}
                <div className="reader-main">
                    {status === 'loading' && <p className="hint">打开书籍中…</p>}
                    {status === 'error' && <p className="error" role="alert">打开失败:{error}</p>}
                    <FoliateViewHost onReady={v => void openBook(v)}/>
                </div>
            </div>

            <HighlightBar
                selection={hl.selection}
                editing={hl.editing}
                onCreate={hl.create}
                onUpdate={hl.update}
                onDelete={hl.remove}
                onJump={(h: Highlight) => void view?.goTo(h.cfi)}
                onClose={() => {
                    hl.setSelection(null)
                    hl.setEditing(null)
                }}
            />
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
