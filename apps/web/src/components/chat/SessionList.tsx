import type {ChatSession} from '@reader/api-client'

/**
 * 会话列表(共用件):最近活跃排序、当前高亮、重命名与删除、空提示。
 * testPrefix 区分两端面板的书级/跨书 testid(书级沿用 ai 前缀,既有 E2E 不变)。
 */
export function SessionList({sessions, activeId, renaming, renameText, onRenameText, onConfirmRename,
                                onStartRename, onCancelRename, onOpen, onRemove, emptyHint, testPrefix}: {
    sessions: ChatSession[]
    activeId: number | null
    renaming: number | null
    renameText: string
    onRenameText: (value: string) => void
    onConfirmRename: (sessionId: number) => void | Promise<void>
    onStartRename: (session: ChatSession) => void
    onCancelRename: () => void
    onOpen: (sessionId: number) => void | Promise<void>
    onRemove: (sessionId: number) => void | Promise<void>
    emptyHint: string
    testPrefix: string
}) {
    return (
        <div className="ai-sessions" data-testid={`${testPrefix}-session-list`}>
            {sessions.map(s => (
                <div key={s.id}
                     className={`ai-session-item ${s.id === activeId ? 'active' : ''}`}
                     data-testid={`${testPrefix}-session-item`}>
                    {renaming === s.id ? (
                        <>
                            <input autoFocus value={renameText}
                                   onChange={e => onRenameText(e.target.value)}
                                   onKeyDown={e => e.key === 'Enter' && void onConfirmRename(s.id)}
                                   data-testid={`${testPrefix}-rename-input`}/>
                            <button onClick={() => void onConfirmRename(s.id)}
                                    data-testid={`${testPrefix}-rename-confirm`}>确定</button>
                            <button onClick={onCancelRename}>取消</button>
                        </>
                    ) : (
                        <>
                            <button className="ai-session-title" onClick={() => void onOpen(s.id)}
                                    data-testid={`${testPrefix}-session-title`}>{s.title}</button>
                            <button onClick={() => onStartRename(s)}
                                    data-testid={`${testPrefix}-rename-button`}>重命名</button>
                            <button onClick={() => void onRemove(s.id)}
                                    data-testid={`${testPrefix}-delete-session`}>删除</button>
                        </>
                    )}
                </div>
            ))}
            {sessions.length === 0 && <p className="hint">{emptyHint}</p>}
        </div>
    )
}
