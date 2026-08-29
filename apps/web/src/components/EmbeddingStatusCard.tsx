import {useEffect, useRef, useState} from 'react'
import type {EmbeddingStatus} from '@reader/api-client'
import {api} from '../client'

/**
 * 嵌入状态卡(M4-04):进行中显示进度(已完成块数/总块数)并轮询;失败显示可读错误与
 * 「重试」;完成显示所用模型与「重新嵌入」;未嵌入(存量书)显示「嵌入」——
 * 四态同一触发端点(多态一语义:首次嵌入 / 失败重试 / 换模型全量重嵌入)。
 * 完成但模型已换(US 13):明示「embedding 模型已更换,需重新嵌入」,S3 入口在
 * 重嵌入完成前隐藏(AiPanel 侧同一裁决)。未配置 embedding 时由父级整体隐藏(FR-403)。
 */

/** 轮询间隔:进行中(pending/running)每 1.5s 拉一次进度;拉取失败 5s 后重试。 */
const POLL_INTERVAL_MS = 1500
const RETRY_INTERVAL_MS = 5000

export function EmbeddingStatusCard({bookId, embeddingModel}: {
    bookId: number
    /** 当前配置的 embedding 模型(父级从模型设置拉取);与任务模型比对裁决“模型已更换” */
    embeddingModel: string | null
}) {
    const [status, setStatus] = useState<EmbeddingStatus | null>(null)
    const [actionError, setActionError] = useState<string | null>(null)
    const [busy, setBusy] = useState(false)
    /** 触发后自增:重启轮询循环(进入 running 直至终态) */
    const [pollNonce, setPollNonce] = useState(0)
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    useEffect(() => {
        let cancelled = false
        const tick = async () => {
            try {
                const s = await api.getEmbeddingStatus(bookId)
                if (cancelled) return
                setStatus(s)
                setActionError(null)
                if (s.status === 'pending' || s.status === 'running') {
                    timerRef.current = setTimeout(() => void tick(), POLL_INTERVAL_MS)
                }
            } catch (e) {
                if (cancelled) return
                setActionError(e instanceof Error ? e.message : String(e))
                timerRef.current = setTimeout(() => void tick(), RETRY_INTERVAL_MS) // 拉取失败不永久停摆
            }
        }
        void tick()
        return () => {
            cancelled = true
            if (timerRef.current != null) clearTimeout(timerRef.current)
        }
    }, [bookId, pollNonce])

    async function trigger() {
        setBusy(true)
        setActionError(null)
        try {
            setStatus(await api.triggerEmbedding(bookId))
            setPollNonce(n => n + 1) // 重启轮询(可能已进入 running)
        } catch (e) {
            setActionError(e instanceof Error ? e.message : String(e))
        } finally {
            setBusy(false)
        }
    }

    /** done 但模型已换:需全量重嵌入,否则 S3/检索式降级不可用(US 13) */
    const modelChanged = status?.status === 'done'
        && embeddingModel != null
        && status.model !== embeddingModel

    const s = status
    return (
        <div className="embedding-card" data-testid="embedding-card" data-book-id={bookId}>
            {s == null && <span className="embedding-status" data-testid="embedding-status">…</span>}
            {s?.status === 'none' && (
                <>
                    <span className="embedding-status" data-testid="embedding-status">未嵌入</span>
                    <button className="embedding-action" onClick={() => void trigger()} disabled={busy}
                            data-testid="embedding-trigger">嵌入
                    </button>
                </>
            )}
            {s?.status === 'pending' && (
                <span className="embedding-status" data-testid="embedding-status">排队嵌入中…</span>
            )}
            {s?.status === 'running' && (
                <>
                    <span className="embedding-status" data-testid="embedding-status">
                        嵌入中 {s.chunkDone ?? 0}/{s.chunkTotal ?? '?'} 块
                    </span>
                    <div className="embedding-progress-track" data-testid="embedding-progress">
                        <div className="embedding-progress-fill"
                             style={{width: progressPercent(s) + '%'}}/>
                    </div>
                </>
            )}
            {s?.status === 'done' && (
                <>
                    <span className={`embedding-status ${modelChanged ? 'error' : 'ok'}`}
                          data-testid="embedding-status"
                          data-model={s.model ?? ''}>
                        {modelChanged
                            ? `embedding 模型已更换(${s.model} → ${embeddingModel}),需重新嵌入`
                            : `已嵌入 · ${s.model}`}
                    </span>
                    <button className="embedding-action" onClick={() => void trigger()} disabled={busy}
                            data-testid="embedding-trigger">重新嵌入
                    </button>
                </>
            )}
            {s?.status === 'failed' && (
                <>
                    <span className="embedding-status error" data-testid="embedding-status"
                          title={s.error ?? undefined}>嵌入失败:{s.error}</span>
                    <button className="embedding-action" onClick={() => void trigger()} disabled={busy}
                            data-testid="embedding-trigger">重试
                    </button>
                </>
            )}
            {actionError && <span className="embedding-status error" data-testid="embedding-action-error">{actionError}</span>}
        </div>
    )
}

function progressPercent(s: EmbeddingStatus): number {
    if (s.chunkTotal == null || s.chunkTotal <= 0) return 0
    return Math.min(100, Math.round(((s.chunkDone ?? 0) / s.chunkTotal) * 100))
}
