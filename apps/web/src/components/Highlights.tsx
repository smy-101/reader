import {useEffect, useState} from 'react'
import type {Highlight} from '@reader/api-client'
import type {PendingSelection} from '../reader/useHighlights'
import {HIGHLIGHT_COLORS, colorHex, snippet} from '../reader/highlight-colors'

/**
 * 划线操作条(阅读器底部固定条):
 * - 有选中文本时:选色(可选)→ 划线,或取消
 * - 正在编辑已有划线时:改色 / 改备注 / 删除 / 跳转
 */
export function HighlightBar({
    selection,
    editing,
    onCreate,
    onUpdate,
    onDelete,
    onJump,
    onClose,
}: {
    selection: PendingSelection | null
    editing: Highlight | null
    onCreate: (input: PendingSelection & { color?: string; note?: string }) => Promise<unknown>
    onUpdate: (id: number, patch: { color?: string; note?: string }) => Promise<unknown>
    onDelete: (id: number) => Promise<unknown>
    onJump: (h: Highlight) => void
    onClose: () => void
}) {
    const [color, setColor] = useState<string>('yellow')
    const [note, setNote] = useState('')
    const [editNote, setEditNote] = useState('')
    const [busy, setBusy] = useState(false)

    // hooks 必须在早退之前无条件调用;编辑对象切换时重置备注草稿
    useEffect(() => {
        setEditNote(editing?.note ?? '')
    }, [editing?.id])

    if (!selection && !editing) return null

    async function guard<T>(fn: () => Promise<T>): Promise<void> {
        setBusy(true)
        try {
            await fn()
        } finally {
            setBusy(false)
        }
    }

    return (
        <div className="highlight-bar" data-testid="highlight-bar">
            {selection && !editing && (
                <>
                    <span className="bar-title" data-testid="selection-text">{snippet(selection.text, 40)}</span>
                    <Swatches
                        selected={color}
                        onSelect={setColor}
                        testPrefix="color"
                    />
                    <input
                        className="note-input"
                        type="text"
                        placeholder="备注(可选)"
                        value={note}
                        onChange={e => setNote(e.target.value)}
                        data-testid="note-input"
                    />
                    <button
                        className="primary"
                        disabled={busy}
                        data-testid="create-highlight"
                        onClick={() => void guard(async () => {
                            await onCreate({...selection, color, note: note.trim() || undefined})
                            setNote('')
                            onClose()
                        })}
                    >
                        划线
                    </button>
                    <button data-testid="cancel-selection" onClick={onClose}>取消</button>
                </>
            )}
            {editing && (
                <>
                    <span className="bar-title" data-testid="editing-text">{snippet(editing.text, 40)}</span>
                    <Swatches
                        selected={editing.color ?? 'yellow'}
                        onSelect={c => void guard(() => onUpdate(editing.id, {color: c}))}
                        testPrefix="edit-color"
                    />
                    <input
                        className="note-input"
                        type="text"
                        placeholder="备注"
                        value={editNote}
                        onChange={e => setEditNote(e.target.value)}
                        onKeyDown={e => e.key === 'Enter'
                            && void guard(() => onUpdate(editing.id, {note: editNote.trim()}))}
                        data-testid="edit-note-input"
                    />
                    {/* 空串=清空备注(后端空串照存,null 才是"保持不变") */}
                    <button
                        disabled={busy}
                        data-testid="save-note"
                        onClick={() => void guard(() => onUpdate(editing.id, {note: editNote.trim()}))}
                    >
                        存备注
                    </button>
                    <button data-testid="jump-highlight" onClick={() => onJump(editing)}>跳转</button>
                    <button
                        className="danger"
                        disabled={busy}
                        data-testid="delete-highlight"
                        onClick={() => void guard(() => onDelete(editing.id))}
                    >
                        删除
                    </button>
                    <button data-testid="close-editor" onClick={onClose}>关闭</button>
                </>
            )}
        </div>
    )
}

/** 颜色选择圆点(创建/编辑共用形态)。 */
function Swatches({selected, onSelect, testPrefix}: {
    selected: string
    onSelect: (color: string) => void
    testPrefix: string
}) {
    return (
        <span className="swatches" role="radiogroup" aria-label="划线颜色">
            {HIGHLIGHT_COLORS.map(c => (
                <button
                    key={c.id}
                    role="radio"
                    aria-checked={selected === c.id}
                    className={`swatch ${selected === c.id ? 'active' : ''}`}
                    style={{background: c.hex}}
                    title={c.label}
                    data-testid={`${testPrefix}-${c.id}`}
                    onClick={() => onSelect(c.id)}
                />
            ))}
        </span>
    )
}

/** 书内划线列表(浏览与管理该书全部划线)。 */
export function HighlightListPanel({highlights, onJump, onEdit}: {
    highlights: Highlight[]
    onJump: (h: Highlight) => void
    onEdit: (h: Highlight) => void
}) {
    return (
        <div className="highlight-panel" data-testid="highlight-panel">
            <h2>划线({highlights.length})</h2>
            {highlights.length === 0 && <p className="hint">本书还没有划线;在正文选中文字即可创建。</p>}
            <ul className="highlight-list">
                {highlights.map(h => (
                    <li key={h.id} className="highlight-item" data-testid="highlight-item">
                        <span className="dot" style={{background: colorHex(h.color)}}/>
                        <button className="item-main" onClick={() => onJump(h)} data-testid="highlight-item-text">
                            <span className="item-text">{snippet(h.text)}</span>
                            {h.note && <span className="item-note">备注:{h.note}</span>}
                        </button>
                        <button className="item-edit" onClick={() => onEdit(h)} data-testid="highlight-item-edit">编辑</button>
                    </li>
                ))}
            </ul>
        </div>
    )
}
