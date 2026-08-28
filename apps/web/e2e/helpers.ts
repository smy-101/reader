import {expect, type Page} from '@playwright/test'

/**
 * E2E 共用工具:foliate 内容断言/手势驱动。
 * foliate-js 内容渲染在 closed shadow DOM 的 iframe 里,正文/坐标断言经
 * `foliate-view.renderer.getContents()` 这个自家稳定缝(spike 结论坑位 2)。
 */

/** 等 foliate 正文渲染完成(innerText 非空)。 */
export async function waitForRendered(page: Page): Promise<void> {
    await expect.poll(() => page.evaluate(() =>
        (document.querySelector('foliate-view') as any)?.renderer?.getContents?.()[0]?.doc?.body?.innerText ?? '',
    )).not.toBe('')
}

/** 当前渲染正文纯文本。 */
export function renderedText(page: Page): Promise<string> {
    return page.evaluate(() =>
        (document.querySelector('foliate-view') as any).renderer.getContents()[0].doc.body.innerText as string)
}

/** 高亮图层里已画的高亮数(overlayer SVG 子元素,跨已加载 section 汇总)。 */
export function overlayCount(page: Page): Promise<number> {
    return page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const contents = view.renderer?.getContents?.() ?? []
        return contents.reduce((n: number, c: any) => n + (c.overlayer?.element?.childElementCount ?? 0), 0)
    })
}

/** 拖拽选中当前章第一段(15% → 85% 文字;真实手势,spike 已验证 CDP 通道)。 */
export async function dragSelectFirstParagraph(page: Page): Promise<void> {
    const {x1, x2, y} = await page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const content = view.renderer.getContents()[0]
        const frame = content.doc.defaultView.frameElement as HTMLIFrameElement
        const fr = frame.getBoundingClientRect()
        const p = content.doc.querySelector('p') as HTMLElement
        const r = p.getBoundingClientRect()
        return {
            x1: fr.x + r.left + r.width * 0.15,
            x2: fr.x + r.left + r.width * 0.85,
            y: fr.y + r.top + r.height / 2,
        }
    })
    await page.mouse.move(x1, y)
    await page.mouse.down()
    await page.mouse.move(x1 + (x2 - x1) / 2, y, {steps: 5})
    await page.mouse.move(x2, y, {steps: 10})
    await page.mouse.up()
    await expect(page.getByTestId('highlight-bar')).toBeVisible()
}

/** 上传 fixture(经隐藏 input,真实事件链路);等整批完成。 */
export async function uploadFiles(page: Page, ...names: string[]): Promise<void> {
    await page.locator('#upload-input').setInputFiles(names.map(n => `e2e/fixtures/${n}`))
    await expect(page.getByTestId('upload-result')).toHaveCount(names.length)
    await expect(page.locator('.upload-button')).not.toHaveClass(/busy/)
}

/** 上传 m1-e2e.epub 并打开阅读器。 */
export async function openReader(page: Page): Promise<void> {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
}

/** 服务端进度(直连测试后端;未上报返回 null)。 */
export function serverProgress(page: Page, bookId = 1): Promise<{ percent: number } | null> {
    return page.evaluate(async bookId => {
        const res = await fetch(`/api/books/${bookId}/progress`, {
            headers: {Authorization: 'Bearer reader-dev-token'},
        })
        if (!res.ok) return null
        return {percent: (await res.json()).percent}
    }, bookId)
}
