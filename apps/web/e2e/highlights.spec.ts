import {expect, test} from '@playwright/test'
import {resetBackend} from './global-setup'

/**
 * M1-07 划线 E2E:真实拖拽选中 → 划线 → 高亮渲染 → 改色/改备注 → 刷新重开仍在。
 * 手势策略按 01 spike 结论:全自动(CDP 同通道拖拽在 spike 一次成功)。
 * 高亮 SVG 在 paginator closed shadow DOM 内,断言经 getContents()[i].overlayer(自家稳定缝)。
 */

test.beforeEach(async () => {
    await resetBackend()
})

async function openReader(page: import('@playwright/test').Page) {
    await page.goto('/')
    await page.locator('#upload-input').setInputFiles(['e2e/fixtures/m1-e2e.epub'])
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
}

/** 等正文渲染并取当前内容 doc。 */
async function waitForRendered(page: import('@playwright/test').Page): Promise<void> {
    await expect.poll(() => page.evaluate(() =>
        (document.querySelector('foliate-view') as any)?.renderer?.getContents?.()[0]?.doc?.body?.innerText ?? '',
    )).not.toBe('')
}

/** 取第一段在页面坐标系中的拖拽起止点(段落内 20% → 80% 文字,同段内选中)。 */
async function paragraphDragPoints(page: import('@playwright/test').Page) {
    return page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const content = view.renderer.getContents()[0]
        const frame = content.doc.defaultView.frameElement as HTMLIFrameElement
        const fr = frame.getBoundingClientRect()
        const p = content.doc.querySelector('p') as HTMLElement
        const r = p.getBoundingClientRect()
        return {
            x1: fr.x + r.left + r.width * 0.15,
            x2: fr.x + r.left + r.width * 0.85,
            y: fr.y + r.top + r.height * 0.5,
            text: p.innerText,
        }
    })
}

/** 高亮图层里已画的高亮数(overlayer SVG 子元素)。 */
function overlayCount(page: import('@playwright/test').Page) {
    return page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const contents = view.renderer?.getContents?.() ?? []
        return contents.reduce((n: number, c: any) => n + (c.overlayer?.element?.childElementCount ?? 0), 0)
    })
}

test('选中文字创建划线,高亮即时渲染,改色改备注后刷新仍在', async ({page}) => {
    await openReader(page)

    // ---- 选中(真实拖拽)→ 划线 ----
    const {x1, x2, y} = await paragraphDragPoints(page)
    await page.mouse.move(x1, y)
    await page.mouse.down()
    await page.mouse.move(x1 + (x2 - x1) / 3, y, {steps: 5})
    await page.mouse.move(x2, y, {steps: 10})
    await page.mouse.up()

    await expect(page.getByTestId('highlight-bar')).toBeVisible()
    await page.getByTestId('color-green').click()
    await page.getByTestId('note-input').fill('卧龙之言')
    await page.getByTestId('create-highlight').click()

    await expect.poll(() => overlayCount(page)).toBe(1) // 高亮即时渲染
    await expect(page.getByTestId('highlights-toggle')).toContainText('划线(1)')

    // ---- 划线列表:可见文字快照与备注 ----
    await page.getByTestId('highlights-toggle').click()
    await expect(page.getByTestId('highlight-item')).toHaveCount(1)
    await expect(page.getByTestId('highlight-item-text')).toContainText('危急存亡')
    await expect(page.getByTestId('highlight-item-text')).toContainText('卧龙之言')

    // ---- 改色 + 改备注(经列表编辑) ----
    await page.getByTestId('highlight-item-edit').click()
    await expect(page.getByTestId('highlight-bar')).toBeVisible()
    await page.getByTestId('edit-color-red').click()
    await page.getByTestId('edit-note-input').fill('改过的备注')
    await page.getByTestId('save-note').click()
    await expect(page.getByTestId('editing-text')).toBeVisible() // 编辑态保持
    // 服务端此刻应已是新备注(LWW 后写胜)
    await expect.poll(() => page.evaluate(async () =>
        (await (await fetch('/api/books/1/highlights', {headers: {Authorization: `Bearer ${'reader-dev-token'}`}})).json())[0]?.note,
    )).toBe('改过的备注')

    // ---- 刷新 → 重开:高亮与备注仍在(全量拉取 D-24) ----
    await page.reload()
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)

    await expect.poll(() => overlayCount(page)).toBe(1)
    await expect(page.getByTestId('highlights-toggle')).toContainText('划线(1)')
    await page.getByTestId('highlights-toggle').click()
    await expect(page.getByTestId('highlight-item-text')).toContainText('改过的备注')
})

test('删除划线后,刷新重开不再出现', async ({page}) => {
    await openReader(page)

    const {x1, x2, y} = await paragraphDragPoints(page)
    await page.mouse.move(x1, y)
    await page.mouse.down()
    await page.mouse.move(x2, y, {steps: 10})
    await page.mouse.up()
    await page.getByTestId('create-highlight').click()
    await expect.poll(() => overlayCount(page)).toBe(1)

    await page.getByTestId('highlights-toggle').click()
    await page.getByTestId('highlight-item-edit').click()
    await page.getByTestId('delete-highlight').click()
    await expect.poll(() => overlayCount(page)).toBe(0)
    await expect(page.getByTestId('highlight-item')).toHaveCount(0)

    await page.reload()
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
    await expect(page.getByTestId('highlights-toggle')).toContainText('划线(0)')
    await expect.poll(() => overlayCount(page)).toBe(0)
})
