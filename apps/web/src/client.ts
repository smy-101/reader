import {createClient, type ApiClient} from '@reader/api-client'
import {loadConnection} from './connection'

/**
 * 全局唯一 client,构建规则(M2 运行时连接设置):
 * - 已配置连接:运行时配置优先(绝对 baseUrl + token)——桌面壳即此形态
 * - 未配置:回退 M1 现状——同源 + 构建期注入 token(VITE_READER_TOKEN,E2E 亦用此注入)
 * 连接设置保存/清除后整页 reload,模块随页面重新初始化,无需运行时热切换。
 */
const connection = loadConnection()

export const api: ApiClient = connection
    ? createClient({baseUrl: connection.baseUrl, token: connection.token})
    : createClient({token: import.meta.env.VITE_READER_TOKEN ?? 'reader-dev-token'})

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
