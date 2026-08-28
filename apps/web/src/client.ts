import {createClient, type ApiClient} from '@reader/api-client'

/**
 * 全局唯一 client:token 经环境注入(VITE_READER_TOKEN,构建期可配,E2E 亦用此注入)。
 * 端上设备标识:本地生成持久化(Crypto UUID),随划线上报,仅展示/追溯用。
 */
const token = import.meta.env.VITE_READER_TOKEN ?? 'reader-dev-token'

export const api: ApiClient = createClient({token})

const DEVICE_KEY = 'reader-device-id'

export function deviceId(): string {
    let id = localStorage.getItem(DEVICE_KEY)
    if (!id) {
        id = (crypto.randomUUID?.() ?? `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`)
        localStorage.setItem(DEVICE_KEY, id)
    }
    return id
}
