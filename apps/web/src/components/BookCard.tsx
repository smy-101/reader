import {useEffect, useState} from 'react'
import type {BookListItem} from '@reader/api-client'
import {api} from '../client'
import {EmbeddingStatusCard} from './EmbeddingStatusCard'

/** 书籍卡片:封面(带 token 程序化拉取)、标题、作者、进度百分比;右上角删书入口;
 * embedding 已配置时附带嵌入状态卡(M4-04;未配置时隐藏,FR-403)。 */
export function BookCard({book, onOpen, onDelete, showEmbedding}: {
    book: BookListItem
    onOpen: () => void
    onDelete: () => void
    showEmbedding?: boolean
}) {
    const [coverUrl, setCoverUrl] = useState<string | null>(null)

    useEffect(() => {
        let revoked = false
        let objectUrl: string | null = null
        if (book.coverUrl) {
            api.fetchCover(book.coverUrl)
                .then(blob => {
                    if (revoked) return
                    objectUrl = URL.createObjectURL(blob)
                    setCoverUrl(objectUrl)
                })
                .catch(() => setCoverUrl(null)) // 封面拉不到就显示占位,不阻断书库
        }
        return () => {
            revoked = true
            if (objectUrl) URL.revokeObjectURL(objectUrl)
        }
    }, [book.coverUrl])

    return (
        <div className="book-card-wrap">
            <button
                className="book-card"
                onClick={onOpen}
                data-testid="book-card"
                data-book-id={book.id}
            >
                <div className="book-cover">
                    {coverUrl
                        ? <img src={coverUrl} alt={`${book.title} 封面`}/>
                        : <span className="cover-placeholder">无封面</span>}
                </div>
                <div className="book-meta">
                    <div className="book-title" data-testid="book-title">{book.title}</div>
                    <div className="book-author">{book.author || '佚名'}</div>
                    <div className="book-progress" data-testid="book-progress">
                        {book.progressPercent == null ? '未读' : `已读 ${book.progressPercent}%`}
                    </div>
                </div>
            </button>
            <button
                className="book-delete"
                title="删除本书"
                onClick={onDelete}
                data-testid="delete-book-button"
            >
                删除
            </button>
            {showEmbedding && <EmbeddingStatusCard bookId={book.id}/>}
        </div>
    )
}
