import {createClient, type ApiClient} from '@reader/api-client'
import {isTauriShell, loadConnection} from './connection'

/**
 * 全局唯一 client,构建规则(M2 运行时连接设置):
 * - 已配置连接:运行时配置优先(绝对 baseUrl + token)——桌面壳即此形态
 * - 未配置:回退 M1 现状——同源 + 构建期注入 token(VITE_READER_TOKEN,E2E 亦用此注入);
 *   但壳内(Tauri WebView)例外:同 origin 是资产协议,未命中路径一律回退 index.html,
 *   同源回退必失败——置 sameOriginBlocked 让请求直抛可读文案,首启即引导连接设置
 * 连接设置保存/清除后整页 reload,模块随页面重新初始化,无需运行时热切换。
 */
const connection = loadConnection()

export const api: ApiClient = connection
    ? createClient({baseUrl: connection.baseUrl, token: connection.token})
    : createClient({
          token: import.meta.env.VITE_READER_TOKEN ?? 'reader-dev-token',
          sameOriginBlocked: isTauriShell()
              ? '桌面端尚未配置后端连接:请打开连接设置,填入后端地址与 token'
              : undefined,
      })

/** 端上设备标识:本地生成持久化(Crypto UUID),随划线上报,仅展示/追溯用。 */
const DEVICE_KEY = 'reader-device-id'

export function deviceId(): string {
    let id = localStorage.getItem(DEVICE_KEY)
    if (!id) {
        id = (crypto.randomUUID?.() ?? `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`)
        localStorage.setItem(DEVICE_KEY, id)
    }
    return id
}
