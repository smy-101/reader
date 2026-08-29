import {expect, test, type Page} from '@playwright/test'
import {resetBackend} from './global-setup'
import {E2E_STUB_BASE_URL} from './stub-llm'
import {uploadFiles} from './helpers'

/**
 * M4-04:嵌入状态卡(Seam B,stub embeddings 确定性向量,零外网依赖):
 * 配置 embedding → 上传 → 状态卡进度推进至完成;未配置 → 状态卡整体隐藏(FR-403);
 * 存量书手动首次嵌入;失败(独立 base_url 不可达)→ 可读错误 + 重试至完成。
 */

/** 经设置页 UI 配置模型指向 stub;embeddingModel 传空串 = 不配置 embedding。 */
async function configureModelViaUi(page: Page, embeddingModel = 'bge-m3', embeddingBaseUrl?: string): Promise<void> {
    await page.getByTestId('model-settings-open').click()
    await expect(page.getByTestId('model-settings-dialog')).toBeVisible()
    await page.getByTestId('model-base-url').fill(E2E_STUB_BASE_URL)
    await page.getByTestId('model-api-key').fill('sk-e2e-stub')
    await page.getByTestId('model-chat-model').fill('stub-chat')
    await page.getByTestId('model-embedding-model').fill(embeddingModel)
    // 独立地址一律显式覆盖(面板会回填旧值,不清空则沿用上次的独立地址)
    await page.getByTestId('model-embedding-base-url').fill(embeddingBaseUrl ?? '')
    await page.getByTestId('model-settings-save').click()
    await expect(page.getByTestId('model-saved-hint')).toBeVisible()
    await page.getByTestId('model-settings-close').click()
}

test.beforeEach(async () => {
    await resetBackend()
})

test('配置 embedding → 上传 → 状态卡进度推进至完成', async ({page}) => {
    await page.goto('/')
    await configureModelViaUi(page)
    await uploadFiles(page, 'm1-e2e.epub')

    // 状态卡出现(上传自动建任务)且推进到完成,显示所用模型
    const card = page.getByTestId('embedding-card').first()
    await expect(card).toBeVisible()
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('已嵌入 · bge-m3', {timeout: 30_000})

    // 向量块已落库(直连断言:块数与归属)
    const chunks = await page.evaluate(async () => {
        const res = await fetch('/api/books/1/embedding', {headers: {Authorization: 'Bearer reader-dev-token'}})
        return res.json()
    })
    expect(chunks.status).toBe('done')
})

test('未配置 embedding → 状态卡与入口全部隐藏不报错(FR-403)', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await expect(page.getByTestId('embedding-card')).toHaveCount(0)
})

test('存量书(未配置期上传)手动触发首次嵌入至完成', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('embedding-card')).toHaveCount(0) // 未配置:隐藏

    await configureModelViaUi(page) // 配置后状态卡出现(设置页关闭即刷新显隐)
    await expect(page.getByTestId('embedding-card')).toHaveCount(1)
    await expect(page.getByTestId('embedding-status')).toHaveText('未嵌入')

    await page.getByTestId('embedding-trigger').click()
    await expect(page.getByTestId('embedding-status')).toContainText('已嵌入 · bge-m3', {timeout: 30_000})
})

test('嵌入失败(独立 base_url 不可达)→ 可读错误 + 重试至完成', async ({page}) => {
    await page.goto('/')
    await configureModelViaUi(page, 'bge-m3', 'http://127.0.0.1:9/v1') // 独立 embedding 地址不可达
    await uploadFiles(page, 'm1-e2e.epub')

    await expect(page.getByTestId('embedding-status').first())
        .toContainText('嵌入失败', {timeout: 30_000})

    // 修复配置(去掉独立地址,跟随 chat stub)→ 重试 → 完成
    await configureModelViaUi(page)
    await page.getByTestId('embedding-trigger').first().click()
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('已嵌入 · bge-m3', {timeout: 30_000})
})

test('换模型 → 状态卡提示需重新嵌入 → 重嵌入入口跑通至新模型(US 13)', async ({page}) => {
    await page.goto('/')
    await configureModelViaUi(page, 'bge-m3')
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('已嵌入 · bge-m3', {timeout: 30_000})

    // 换 embedding 模型:状态卡明示需重新嵌入(不再静默停留在旧模型)
    await configureModelViaUi(page, 'bge-m3-v2')
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('模型已更换', {timeout: 10_000})
    await expect(page.getByTestId('embedding-status').first()).toContainText('bge-m3-v2')

    // 重新嵌入(同一触发入口)→ 全量重嵌入至新模型
    await page.getByTestId('embedding-trigger').first().click()
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('已嵌入 · bge-m3-v2', {timeout: 30_000})
})
