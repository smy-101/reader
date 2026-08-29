import type {ChapterSummary} from '@reader/api-client'
import type {FoliateView} from './foliate-types'

/**
 * 章内摘录定位(M4-05 自 AiPanel 抽取,S3 与 S4 跨书跳转共用口径):
 * 清洗文本与渲染 DOM 无一一对应(D-40),不承诺 CFI 级精确定位;
 * 取摘录归一化前缀在正文文本节点中搜索,命中滚动到命中处,未命中停章首不报错。
 */
export function locateExcerpt(view: FoliateView, excerpt: string) {
    const probe = excerpt.replaceAll(/\s+/g, '').slice(0, 40)
    if (!probe) return
    setTimeout(() => {
        const contents = view.renderer?.getContents() ?? []
        for (const content of contents) {
            if (findTextAndScroll(content.doc, probe)) return
        }
    }, 120) // 等 goTo 后内容重绘
}

function findTextAndScroll(doc: Document, probe: string): boolean {
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
    let acc = ''
    let node: Node | null
    while ((node = walker.nextNode()) != null) {
        const text = node as Text
        acc += (text.data ?? '').replaceAll(/\s+/g, '')
        if (acc.includes(probe)) {
            // 命中:滚动到包含命中尾段的最近元素(近似定位,v1 口径)
            const el = text.parentElement ?? doc.body
            el.scrollIntoView({block: 'center', behavior: 'smooth'})
            return true
        }
        if (acc.length > 2_000_000) break // 防御超长章
    }
    return false
}

/** 点击引用跳转(S3 与 S4 跨书同口径):到对应章节 + 尝试以摘录文字定位。 */
export async function jumpToCitation(
    view: FoliateView,
    chapters: ChapterSummary[],
    citation: { chapterId?: number; excerpt?: string },
) {
    const chapter = chapters.find(c => c.id === citation.chapterId)
    if (!view || !chapter) return
    await view.goTo(chapter.href) // 跳到对应章节(经 foliate 既有导航能力)
    locateExcerpt(view, citation.excerpt ?? '')
}
