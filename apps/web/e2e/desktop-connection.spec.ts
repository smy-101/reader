import {expect, test} from '@playwright/test'
import {e2e, resetBackend} from './global-setup'
import {dragSelectFirstParagraph, renderedText, uploadFiles, waitForRendered} from './helpers'

/**
 * M2 "桌面等效连接"(Seam B,spec · Testing Decisions):
 * 经连接设置 UI 配绝对后端 URL + token——页面 origin(5173)≠ 后端端口(18080),
 * 且携带 Authorization 头 → 天然触发跨域 preflight + 实际跨域请求,
 * 这正是 Tauri 壳在 WebView2 里将执行的代码路径(D-7:壳零逻辑,功能全在 web 层)。
 */

const BACKEND = `http://localhost:${e2e.BACKEND_PORT}`
const TOKEN = e2e.TOKEN

/** 带token直连绝对后端的 GET(跨域,即壳内路径);非 2xx 返回 null。 */
async function apiGet<T>(page: import('@playwright/test').Page, path: string): Promise<T | null> {
    return page.evaluate(async ({url, token, path}) => {
        const res = await fetch(`${url}${path}`, {headers: {Authorization: `Bearer ${token}`}})
        if (!res.ok) return null
        return await res.json() as T
    }, {url: BACKEND, token: TOKEN, path})
}

/** 服务端划线数:直连绝对后端(跨域 GET,带 token)——与壳内同一路径。 */
async function serverHighlights(page: import('@playwright/test').Page, bookId = 1): Promise<number> {
    return (await apiGet<unknown[]>(page, `/api/books/${bookId}/highlights`))?.length ?? 0
}

/** 服务端进度:直连绝对后端;未上报返回 null。 */
function serverProgressAbsolute(page: import('@playwright/test').Page, bookId = 1): Promise<{ percent: number } | null> {
    return apiGet<{ percent: number }>(page, `/api/books/${bookId}/progress`)
}

test.beforeEach(async () => {
    await resetBackend()
})

test('桌面等效连接:配绝对 URL + token → 列表→上传→打开→划线/进度全链路且落库', async ({page}) => {
    await page.goto('/')

    // 经 UI 配置连接(未保存先测):测试连接走通真实鉴权 + 网络路径
    await page.getByTestId('connection-settings-open').click()
    await page.getByTestId('connection-url').fill(BACKEND)
    await page.getByTestId('connection-token').fill(TOKEN)
    await page.getByTestId('connection-test').click()
    await expect(page.getByTestId('connection-test-result')).toHaveText('连接成功')

    // 保存 → 整页 reload(弹窗消失即重载完成),配置 localStorage 持久化
    await page.getByTestId('connection-save').click()
    await expect(page.getByTestId('connection-dialog')).toHaveCount(0)
    await page.getByTestId('connection-settings-open').click()
    await expect(page.getByTestId('connection-url')).toHaveValue(BACKEND)
    await expect(page.getByTestId('connection-token')).toHaveValue(TOKEN)
    await page.getByTestId('connection-cancel').click()

    // 上传:此起一切 API 走绝对 baseUrl(跨域,Authorization 头触发 preflight)
    await uploadFiles(page, 'm1-e2e.epub')
    await expect(page.getByTestId('book-card')).toHaveCount(1)

    // 打开读书:书源文件/划线/进度全走跨域路径
    await page.getByTestId('book-card').click()
    await expect(page.getByTestId('reader-root')).toBeVisible()
    await waitForRendered(page)

    // 划线 → 落库,经后端直查可验证
    await dragSelectFirstParagraph(page)
    await page.getByTestId('create-highlight').click()
    await expect(page.getByTestId('highlights-toggle')).toContainText('划线(1)')
    await expect.poll(() => serverHighlights(page)).toBe(1)

    // 目录跳转 → 进度节流上报(800ms)→ 落库,经后端直查可验证
    await page.getByTestId('toc-toggle').click()
    await page.getByTestId('toc-item').filter({hasText: '卷二 · 后出师表'}).click()
    await expect.poll(() => renderedText(page)).toContain('后出师表')
    await expect.poll(() => serverProgressAbsolute(page), {timeout: 15_000}).not.toBeNull()
})

test('连接错误路径:不可达/错 token 可读提示,失败引导连接设置,恢复默认回退同源', async ({page}) => {
    await page.goto('/')
    await page.getByTestId('connection-settings-open').click()

    // 不可达地址:可读错误,设置页不崩
    await page.getByTestId('connection-url').fill('http://localhost:19999')
    await page.getByTestId('connection-token').fill(TOKEN)
    await page.getByTestId('connection-test').click()
    await expect(page.getByTestId('connection-test-result')).toContainText('无法连接到后端')

    // 正确地址 + 错 token:401 可读文案
    await page.getByTestId('connection-url').fill(BACKEND)
    await page.getByTestId('connection-token').fill('wrong-token')
    await page.getByTestId('connection-test').click()
    await expect(page.getByTestId('connection-test-result')).toContainText('token')

    // 保存不可达配置 → 书库错误可读且有连接设置入口(首次体验被引导而非白屏)
    await page.getByTestId('connection-url').fill('http://localhost:19999')
    await page.getByTestId('connection-token').fill(TOKEN)
    await page.getByTestId('connection-save').click()
    await expect(page.getByTestId('connection-dialog')).toHaveCount(0)
    await expect(page.getByTestId('error-settings-open')).toBeVisible()
    await expect(page.locator('.load-error .error')).toContainText('无法连接到后端')

    // 恢复默认 → 回退 M1 现状(同源 + 构建期注入 token),书库恢复可用
    await page.getByTestId('error-settings-open').click()
    await page.getByTestId('connection-clear').click()
    await expect(page.getByTestId('connection-dialog')).toHaveCount(0)
    await expect(page.getByTestId('empty-library')).toBeVisible()
})
