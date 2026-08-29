import {expect, test} from '@playwright/test'
import {resetBackend} from './global-setup'
import {dragSelectFirstParagraph, openReader, overlayCount, renderedText, waitForRendered} from './helpers'

/**
 * 三个用户上报缺陷的回归(反馈回路):
 * 1. 分页模式键盘(方向键/回车)无法翻页——宿主从未接线(vendor 不自带键盘处理,上游演示页同在宿主接)。
 * 2. 划线后经目录跳走再跳回,高亮不重画——overlayer 随 section 重建,宿主须监听 create-overlay 重画。
 * 3. 滚动模式滚到章底无法进入下一章——vendor 现状即单章容器,须在边界滚轮时由宿主触发跨章。
 */

test.beforeEach(async () => {
    await resetBackend()
})

/** 当前定位:section index + section 内页码(合成单调量,翻页/跨章均递增)。 */
function locate(page: import('@playwright/test').Page): Promise<number> {
    return page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const r = view.renderer
        return r.getContents()[0].index * 1000 + r.page
    })
}

test('分页模式:键盘(窗口与正文聚焦两路)可翻页', async ({page}) => {
    await openReader(page)
    expect(await renderedText(page)).toContain('先帝创业未半')

    // 路径一:焦点在顶层窗口(打开书后未点正文);翻页后留 350ms 避开 vendor 100ms 翻页锁
    const before = await locate(page)
    await page.keyboard.press('ArrowRight')
    await expect.poll(() => locate(page), {message: 'ArrowRight 应前进(顶层窗口聚焦)'}).toBeGreaterThan(before)
    await page.waitForTimeout(350)

    // 路径二:焦点在正文 iframe 内(点击正文后,键盘事件只到内容 doc)
    const before2 = await locate(page)
    await page.getByTestId('reader-content').click()
    await page.keyboard.press('Enter')
    await expect.poll(() => locate(page), {message: 'Enter 应前进(正文 iframe 聚焦)'}).toBeGreaterThan(before2)
    await page.waitForTimeout(350)

    // 回退键生效
    const before3 = await locate(page)
    await page.keyboard.press('ArrowLeft')
    await expect.poll(() => locate(page), {message: 'ArrowLeft 应后退'}).toBeLessThan(before3)
})

test('划线经目录跳走再跳回,高亮重画不丢', async ({page}) => {
    await openReader(page)

    // 建立划线(第一章第一段)
    await dragSelectFirstParagraph(page)
    await page.getByTestId('create-highlight').click()
    await expect.poll(() => overlayCount(page)).toBe(1)

    // 目录跳到卷三(另一章)
    await page.getByTestId('toc-toggle').click()
    await page.getByTestId('toc-item').filter({hasText: '卷三 · 赤壁赋'}).click()
    await expect.poll(() => renderedText(page)).toContain('壬戌之秋')
    expect(await overlayCount(page)).toBe(0) // 他章无划线

    // 划线列表跳回 → 正文回到第一章,且高亮必须重画(缺陷:重画为 0)
    await page.getByTestId('highlights-toggle').click()
    await page.getByTestId('highlight-item-text').click()
    await expect.poll(() => renderedText(page), {message: '应跳回第一章'}).toContain('先帝创业未半')
    await expect.poll(() => overlayCount(page), {message: '跳回后高亮应重画'}).toBe(1)

    // 点击高亮本体(rect 中心;p 是块级元素宽于文字,段落中心可能是空白)应打开编辑
    const {x, y} = await page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const r = (view.renderer.getContents()[0].overlayer.element
            .firstElementChild as SVGGraphicsElement).getBoundingClientRect()
        return {x: r.x + r.width / 2, y: r.y + r.height / 2}
    })
    await page.mouse.click(x, y)
    await expect(page.getByTestId('highlight-bar')).toBeVisible()
})

test('滚动模式:滚到章底继续滚动进入下一章', async ({page}) => {
    await openReader(page)
    await page.getByTestId('flow-select').selectOption('scrolled')
    await expect.poll(() => renderedText(page)).toContain('先帝创业未半')

    // 鼠标置于正文上,持续向下滚:章内滚动 → 章底后继续滚应跨章
    const {x, y} = await page.evaluate(() => {
        const view = document.querySelector('foliate-view') as any
        const r = view.getBoundingClientRect()
        return {x: r.x + r.width / 2, y: r.y + r.height / 2}
    })
    await page.mouse.move(x, y)
    // 逐轮下滚:命中目标章即停(不盲滚固定次数,避免冷却期内过冲到更后章节)
    for (let i = 0; i < 10 && !(await renderedText(page)).includes('宫中府中'); i++) {
        await page.mouse.wheel(0, 800)
        await page.waitForTimeout(400)
    }
    await expect.poll(() => renderedText(page), {timeout: 15_000, message: '章底继续滚动应进入下一章'})
        .toContain('宫中府中')

    // 反向:滚到章首继续向上滚,回到第一章
    for (let i = 0; i < 10 && !(await renderedText(page)).includes('先帝创业未半'); i++) {
        await page.mouse.wheel(0, -800)
        await page.waitForTimeout(400)
    }
    await expect.poll(() => renderedText(page), {timeout: 15_000, message: '章首继续滚动应回到上一章'})
        .toContain('先帝创业未半')
})
