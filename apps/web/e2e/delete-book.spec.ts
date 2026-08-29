import {expect, test, type Page} from '@playwright/test'
import {resetBackend} from './global-setup'
import {E2E_STUB_BASE_URL, E2E_STUB_REPLY_FULL} from './stub-llm'
import {uploadFiles} from './helpers'

/**
 * M3-06:删除书籍完整级联(FR-104 兑现)。
 * E2E:删书入口 → 确认弹窗明示级联范围 → 删除后书库即时移出、该书及其会话端点 404;
 * 他书不受影响。磁盘清理与跨书会话保留的彻底断言在 Seam A(BookDeletionIntegrationTest)。
 */

test.beforeEach(async () => {
    await resetBackend()
})

/** 直连配置 stub 模型(快路径;设置页 UI 链路在 ai-chat.spec 已覆盖)。 */
async function configureStubModel(page: Page): Promise<void> {
    await page.evaluate(async url => {
        await fetch('/api/settings/model', {
            method: 'PUT',
            headers: {Authorization: 'Bearer reader-dev-token', 'Content-Type': 'application/json'},
            body: JSON.stringify({baseUrl: url, apiKey: 'sk-e2e', chatModel: 'stub-chat'}),
        })
    }, E2E_STUB_BASE_URL)
}

test('删书确认弹窗明示级联范围_删除后书库与该书会话清净_他书不受影响', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub', 'second.epub')
    await expect(page.getByTestId('book-card')).toHaveCount(2)
    await configureStubModel(page)

    // 给 m1-e2e(id=1)造一场会话(经真实提问链路;新上传在前,用 data-book-id 精确定位)
    const firstCard = page.locator('.book-card-wrap').filter({has: page.locator('[data-book-id="1"]')})
    await firstCard.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await page.getByTestId('ai-toggle').click()
    await page.getByTestId('ai-input').fill('会随书删除的会话内容')
    await page.getByTestId('ai-send').click()
    await expect(page.getByTestId('ai-assistant-msg').last()).toHaveText(E2E_STUB_REPLY_FULL)
    await page.getByTestId('back-to-library').click()
    await expect(page.getByTestId('book-card')).toHaveCount(2)

    // 删书入口 + 确认弹窗明示级联范围(FR-104)
    await firstCard.getByTestId('delete-book-button').click()
    await expect(page.getByTestId('delete-book-dialog')).toBeVisible()
    const scope = page.getByTestId('delete-book-scope')
    await expect(scope).toContainText('书源文件与封面')
    await expect(scope).toContainText('划线')
    await expect(scope).toContainText('阅读进度')
    await expect(scope).toContainText('AI 会话')

    // 取消不动;再删真删
    await page.getByTestId('delete-book-cancel').click()
    await expect(page.getByTestId('delete-book-dialog')).toHaveCount(0)
    await expect(page.getByTestId('book-card')).toHaveCount(2)

    await firstCard.getByTestId('delete-book-button').click()
    await page.getByTestId('delete-book-confirm').click()
    await expect(page.getByTestId('delete-book-dialog')).toHaveCount(0)
    await expect(page.getByTestId('book-card')).toHaveCount(1) // 书库即时移出,他书仍在
    await expect(page.getByTestId('book-title')).toContainText('fixture 正常书')

    // 该书及其会话端点 404(级联清净);他书(第二本)的会话端点正常
    const gone = await page.evaluate(async () => {
        const headers = {Authorization: 'Bearer reader-dev-token'}
        const book = await fetch('/api/books/1', {headers})
        const sessions = await fetch('/api/books/1/sessions', {headers})
        const other = await fetch('/api/books/2/sessions', {headers})
        return {book: book.status, sessions: sessions.status, otherSessions: other.status}
    })
    expect(gone).toEqual({book: 404, sessions: 404, otherSessions: 200})
})

test('删除不存在的书_后端404_弹窗报可读错误', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub')
    // 先经 UI 打开弹窗,再在确认前把书直连删掉,弹窗的删除应得到 404 可读文案
    await page.evaluate(async () => {
        await fetch('/api/books/1', {method: 'DELETE', headers: {Authorization: 'Bearer reader-dev-token'}})
    })
    await page.getByTestId('delete-book-button').first().click()
    await page.getByTestId('delete-book-confirm').click()
    await expect(page.getByTestId('delete-book-error')).toContainText('不存在')
})
