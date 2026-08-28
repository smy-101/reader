import {useEffect, useRef} from 'react'
import {api} from '../client'
import type {FoliateView, RelocateDetail} from './foliate-types'

/** 上报节流:翻页/滚动稳定后(D-24/D-44)再写一次;间隔内合并。 */
const REPORT_DEBOUNCE_MS = 800

/**
 * 阅读进度自动上报(FR-203,M1-08):relocate 稳定后节流 upsert(CFI + 百分比),
 * 无需手动保存;后端单行覆盖。接续(打开书回放 CFI)由 Reader 在 init 时完成。
 */
export function useAutoReportProgress(view: FoliateView | null, bookId: number, enabled: boolean) {
    const timerRef = useRef<number | undefined>(undefined)
    const lastRef = useRef<{ cfi: string; percent: number } | null>(null)

    useEffect(() => {
        if (!view || !enabled) return

        const onRelocate = (e: Event) => {
            const detail = (e as CustomEvent<RelocateDetail>).detail
            if (!detail?.cfi || detail.fraction == null) return
            const next = {cfi: detail.cfi, percent: Math.round(detail.fraction * 100)}
            // 位置没变(重放/重绘触发的 relocate)不写
            if (lastRef.current?.cfi === next.cfi) return

            window.clearTimeout(timerRef.current)
            timerRef.current = window.setTimeout(() => {
                lastRef.current = next
                api.upsertProgress(bookId, next).catch(() => {
                    // 上报失败不阻断阅读;下次 relocate 会再试(位置变化时)
                })
            }, REPORT_DEBOUNCE_MS)
        }
        view.addEventListener('relocate', onRelocate)
        return () => {
            view.removeEventListener('relocate', onRelocate)
            window.clearTimeout(timerRef.current)
        }
    }, [view, bookId, enabled])
}
