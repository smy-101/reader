import type {ReactNode} from 'react'

/**
 * 提问输入行(M4-05 自 AiPanel 抽取):Enter 发送(IME 组词不触发)、
 * 流式期间禁用。书级与跨书面板共用;书级专属控件(定位原文开关、选中引文)经 children 前置。
 */
export function ChatComposer({value, onChange, onSend, streaming, placeholder, testPrefix = 'ai', children}: {
    value: string
    onChange: (value: string) => void
    onSend: () => void | Promise<void>
    streaming: boolean
    placeholder: string
    /** testid 前缀:书级面板沿用 ai(既有 E2E),跨书面板用 global-ai */
    testPrefix?: string
    children?: ReactNode
}) {
    return (
        <div className="ai-input-row">
            {children}
            <textarea
                value={value}
                onChange={e => onChange(e.target.value)}
                onKeyDown={e => {
                    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                        e.preventDefault()
                        void onSend()
                    }
                }}
                placeholder={placeholder}
                data-testid={`${testPrefix}-input`}
                rows={2}
                disabled={streaming}
            />
            <button className="primary" onClick={() => void onSend()} disabled={streaming || !value.trim()}
                    data-testid={`${testPrefix}-send`}>
                {streaming ? '回复中…' : '发送'}
            </button>
        </div>
    )
}
