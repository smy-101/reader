import {useState} from 'react'
import type {BookListItem} from '@reader/api-client'
import {api} from '../client'

/**
 * 删书确认弹窗(FR-104):明示级联范围——书源文件与封面、划线、进度、该书全部会话;
 * 确认后调 DELETE,列表由父级刷新。向量清理是 M4 在同一删除流程上的增量,不在本期文案。
 */
export function DeleteBookDialog({book, onDone, onCancel}: {
    book: BookListItem
    onDone: () => void
    onCancel: () => void
}) {
    const [deleting, setDeleting] = useState(false)
    const [error, setError] = useState<string | null>(null)

    async function confirm() {
        setDeleting(true)
        setError(null)
        try {
            await api.deleteBook(book.id)
            onDone()
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e))
            setDeleting(false)
        }
    }

    return (
        <div className="dialog-backdrop" data-testid="delete-book-dialog">
            <div className="dialog" role="alertdialog" aria-modal="true" aria-label="删除书籍">
                <h2>删除「{book.title}」?</h2>
                <p className="dialog-hint">此操作不可撤销,以下内容将被一并清除:</p>
                <ul className="delete-scope" data-testid="delete-book-scope">
                    <li>书源文件与封面</li>
                    <li>全部划线</li>
                    <li>阅读进度</li>
                    <li>这本书的全部 AI 会话</li>
                </ul>
                {error && <p className="test-result error" data-testid="delete-book-error">{error}</p>}
                <div className="dialog-actions">
                    <button className="danger" onClick={() => void confirm()} disabled={deleting}
                            data-testid="delete-book-confirm">
                        {deleting ? '删除中…' : '确认删除'}
                    </button>
                    <button onClick={onCancel} disabled={deleting} data-testid="delete-book-cancel">取消</button>
                </div>
            </div>
        </div>
    )
}
