import {expect, test, type Page} from '@playwright/test'
import {resetBackend} from './global-setup'
import {E2E_STUB_BASE_URL, E2E_STUB_REPLY_FULL, stubLastRequestBody} from './stub-llm'
import {dragSelectFirstParagraph, uploadFiles, waitForRendered} from './helpers'

/**
 * M3-04:S2 当前书问答全链路(Seam B,E2E harness 内置本地流式 stub LLM,零外网依赖):
 * 设置页 UI 配置指向 stub → S2 提问(缺省携带当前阅读位置所在章,D-31)→ 流式增量渲染
 * → 会话与消息落库(直连断言)→ 刷新/重开仍在 → 重命名/删除会话;未配置模型设置 → 引导文案。
 */

/** 经设置页 UI 配置模型指向 stub(复用 M3-01 的设置页)。 */
async function configureModelViaUi(page: Page): Promise<void> {
    await page.getByTestId('model-settings-open').click()
    await expect(page.getByTestId('model-settings-dialog')).toBeVisible()
    // 首次配置:表单全空
    await page.getByTestId('model-base-url').fill(E2E_STUB_BASE_URL)
    await page.getByTestId('model-api-key').fill('sk-e2e-stub')
    await page.getByTestId('model-chat-model').fill('stub-chat')
    await page.getByTestId('model-settings-test').click()
    await expect(page.getByTestId('model-test-chat-result')).toContainText('连接成功')
    await expect(page.getByTestId('model-test-embedding-result')).toContainText('跳过')
    await page.getByTestId('model-settings-save').click()
    await expect(page.getByTestId('model-saved-hint')).toBeVisible()
    await page.getByTestId('model-settings-close').click()
}

/** 直连测试后端拉某书会话与消息(落库断言)。 */
async function serverSessions(page: Page): Promise<{ id: number; title: string }[]> {
    return page.evaluate(async () => {
        const res = await fetch('/api/books/1/sessions', {headers: {Authorization: 'Bearer reader-dev-token'}})
        return res.json()
    })
}

async function serverMessages(page: Page, sessionId: number): Promise<{ role: string; content: string; refs: unknown[] | null }[]> {
    return page.evaluate(async sessionId => {
        const res = await fetch(`/api/sessions/${sessionId}/messages`, {headers: {Authorization: 'Bearer reader-dev-token'}})
        return res.json()
    }, sessionId)
}

test.beforeEach(async () => {
    await resetBackend()
})

test('S2 全链路:配置 → 提问携带当前章 → 流式渲染 → 落库 → 刷新仍在 → 重命名/删除', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub')
    await configureModelViaUi(page)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page) // 等正文渲染与首次 relocate(目标章映射依赖,D-31)

    // 打开 AI 面板,发起 S2 提问(缺省携带当前阅读位置所在章,D-31)
    await page.getByTestId('ai-toggle').click()
    await expect(page.getByTestId('ai-panel')).toBeVisible()
    await page.getByTestId('ai-input').fill('这一章讲了什么?')
    await page.getByTestId('ai-send').click()

    // 流式:发送即进入回复中状态(ai-streaming 出现),逐块增长到完整回复
    await expect(page.getByTestId('ai-streaming')).toBeVisible()
    await expect(page.getByTestId('ai-assistant-msg').last()).toHaveText(E2E_STUB_REPLY_FULL)
    await expect(page.getByTestId('ai-user-msg').last()).toContainText('这一章讲了什么?')

    // 目标章:当前阅读位置在第 1 章(出师表),引用可见 + 发给 stub 的 prompt 含章节正文
    await expect(page.getByTestId('ai-msg-ref-chapter').last()).toContainText('出师表')
    const prompt = await stubLastRequestBody()
    expect(prompt).toContain('先帝创业未半') // 第 1 章正文进 prompt(经 stub 断言预算装配)
    expect(prompt).toContain('这一章讲了什么?')

    // 落库直连断言:会话(标题=首条提问)+ 两条消息(assistant=完整回复,refs 含章节)
    const sessions = await serverSessions(page)
    expect(sessions).toHaveLength(1)
    expect(sessions[0].title).toBe('这一章讲了什么?')
    const messages = await serverMessages(page, sessions[0].id)
    expect(messages.map(m => m.role)).toEqual(['user', 'assistant'])
    expect(messages[1].content).toBe(E2E_STUB_REPLY_FULL)
    expect(JSON.stringify(messages[0].refs)).toContain('chapter')

    // 刷新/重开:会话与消息原样还在(持久,FR-301)
    await page.reload()
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await page.getByTestId('ai-toggle').click()
    await expect(page.getByTestId('ai-session-title')).toHaveText('这一章讲了什么?')
    await expect(page.getByTestId('ai-assistant-msg').last()).toHaveText(E2E_STUB_REPLY_FULL)

    // 重命名会话(FR-304)
    await page.getByTestId('ai-rename-button').click()
    await page.getByTestId('ai-rename-input').fill('重命名后的会话')
    await page.getByTestId('ai-rename-confirm').click()
    await expect(page.getByTestId('ai-session-title')).toHaveText('重命名后的会话')

    // 再问一轮:落在重命名后的同一会话(缺省=最近活跃,D-32)
    await page.getByTestId('ai-input').fill('继续问')
    await page.getByTestId('ai-send').click()
    await expect(page.getByTestId('ai-assistant-msg').last()).toHaveText(E2E_STUB_REPLY_FULL)
    expect(await page.getByTestId('ai-session-item').count()).toBe(1)
    const after = await serverSessions(page)
    expect(after).toHaveLength(1)

    // 删除会话:列表与消息流清空,DB 无残留
    await page.getByTestId('ai-delete-session').click()
    await expect(page.getByTestId('ai-session-item')).toHaveCount(0)
    await expect(page.getByTestId('ai-user-msg')).toHaveCount(0)
    expect(await serverSessions(page)).toHaveLength(0)
})

