import {useState} from 'react'
import {Library} from './pages/Library'
import {Reader} from './pages/Reader'

/** 顶层:书库 ↔ 阅读器两个视图(单用户个人应用,不引路由库)。 */
export function App() {
    const [readingBookId, setReadingBookId] = useState<number | null>(null)

    if (readingBookId != null) {
        return <Reader bookId={readingBookId} onExit={() => setReadingBookId(null)}/>
    }
    return <Library onOpen={setReadingBookId}/>
}
