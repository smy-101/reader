import {expect, test, type Page} from '@playwright/test'
import {resetBackend} from './global-setup'
import {E2E_STUB_BASE_URL, E2E_STUB_REPLY_FULL, stubLastRequestBody, stubSetEmbeddingsFailure} from './stub-llm'
import {renderedText, uploadFiles, waitForRendered} from './helpers'

/**
 * S4 跨书对比(Seam B,stub embeddings + chat,零外网依赖):
 * 书库页全局「AI 伴读」入口显隐(FR-403)→ 跨书会话面板四件事 → 全库检索跨书引用
 * (随 meta 在流式开始前可见、刷新仍在)→ 点击跨书引用打开另一本书并定位(S3 同口径)
 * → 提问出错显式提示(FR-303)→ 删书后跨书会话保留、悬空引用占位(D-33)。
 */

const M1_TITLE = '赤壁赋与前后出师表'
const SECOND_TITLE = 'fixture 正常书'
/** 跨书问句:混两本书各自词域(赤壁/泛舟 ↔ 第一章/起点/正文),袋向量确定性命中两书。 */
const CROSS_QUESTION = '赤壁泛舟与第一章起点的正文分别怎么说?'

/** 经设置页 UI 配置模型指向 stub;embeddingModel 传空串 = 不配置 embedding。 */
async function configureModelViaUi(page: Page, embeddingModel = 'bge-m3', embeddingBaseUrl?: string): Promise<void> {
    await page.getByTestId('model-settings-open').click()
    await expect(page.getByTestId('model-settings-dialog')).toBeVisible()
    await page.getByTestId('model-base-url').fill(E2E_STUB_BASE_URL)
    await page.getByTestId('model-api-key').fill('sk-e2e-stub')
    await page.getByTestId('model-chat-model').fill('stub-chat')
    await page.getByTestId('model-embedding-model').fill(embeddingModel)
    await page.getByTestId('model-embedding-base-url').fill(embeddingBaseUrl ?? '')
    await page.getByTestId('model-settings-save').click()
    await expect(page.getByTestId('model-saved-hint')).toBeVisible()
    await page.getByTestId('model-settings-close').click()
}

/** 配置 + 上传两本书 + 等两本嵌入完成(入口亮起)。 */
async function prepareTwoEmbeddedBooks(page: Page): Promise<void> {
    await page.goto('/')
    await configureModelViaUi(page)
    await uploadFiles(page, 'm1-e2e.epub', 'second.epub')
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('已嵌入 · bge-m3', {timeout: 30_000})
    await expect(page.getByTestId('embedding-status').nth(1))
        .toContainText('已嵌入 · bge-m3', {timeout: 30_000})
}

/** 打开全局面板并发起一次跨书提问,等首条引用可见。 */
async function askAndWaitCitations(page: Page): Promise<void> {
    await page.getByTestId('global-ai-entry').click()
    await expect(page.getByTestId('global-ai-panel')).toBeVisible()
    await page.getByTestId('global-ai-input').fill(CROSS_QUESTION)
    await page.getByTestId('global-ai-send').click()
    await expect(page.getByTestId('ai-citation').first()).toBeVisible({timeout: 15_000})
}

test.beforeEach(async () => {
    await resetBackend()
})

// ---- 入口显隐(FR-403) ----

test('未配置 embedding → 无全局入口,不报错(FR-403)', async ({page}) => {
    await page.goto('/')
    await configureModelViaUi(page, '')
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('book-card')).toHaveCount(1)
    await expect(page.getByTestId('global-ai-entry')).toHaveCount(0)
})

test('全库无就绪书(嵌入失败)→ 入口隐藏;修复重嵌入后出现', async ({page}) => {
    await page.goto('/')
    await configureModelViaUi(page, 'bge-m3', 'http://127.0.0.1:9/v1') // embedding 地址不可达 → 嵌入必败
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('嵌入失败', {timeout: 30_000})
    await expect(page.getByTestId('global-ai-entry')).toHaveCount(0)

    // 修复配置 → 重试嵌入 → 完成后入口自动亮起(列表就绪摘要收敛)
    await configureModelViaUi(page)
    await page.getByTestId('embedding-trigger').first().click()
    await expect(page.getByTestId('embedding-status').first())
        .toContainText('已嵌入 · bge-m3', {timeout: 30_000})
    await expect(page.getByTestId('global-ai-entry')).toBeVisible({timeout: 10_000})
})

// ---- 主链路:跨书提问 + 引用条 + 持久化 ----

