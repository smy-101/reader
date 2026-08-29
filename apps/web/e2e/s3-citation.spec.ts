import {expect, test, type Page} from '@playwright/test'
import {resetBackend} from './global-setup'
import {E2E_STUB_BASE_URL, E2E_STUB_REPLY_FULL, stubLastRequestBody} from './stub-llm'
import {renderedText, uploadFiles, waitForRendered} from './helpers'

/**
 * M4-05:S3 定位原文全链路(Seam B,stub embeddings + chat,零外网依赖):
 * 嵌入完成 → AI 面板「定位原文」入口 → 检索式提问 → 引用条随 meta 在流式开始前可见
 * → 流式回复照常 → 点击引用跳转到对应章节并定位摘录 → 刷新后引用仍在(refs 持久化);
 * 未配置 embedding → 入口隐藏不报错(FR-403)。
 */

/** 经设置页 UI 配置模型指向 stub;embeddingModel 传空串 = 不配置 embedding。 */
async function configureModelViaUi(page: Page, embeddingModel = 'bge-m3'): Promise<void> {
    await page.getByTestId('model-settings-open').click()
    await expect(page.getByTestId('model-settings-dialog')).toBeVisible()
    await page.getByTestId('model-base-url').fill(E2E_STUB_BASE_URL)
    await page.getByTestId('model-api-key').fill('sk-e2e-stub')
    await page.getByTestId('model-chat-model').fill('stub-chat')
    await page.getByTestId('model-embedding-model').fill(embeddingModel)
    await page.getByTestId('model-settings-save').click()
    await expect(page.getByTestId('model-saved-hint')).toBeVisible()
    await page.getByTestId('model-settings-close').click()
}

test.beforeEach(async () => {
    await resetBackend()
})

test('S3 全链路:定位原文提问 → 引用条 → 跳转章节定位摘录 → 刷新后引用仍在', async ({page}) => {
    await page.goto('/')
    await configureModelViaUi(page)
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('已嵌入 · bge-m3', {timeout: 30_000})

    // 打开书(起于第 1 章:出师表)与 AI 面板
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
    await page.getByTestId('ai-toggle').click()
    await expect(page.getByTestId('ai-panel')).toBeVisible()

    // 「定位原文」入口仅嵌入完成时显示;开启 S3 模式
    await expect(page.getByTestId('ai-retrieval-toggle')).toBeVisible()
    await page.getByTestId('ai-retrieval-toggle').click()
    await expect(page.getByTestId('ai-retrieval-toggle')).toHaveText('定位原文:开')

    // 问赤壁赋内容(第 4 章独有):检索应命中第 4 章
    await page.getByTestId('ai-input').fill('壬戌之秋七月既望苏子与客泛舟游于赤壁之下在哪一章?')
    await page.getByTestId('ai-send').click()

    // 引用条可见(随 meta 下发,流式开始前)且首条命中第 4 章
    await expect(page.getByTestId('ai-citation').first()).toBeVisible({timeout: 15_000})
    await expect(page.getByTestId('ai-citation-chapter').first()).toContainText('第4章')
    await expect(page.getByTestId('ai-citation-chapter').first()).toContainText('赤壁赋')

    // 流式回复照常渲染(S3 气泡含引用条,断言落在内容节点)
    await expect(page.getByTestId('ai-streaming')).toBeVisible()
    await expect(page.getByTestId('ai-assistant-msg').last().getByTestId('ai-msg-content')).toHaveText(E2E_STUB_REPLY_FULL)

    // 发给上游的 prompt 为检索式装配(经 stub 断言)
    const prompt = await stubLastRequestBody()
    expect(prompt).toContain('检索到的相关段落')
    expect(prompt).toContain('壬戌之秋')

    // 点击引用:从第 1 章跳到第 4 章,正文渲染出该章内容并定位到摘录
    await page.getByTestId('ai-citation').first().click()
    await expect.poll(() => renderedText(page), {timeout: 10_000}).toContain('清风徐来')

    // 刷新重开:引用条仍在(refs 持久化在助手消息上)
    await page.reload()
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
    await page.getByTestId('ai-toggle').click()
    await expect(page.getByTestId('ai-msg-citations')).toBeVisible()
    await expect(page.getByTestId('ai-citation-chapter').first()).toContainText('赤壁赋')
})

test('未配置 embedding → 无「定位原文」入口,不报错(FR-403)', async ({page}) => {
    await page.goto('/')
    await configureModelViaUi(page, '')
    await uploadFiles(page, 'm1-e2e.epub')
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)

    await page.getByTestId('ai-toggle').click()
    await expect(page.getByTestId('ai-panel')).toBeVisible()
    await expect(page.getByTestId('ai-retrieval-toggle')).toHaveCount(0)

    // 普通提问照常可用(S2 不受影响)
    await page.getByTestId('ai-input').fill('这一章讲了什么?')
    await page.getByTestId('ai-send').click()
    await expect(page.getByTestId('ai-assistant-msg').last()).toHaveText(E2E_STUB_REPLY_FULL)
})
