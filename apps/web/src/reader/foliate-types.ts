/**
 * foliate-js vendor 的最小类型垫片(源码无类型;API 面按 spike 结论钉死,
 * 见 .scratch/m1-web-reader/spike-conclusion.md)。
 * 集中在此一处,业务代码不直接碰 any。
 */

export interface TocItem {
    label?: string
    href?: string
    subitems?: TocItem[]
}

export interface RelocateDetail {
    /** 全书进度 0-1 */
    fraction?: number
    section?: { current: number; total: number }
    cfi?: string
}

export interface ContentEntry {
    index: number
    doc: Document
    overlayer?: unknown
}

export interface FoliateRenderer {
    setAttribute(name: string, value: string): void
    getAttribute(name: string): string | null
    getContents(): ContentEntry[]
    setStyles(css: string): void
    /** 滚动模式(单章容器)滚动状态:只读 getters(paginator 公开面) */
    readonly scrolled: boolean
    readonly start: number
    readonly end: number
    readonly viewSize: number
}

export interface FoliateView extends HTMLElement {
    open(book: unknown): Promise<void>
    init(options?: { lastLocation?: string; showTextStart?: boolean }): Promise<void>
    goTo(target: string | number): Promise<unknown>
    prev(): Promise<void>
    next(): Promise<void>
    /** 方向键翻页用(内部按书籍 rtl 取向选 prev/next,上游演示页同款) */
    goLeft(): Promise<void>
    goRight(): Promise<void>
    close(): void
    getCFI(index: number, range: Range): string
    addAnnotation(annotation: { value: string; color?: string }): Promise<unknown>
    deleteAnnotation(annotation: { value: string }): Promise<unknown>
    renderer?: FoliateRenderer
    book?: { toc?: TocItem[]; sections?: unknown[] }
    /** 最新阅读位置(vendor 每次 relocate 后更新;AI 目标章映射用,D-31) */
    lastLocation?: RelocateDetail
}
