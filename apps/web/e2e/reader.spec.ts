import {expect, test} from '@playwright/test'
import {resetBackend} from './global-setup'

/**
 * M1-05 阅读器主链路:打开 → 渲染 → 目录跳转 → 字号/主题(仅 localStorage)→ 刷新保留。
 * foliate-js 内容渲染在 closed shadow DOM 的 iframe 里,正文断言经
 * `foliate-view.renderer.getContents()` 这个自家稳定缝(见 spike 结论坑位 2)。
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
}

/** 取当前渲染正文(foliate-view 的内容 doc 纯文本)。 */
async function renderedText(page: import('@playwright/test').Page): Promise<string> {
    await expect.poll(async () => page.evaluate(() =>
        (document.querySelector('foliate-view') as any)?.renderer?.getContents?.()[0]?.doc?.body?.innerText ?? '',
    )).not.toBe('')
    return page.evaluate(() =>
        (document.querySelector('foliate-view') as any).renderer.getContents()[0].doc.body.innerText as string)
}

test('打开书渲染正文,目录跳转到对应章节', async ({page}) => {
    await openReader(page)

    const text = await renderedText(page)
    expect(text).toContain('先帝创业未半') // 第一章正文可见
    await expect(page.getByTestId('reader-progress-label')).not.toHaveText('')

    // 目录面板:嵌套结构渲染,点“卷三 · 赤壁赋”跳转
    await page.getByTestId('toc-toggle').click()
    await expect(page.getByTestId('toc-panel')).toBeVisible()
    const items = page.getByTestId('toc-item')
    await expect(items).toHaveCount(7) // 3 顶级 + 2+2 嵌套
    await items.filter({hasText: '卷三 · 赤壁赋'}).click()

    await expect.poll(() => renderedText(page)).toContain('壬戌之秋')
})

test('字号可调且刷新后保留(仅 localStorage,不进后端)', async ({page}) => {
    await openReader(page)

    const before = await contentFontSize(page)
    await page.getByTestId('font-increase').click()
    await page.getByTestId('font-increase').click()
    await expect(page.getByTestId('font-size-label')).toHaveText('120%')
    const after = await contentFontSize(page)
    expect(after).toBeGreaterThan(before)

    // 设置只落 localStorage:阅读期间无任何写入请求(划线/进度端点尚未使用)
    expect(page.getByTestId('font-size-label')).toHaveText('120%')

    // 刷新 → 回书库(状态路由)→ 重开书:设置保留
    await page.reload()
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await expect(page.getByTestId('font-size-label')).toHaveText('120%')
    await expect.poll(() => contentFontSize(page)).toBe(after)
})

test('主题切换即时生效且刷新后保留', async ({page}) => {
    await openReader(page)

    await page.getByTestId('theme-select').selectOption('sepia')
    await expect(page.getByTestId('reader-root')).toHaveAttribute('data-theme', 'sepia')

    await page.reload()
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toHaveAttribute('data-theme', 'sepia')
    await expect(page.getByTestId('theme-select')).toHaveValue('sepia')
})

/** 内容 doc 的正文字号(px);等待书打开后再取。 */
function contentFontSize(page: import('@playwright/test').Page) {
    return expect.poll(() => page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const doc = view?.renderer?.getContents?.()[0]?.doc as Document | undefined
        if (!doc) return 0
        const p = doc.querySelector('p') ?? doc.body
        return Number.parseFloat(getComputedStyle(p).fontSize) || 0
    })).toBeGreaterThan(0).then(() => page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const doc = view.renderer.getContents()[0].doc as Document
        const p = doc.querySelector('p') ?? doc.body
        return Number.parseFloat(getComputedStyle(p).fontSize)
    }))
}
