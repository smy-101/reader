import {useEffect, useState} from 'react'
import type {BookListItem} from '@reader/api-client'
import {api} from '../client'

/** 书籍卡片:封面(带 token 程序化拉取)、标题、作者、进度百分比。 */
export function BookCard({book, onOpen}: { book: BookListItem; onOpen: () => void }) {
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
    )
}