test('两书就绪 → 跨书提问 → 流式回复 + 两书引用条(流式开始前可见)→ 刷新仍在', async ({page}) => {
    await prepareTwoEmbeddedBooks(page)
    await askAndWaitCitations(page)

    // 引用命中跨两本书(书名随引用可见;流式开始前即可见;top-k 含同书多块,只断言至少一条)
    await expect(page.getByTestId('ai-citation-chapter').filter({hasText: `《${M1_TITLE}》`}).first())
        .toBeVisible({timeout: 5_000})
    await expect(page.getByTestId('ai-citation-chapter').filter({hasText: `《${SECOND_TITLE}》`}).first())
        .toBeVisible({timeout: 5_000})

    // 流式回复照常渲染
    await expect(page.getByTestId('global-ai-streaming')).toBeVisible()
    await expect(page.getByTestId('ai-assistant-msg').last().getByTestId('ai-msg-content'))
        .toHaveText(E2E_STUB_REPLY_FULL)

    // 发给上游的 prompt 为多书检索装配(带〔书名·第 N 章〕溯源头,经 stub 断言)
    const prompt = await stubLastRequestBody()
    expect(prompt).toContain('检索到的相关段落')
    expect(prompt).toContain(`《${SECOND_TITLE}》·第`)
    expect(prompt).toContain(`《${M1_TITLE}》·第`)

    // 刷新重开:跨书会话、消息与引用都在(refs 持久化)
    await page.reload()
    await page.getByTestId('global-ai-entry').click()
    await expect(page.getByTestId('global-ai-panel')).toBeVisible()
    await expect(page.getByTestId('global-ai-session-item')).toHaveCount(1)
    await expect(page.getByTestId('ai-msg-citations')).toBeVisible()
    await expect(page.getByTestId('ai-citation-chapter').filter({hasText: `《${SECOND_TITLE}》`}).first()).toBeVisible()
    await expect(page.getByTestId('ai-citation-chapter').filter({hasText: `《${M1_TITLE}》`}).first()).toBeVisible()
})

test('续问落同一跨书会话;会话可重命名可删除', async ({page}) => {
    await prepareTwoEmbeddedBooks(page)
    await page.getByTestId('global-ai-entry').click()
    await expect(page.getByTestId('global-ai-panel')).toBeVisible()
    await page.getByTestId('global-ai-input').fill(CROSS_QUESTION)
    await page.getByTestId('global-ai-send').click()
    await expect(page.getByTestId('ai-citation').first()).toBeVisible({timeout: 15_000})
    await expect(page.getByTestId('global-ai-session-item')).toHaveCount(1)

    // 续问落同一会话
    await page.getByTestId('global-ai-input').fill('再展开讲讲?')
    await page.getByTestId('global-ai-send').click()
    await expect(page.getByTestId('ai-assistant-msg').nth(1).getByTestId('ai-msg-content'))
        .toHaveText(E2E_STUB_REPLY_FULL, {timeout: 15_000})
    await expect(page.getByTestId('global-ai-session-item')).toHaveCount(1)

    // 重命名
    await page.getByTestId('global-ai-rename-button').click()
    await page.getByTestId('global-ai-rename-input').fill('两书对比')
    await page.getByTestId('global-ai-rename-confirm').click()
    await expect(page.getByTestId('global-ai-session-title')).toHaveText('两书对比')

    // 删除 → 列表清空
    await page.getByTestId('global-ai-delete-session').click()
    await expect(page.getByTestId('global-ai-session-item')).toHaveCount(0)
})

test('跨书会话不出现在任何书级 AI 面板列表里', async ({page}) => {
    await prepareTwoEmbeddedBooks(page)
    await askAndWaitCitations(page)
    const sessionTitle = await page.getByTestId('global-ai-session-title').textContent()
    await page.getByTestId('global-ai-close').click()

    // 打开第二本书(新上传在前,首卡即 second.epub)的书级面板:列表为空(不含跨书会话),书级提问照常
    await page.getByTestId('book-card').nth(0).click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
    await page.getByTestId('ai-toggle').click()
    await expect(page.getByTestId('ai-panel')).toBeVisible()
    await expect(page.getByTestId('ai-session-item')).toHaveCount(0)
    await expect(page.getByTestId('ai-session-list')).not.toContainText(sessionTitle!)

    await page.getByTestId('ai-input').fill('这一章讲了什么?')
    await page.getByTestId('ai-send').click()
    await expect(page.getByTestId('ai-assistant-msg').last().getByTestId('ai-msg-content'))
        .toHaveText(E2E_STUB_REPLY_FULL, {timeout: 15_000})
})

// ---- 提问出错显式提示(FR-303) ----

