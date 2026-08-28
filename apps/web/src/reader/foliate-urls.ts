/**
 * foliate-js vendor 模块加载(vendor 源码无类型,ADR-0004)。
 * <p>
 * vendor 以原生 ESM 静态资源发布在 public/foliate-js/(不经打包器转换:
 * view.js 内对非 EPUB 格式的按需动态 import 在本项目永不触发,若进模块图会被
 * 静态分析报错,见 spike 结论)。Vite 禁止源代码直接 import public 目录文件,
 * 故经 {@code new Function} 走浏览器原生 import——dev 与 build 后行为一致
 * (public 资源原样拷贝到产物根)。接口面统一见 ./foliate-types.ts。
 */

export const FOLIATE_VIEW_URL = '/foliate-js/view.js'
export const FOLIATE_OVERLAYER_URL = '/foliate-js/overlayer.js'

/** 浏览器原生动态 import(绕过打包器静态分析;self-host 应用无 CSP 顾虑)。 */
const nativeImport = new Function('url', 'return import(url)') as <T = unknown>(url: string) => Promise<T>

export function loadFoliateModule<T = unknown>(url: string): Promise<T> {
    return nativeImport<T>(url)
}
