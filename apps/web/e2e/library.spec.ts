import {expect, test} from '@playwright/test'
import {resetBackend} from './global-setup'

/**
 * M1-03 首批 E2E:02 已交付的行为(书库列表 / 上传 / 幂等 / 过滤)。
 * fixture:m1-e2e.epub(多章嵌套目录,05/07/08 复用)+ second.epub(第二本书)。
 */

test.beforeEach(async () => {
    await resetBackend()
})

test('上传单本 → 书库列表出现(封面、标题可见)', async ({page}) => {
    await page.goto('/')
    await expect(page.getByTestId('empty-library')).toBeVisible()

    await uploadFiles(page, 'm1-e2e.epub')

    await expect(page.getByTestId('upload-result')).toContainText('已入库')
    const card = page.getByTestId('book-card')
    await expect(card).toHaveCount(1)
    await expect(card.getByTestId('book-title')).toHaveText('赤壁赋与前后出师表')
    await expect(card.locator('img[alt*="封面"]')).toBeVisible()
})

test('批量上传 → 全部出现;重复上传 → "已在书库"', async ({page}) => {
    await page.goto('/')

    await uploadFiles(page, 'm1-e2e.epub', 'second.epub')
    await expect(page.getByTestId('book-card')).toHaveCount(2)
    await expect(page.getByTestId('book-card').first().getByTestId('book-title'))
        .toHaveText('fixture 正常书') // 新上传在前(backend 排序)
    await expect(page.getByTestId('book-card').nth(1).getByTestId('book-title'))
        .toHaveText('赤壁赋与前后出师表')

    // 同文件再传:幂等提示,不打断,列表不重复
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('upload-result')).toContainText('已在书库')
    await expect(page.getByTestId('book-card')).toHaveCount(2)
})

test('按标题过滤生效', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub', 'second.epub')
    await expect(page.getByTestId('book-card')).toHaveCount(2)

    const filter = page.getByTestId('filter-input')
    await filter.fill('赤壁赋')
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await expect(page.getByTestId('book-card').getByTestId('book-title')).toHaveText('赤壁赋与前后出师表')

    await filter.fill('不存在的书')
    await expect(page.getByTestId('no-match')).toBeVisible()

    await filter.fill('张三') // 作者过滤(second.epub 作者)
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await expect(page.getByTestId('book-card').getByTestId('book-title')).toHaveText('fixture 正常书')
})

/** 经隐藏 input 上传(Playwright setInputFiles 直接驱动文件输入,真实事件链路);等整批完成。 */
async function uploadFiles(page: import('@playwright/test').Page, ...names: string[]) {
    const input = page.locator('#upload-input')
    const button = page.locator('.upload-button')
    await input.setInputFiles(names.map(n => `e2e/fixtures/${n}`))
    // 整批完成:结果行数等于本批文件数,且按钮不再“上传中…”
    await expect(page.getByTestId('upload-result')).toHaveCount(names.length)
    await expect(button).not.toHaveClass(/busy/)
}