test('提问中途上游故障 → 显式错误提示,不永久转圈(FR-303)', async ({page}) => {
    await prepareTwoEmbeddedBooks(page)
    await stubSetEmbeddingsFailure(true) // 检索向量化一步即失败 → 502 可读文案
    await page.getByTestId('global-ai-entry').click()
    await expect(page.getByTestId('global-ai-panel')).toBeVisible()
    await page.getByTestId('global-ai-input').fill(CROSS_QUESTION)
    await page.getByTestId('global-ai-send').click()

    await expect(page.getByTestId('global-ai-error')).toBeVisible({timeout: 15_000})
    await expect(page.getByTestId('global-ai-error')).toContainText('Embedding 服务')
    // 不悬挂:输入区恢复可用(流式态结束)
    await expect(page.getByTestId('global-ai-input')).toBeEnabled()
})

// ---- 跨书引用跳转(03) ----

test('点击另一本书的引用 → 打开该书阅读器并定位章节摘录', async ({page}) => {
    await prepareTwoEmbeddedBooks(page)
    await askAndWaitCitations(page)

    // 点击「当前没在读的那本书」(second.epub)的引用且选非首章(第二章·表格与图片,
    // 默认打开位置是首章,非首章才能证明真跳了):从书库一步进入该书阅读器
    await page.getByTestId('ai-citation').filter({hasText: '表格与图片'}).first().click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await expect(page.getByTestId('reader-title')).toHaveText(SECOND_TITLE)
    await expect.poll(() => renderedText(page), {timeout: 10_000}).toContain('表格与图片')
    await expect.poll(() => renderedText(page), {timeout: 5_000}).toContain('本段文字保留')

    // 退回书库,再点 m1 书的非首章引用(第五章·哀吾生之须臾):同样打开并定位到命中章
    await page.getByTestId('back-to-library').click()
    await expect(page.getByTestId('global-ai-entry')).toBeVisible()
    await page.getByTestId('global-ai-entry').click()
    await page.getByTestId('ai-citation').filter({hasText: '哀吾生之须臾'}).first().click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await expect(page.getByTestId('reader-title')).toHaveText(M1_TITLE)
    await expect.poll(() => renderedText(page), {timeout: 10_000}).toContain('寄蜉蝣于天地')
})

// ---- D-33:删书留会话 + 占位渲染(04) ----

test('删书 → 跨书会话与引用保留,被删书的引用降级占位不可点(D-33)', async ({page}) => {
    await prepareTwoEmbeddedBooks(page)
    await askAndWaitCitations(page)
    const sessionTitle = await page.getByTestId('global-ai-session-title').textContent()
    await page.getByTestId('global-ai-close').click()

    // 在 second.epub(首卡)里建一个书级会话(级联对照)
    await page.getByTestId('book-card').nth(0).click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)
    await page.getByTestId('ai-toggle').click()
    await page.getByTestId('ai-input').fill('书级问题')
    await page.getByTestId('ai-send').click()
    await expect(page.getByTestId('ai-assistant-msg').last().getByTestId('ai-msg-content'))
        .toHaveText(E2E_STUB_REPLY_FULL, {timeout: 15_000})
    await page.getByTestId('back-to-library').click()

    // 删除 second.epub(首卡;书级会话随之级联清;跨书会话不级联)
    await page.getByTestId('delete-book-button').nth(0).click()
    await expect(page.getByTestId('delete-book-dialog')).toBeVisible()
    await page.getByTestId('delete-book-confirm').click()
    await expect(page.getByTestId('book-card')).toHaveCount(1)

    // 重开跨书会话:会话、消息与引用仍在;被删书的引用显示占位且不可点,未删书的照常
    await page.getByTestId('global-ai-entry').click()
    await expect(page.getByTestId('global-ai-panel')).toBeVisible()
    await expect(page.getByTestId('global-ai-session-title')).toHaveText(sessionTitle!)
    const degraded = page.getByTestId('ai-citation').filter({hasText: '原书已删除'})
    await expect(degraded.first()).toBeVisible()
    await expect(degraded.first()).toContainText(`《${SECOND_TITLE}》(原书已删除)`)
    await expect(degraded.first()).toBeDisabled()
    const alive = page.getByTestId('ai-citation').filter({hasText: `《${M1_TITLE}》`})
    await expect(alive.first()).toBeVisible()
    await expect(alive.first()).toBeEnabled()

    // 未删书的引用照常可跳(03 已交付)
    await alive.first().click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await expect(page.getByTestId('reader-title')).toHaveText(M1_TITLE)

    // 书级会话级联回归:被删书的会话随书消失(直连断言)
    const sessions = await page.evaluate(async () => {
        const res = await fetch('/api/sessions', {headers: {Authorization: 'Bearer reader-dev-token'}})
        return res.json()
    })
    expect(sessions).toHaveLength(1) // 只剩跨书会话
    expect(sessions[0].bookId).toBeNull()
})