test('未配置模型设置提问 → 显式引导文案', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub')
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()

    await page.getByTestId('ai-toggle').click()
    await page.getByTestId('ai-input').fill('直接提问不配置')
    await page.getByTestId('ai-send').click()

    await expect(page.getByTestId('ai-error')).toContainText('尚未配置模型设置')
    // 未受理:不建会话、不落消息
    expect(await serverSessions(page)).toHaveLength(0)
})

test('S1 划选 → 问 AI → 流式返回 → 选中引用落库(自动落最近活跃会话,D-32)', async ({page}) => {
    await page.goto('/')
    await uploadFiles(page, 'm1-e2e.epub')
    await configureModelViaUi(page)
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)

    // 先有一场 S2 对话(该书最近活跃会话存在)
    await page.getByTestId('ai-toggle').click()
    await page.getByTestId('ai-input').fill('先聊两句')
    await page.getByTestId('ai-send').click()
    await expect(page.getByTestId('ai-assistant-msg').last()).toBeVisible({timeout: 15_000})

    // 划选(真实拖选手势,复用 M1 验证链路)→ 划选菜单「问 AI」→ 面板带出选中引用
    await dragSelectFirstParagraph(page)
    await page.getByTestId('ask-ai').click()
    await expect(page.getByTestId('ai-panel')).toBeVisible()
    await expect(page.getByTestId('ai-selection-quote')).toBeVisible()

    await page.getByTestId('ai-input').fill('这段什么意思?')
    await page.getByTestId('ai-send').click()
    await expect(page.getByTestId('ai-streaming')).toBeVisible()

    // 同一会话(D-32):仍只有一场会话;等第 4 条消息(助手回复)落库
    const sessions = await serverSessions(page)
    expect(sessions).toHaveLength(1)
    await expect.poll(async () => (await serverMessages(page, sessions[0].id)).length,
        {timeout: 15_000}).toBe(4)
    const messages = await serverMessages(page, sessions[0].id)
    expect(messages[2].role).toBe('user')
    expect(JSON.stringify(messages[2].refs)).toContain('selection')
    expect(messages[3].role).toBe('assistant')
    expect(messages[3].content).toBe(E2E_STUB_REPLY_FULL)

    // 消息流中该条提问可见选中文字引用(引用可回溯,FR-301);回复完整渲染
    await expect(page.getByTestId('ai-msg-ref-selection').last()).toBeVisible()
    await expect(page.getByTestId('ai-assistant-msg').last()).toHaveText(E2E_STUB_REPLY_FULL)

    // S1 槽位:发给上游的 prompt 书内容 = 选中文字(不装整书/整章)
    const prompt = await stubLastRequestBody()
    expect(prompt).toContain('【选中文字】')
    expect(prompt).toContain('这段什么意思?')
})
