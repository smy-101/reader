import {expect, test} from '@playwright/test'
import {resetBackend} from './global-setup'
import {dragSelectFirstParagraph, renderedText, serverProgress, waitForRendered} from './helpers'

/**
 * M1 验收标准原文落成自动化用例:“浏览器读书,刷新/换设备进度划线不丢”。
 * 链路:上传 → 读书(翻页,进度自动上报)→ 划线 → 刷新(同设备)→ 接续 + 划线仍在 →
 * 第二个浏览器上下文(=换设备,localStorage 独立)→ 接续到同一位置 + 划线齐全(D-44:
 * 接受“重开该书才可见”,无推送/轮询)。
 */

test.beforeEach(async () => {
    await resetBackend()
})

test('M1 验收:读书→划线→刷新→进度划线不丢→第二设备接续', async ({page, browser}) => {
    // ---- 上传并打开 ----
    await page.goto('/')
    await page.locator('#upload-input').setInputFiles(['e2e/fixtures/m1-e2e.epub'])
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)

    // ---- 读书:翻到第二章(目录跳转),等进度自动上报 ----
    await page.getByTestId('toc-toggle').click()
    await page.getByTestId('toc-item').filter({hasText: '卷二 · 后出师表'}).click()
    await expect.poll(() => renderedText(page)).toContain('后出师表')

    await expect.poll(() => serverProgress(page), {timeout: 15_000}).not.toBeNull()
    const percentBefore = (await serverProgress(page))!.percent

    // ---- 在第二章划一条线 ----
    await dragSelectFirstParagraph(page)
    await page.getByTestId('create-highlight').click()
    await expect(page.getByTestId('highlights-toggle')).toContainText('划线(1)')

    // ---- 刷新(同设备):进度接续 + 划线仍在 ----
    await page.reload()
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await expect(page.getByTestId('book-card').getByTestId('book-progress')).toContainText(/已读 \d+%|未读/)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
    await expect.poll(() => renderedText(page)).toContain('后出师表') // 接续到同一章
    await expect(page.getByTestId('reader-progress-label')).toHaveText(`${percentBefore}%`)
    await expect(page.getByTestId('highlights-toggle')).toContainText('划线(1)')

    // ---- 第二个浏览器上下文 = 换设备(全新 localStorage,无任何本地状态) ----
    const deviceB = await browser.newContext()
    const pageB = await deviceB.newPage()
    await pageB.goto('/')

    // 书库列表即见真实进度百分比(与实际接续位置一致,FR-103/203)
    await expect(pageB.getByTestId('book-card').getByTestId('book-progress'))
        .toHaveText(`已读 ${percentBefore}%`)

    await pageB.getByTestId('book-card').click()
    await expect(pageB.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(pageB)

    // 接续到同一位置,划线齐全(D-24 全量拉取)
    await expect.poll(() => renderedText(pageB)).toContain('后出师表')
    await expect(pageB.getByTestId('reader-progress-label')).toHaveText(`${percentBefore}%`)
    await expect(pageB.getByTestId('highlights-toggle')).toContainText('划线(1)')
    await pageB.getByTestId('highlights-toggle').click()
    await expect(pageB.getByTestId('highlight-item')).toHaveCount(1)
    await deviceB.close()
})
