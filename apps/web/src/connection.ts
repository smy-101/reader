/**
 * 运行时连接设置(M2 桌面壳,spec · Implementation Decisions):
 * 后端绝对地址 + token,localStorage 持久化;已配置时以它优先,未配置回退 M1 现状
 * (同源 + 构建期注入 token)——纯叠加,既有开发流与 E2E 零回归。
 * token 明文存 localStorage(个人工具,FR-404 同一已接受姿态;不进 URL、不进壳二进制)。
 */

export interface ConnectionConfig {
    /** 后端绝对地址,如 http://127.0.0.1:8080;末尾斜杠会被去掉 */
    baseUrl: string
    token: string
}

const KEY = 'reader-connection'

/** 读取已保存的连接配置;未配置/脏数据一律返回 null(视同未配置)。 */
export function loadConnection(): ConnectionConfig | null {
    try {
        const raw = localStorage.getItem(KEY)
        if (!raw) return null
        const parsed = JSON.parse(raw) as Partial<ConnectionConfig>
        if (typeof parsed.baseUrl !== 'string' || typeof parsed.token !== 'string') return null
        const baseUrl = normalizeBaseUrl(parsed.baseUrl)
        const token = parsed.token.trim()
        if (!baseUrl || !token) return null
        return {baseUrl, token}
    } catch {
        return null
    }
}

export function saveConnection(config: ConnectionConfig): void {
    localStorage.setItem(KEY, JSON.stringify(config))
}

/** 清除配置,回退默认(同源 + 构建期注入 token)。 */
export function clearConnection(): void {
    localStorage.removeItem(KEY)
}

/** 规整用户输入的地址:去空白与末尾斜杠;缺协议时默认补 http(R-8:连接地址默认 http)。 */
export function normalizeBaseUrl(input: string): string {
    let url = input.trim()
    if (!url) return ''
    if (!/^https?:\/\//i.test(url)) url = `http://${url}`
    return url.replace(/\/+$/, '')
}
