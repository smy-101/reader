import {useState} from 'react'
import type {PendingCitationJump} from './components/chat/citation-jump'
import {Library} from './pages/Library'
import {Reader} from './pages/Reader'

/** 顶层:书库 ↔ 阅读器两个视图(单用户个人应用,不引路由库)。 */
export function App() {
    const [readingBookId, setReadingBookId] = useState<number | null>(null)
    const [pendingJump, setPendingJump] = useState<PendingCitationJump | null>(null)

    if (readingBookId != null) {
        return <Reader
            bookId={readingBookId}
            pendingJump={pendingJump?.bookId === readingBookId ? pendingJump : null}
            onConsumeJump={() => setPendingJump(null)}
            onExit={() => {
                setReadingBookId(null)
                setPendingJump(null)
            }}/>
    }
    return <Library onOpen={(bookId, citation) => {
        setPendingJump(citation ? {
            bookId,
            chapterId: citation.chapterId,
            excerpt: citation.excerpt,
        } : null)
        setReadingBookId(bookId)
    }}/>
}
