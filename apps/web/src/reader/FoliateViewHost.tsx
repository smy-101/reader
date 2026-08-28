import {useEffect, useRef} from 'react'
import type {FoliateView} from './foliate-types'
import {FOLIATE_VIEW_URL, loadFoliateModule} from './foliate-urls'

/**
 * 承载 foliate-js `<foliate-view>` 的宿主组件(自定义元素,命令式生命周期)。
 * 只负责:创建/挂载/销毁;打开书、事件订阅、交互全部由父组件在 onReady 里完成
 * (view 移除时监听随之释放)。
 */
export function FoliateViewHost({onReady}: { onReady: (view: FoliateView) => void }) {
    const hostRef = useRef<HTMLDivElement>(null)
    const viewRef = useRef<FoliateView | null>(null)

    useEffect(() => {
        const host = hostRef.current
        if (!host) return
        // 由 vendor 模块定义自定义元素(幂等);原生 import 加载 public 静态资源(见 foliate-urls.ts)
        let cancelled = false
        void loadFoliateModule(FOLIATE_VIEW_URL).then(() => {
            if (cancelled || !host.isConnected) return
            const view = document.createElement('foliate-view') as FoliateView
            host.append(view)
            viewRef.current = view
            onReady(view)
        })
        return () => {
            cancelled = true
            viewRef.current?.close?.()
            viewRef.current?.remove()
            viewRef.current = null
        }
        // onReady 必须由父组件 useCallback 稳定;不监听其变化(重挂即重开书)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    return <div className="foliate-host" ref={hostRef} data-testid="reader-content"/>
}
