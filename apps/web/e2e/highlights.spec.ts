import {expect, test} from '@playwright/test'
import {resetBackend} from './global-setup'
import {dragSelectFirstParagraph, openReader, overlayCount, waitForRendered} from './helpers'

/**
 * M1-07 划线 E2E:真实拖拽选中 → 划线 → 高亮渲染 → 改色/改备注 → 刷新重开仍在。
 * 手势策略按 01 spike 结论:全自动(CDP 同通道拖拽在 spike 一次成功)。
 */

test.beforeEach(async () => {
    await resetBackend()
})

test('选中文字创建划线,高亮即时渲染,改色改备注后刷新仍在', async ({page}) => {
    await openReader(page)

    // ---- 选中(真实拖拽)→ 划线 ----
    await dragSelectFirstParagraph(page)
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
        (await (await fetch('/api/books/1/highlights', {headers: {Authorization: 'Bearer reader-dev-token'}})).json())[0]?.note,
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

    await dragSelectFirstParagraph(page)
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
