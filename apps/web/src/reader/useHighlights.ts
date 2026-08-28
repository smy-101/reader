import {useCallback, useEffect, useRef, useState} from 'react'
import type {Highlight} from '@reader/api-client'
import {api, deviceId} from '../client'
import type {FoliateView} from './foliate-types'
import {FOLIATE_OVERLAYER_URL, loadFoliateModule} from './foliate-urls'

/** 选中的一段正文(view 侧取出 CFI 与文字快照)。 */
export interface PendingSelection {
    cfi: string
    text: string
}

type OverlayerModule = { Overlayer: { highlight: unknown } }

/**
 * 划线域(M1-07,FR-202/D-24):打开书全量拉取并渲染;创建/改色/改备注/删除即时同步;
 * 高亮绘制走 foliate-js overlayer(draw-annotation 事件)。
 */
export function useHighlights(view: FoliateView | null, bookId: number, onOpen: boolean) {
    const [highlights, setHighlights] = useState<Highlight[]>([])
    const [selection, setSelection] = useState<PendingSelection | null>(null)
    const [editing, setEditing] = useState<Highlight | null>(null)
    const byCfiRef = useRef(new Map<string, Highlight>())
    const overlayerRef = useRef<OverlayerModule | null>(null)

    // Overlayer 绘制函数(懒加载 vendor 模块一次)
    useEffect(() => {
        if (!view) return
        if (!overlayerRef.current) {
            void loadFoliateModule<OverlayerModule>(FOLIATE_OVERLAYER_URL).then(m => {
                overlayerRef.current = m
            })
        }
    }, [view])

    // 打开书:全量拉取划线(D-24),逐条画上;并挂选中监听
    useEffect(() => {
        if (!view || !onOpen) return
        let alive = true
        byCfiRef.current = new Map()
        setHighlights([])
        api.listHighlights(bookId).then(list => {
            if (!alive) return
            setHighlights(list)
            for (const h of list) {
                byCfiRef.current.set(h.cfi, h)
                void view.addAnnotation({value: h.cfi, color: h.color ?? undefined})
            }
        }).catch(() => {
            // 拉取失败不阻断阅读;列表为空,创建仍可用
        })
        return () => {
            alive = false
        }
    }, [view, bookId, onOpen])

    // view 事件:绘制样式 + 点击已有高亮 + 选中监听(只依赖 view:load 在 open() 期间就会触发,
    // 等到 ready 再挂会错过首批内容 doc)
    useEffect(() => {
        if (!view) return

        const onDraw = (e: Event) => {
            const {draw, annotation} = (e as CustomEvent<{ draw: (fn: unknown, opts: { color: string }) => void; annotation: { color?: string | null } }>).detail
            draw(overlayerRef.current?.Overlayer.highlight ?? {}, {color: annotation.color ?? 'yellow'})
        }
        const onShow = (e: Event) => {
            const {value} = (e as CustomEvent<{ value: string }>).detail
            const hit = byCfiRef.current.get(value)
            if (hit) setEditing(hit)
        }
        // 每个 load 的内容 doc 挂 selectionchange:选中即得 CFI(硬点 b,spike 已验证)
        const onLoad = (e: Event) => {
            const {doc} = (e as CustomEvent<{ doc: Document; index: number }>).detail
            doc.addEventListener('selectionchange', () => {
                const sel = doc.getSelection()
                if (!sel || sel.isCollapsed || sel.rangeCount === 0) return
                const range = sel.getRangeAt(0)
                const contents = view.renderer?.getContents?.() ?? []
                const hit = contents.find(c => c.doc === range.startContainer.ownerDocument)
                if (!hit) return
                try {
                    const text = sel.toString().trim()
                    if (!text) return
                    setSelection({cfi: view.getCFI(hit.index, range), text})
                } catch {
                    // 取 CFI 失败(如选中了不可定位节点):忽略本次
                }
            })
        }
        view.addEventListener('draw-annotation', onDraw)
        view.addEventListener('show-annotation', onShow)
        view.addEventListener('load', onLoad)
        return () => {
            view.removeEventListener('draw-annotation', onDraw)
            view.removeEventListener('show-annotation', onShow)
            view.removeEventListener('load', onLoad)
        }
    }, [view])

    const refreshMap = useCallback((list: Highlight[]) => {
        byCfiRef.current = new Map(list.map(h => [h.cfi, h]))
    }, [])

    /** 创建划线:CFI + 文字快照 + 颜色/备注 + 设备标识(FR-202)。 */
    const create = useCallback(async (input: PendingSelection & { color?: string; note?: string }) => {
        const created = await api.createHighlight(bookId, {
            cfi: input.cfi,
            text: input.text,
            color: input.color,
            note: input.note,
            device: deviceId(),
        })
        setHighlights(list => {
            const next = [...list, created]
            refreshMap(next)
            return next
        })
        void view?.addAnnotation({value: created.cfi, color: created.color ?? undefined})
        return created
    }, [bookId, view, refreshMap])

    /** 单条更新(颜色/备注):服务端 LWW 后写胜,端上重画。 */
    const update = useCallback(async (id: number, patch: { color?: string; note?: string }) => {
        const updated = await api.updateHighlight(id, patch)
        setHighlights(list => {
            const next = list.map(h => h.id === id ? updated : h)
            refreshMap(next)
            return next
        })
        if (view) {
            await view.deleteAnnotation({value: updated.cfi})
            await view.addAnnotation({value: updated.cfi, color: updated.color ?? undefined})
        }
        setEditing(updated)
        return updated
    }, [view, refreshMap])

    /** 单条删除:服务端删 + 端上去画。 */
    const remove = useCallback(async (id: number) => {
        const hit = highlights.find(h => h.id === id)
        await api.deleteHighlight(id)
        setHighlights(list => {
            const next = list.filter(h => h.id !== id)
            refreshMap(next)
            return next
        })
        if (hit && view) await view.deleteAnnotation({value: hit.cfi})
        setEditing(null)
    }, [highlights, view, refreshMap])

    return {
        highlights,
        selection,
        setSelection,
        editing,
        setEditing,
        create,
        update,
        remove,
    }
}
