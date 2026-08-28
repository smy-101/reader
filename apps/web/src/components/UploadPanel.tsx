import {useRef, useState} from 'react'
import {api} from '../client'

export interface UploadResult {
    name: string
    status: 'added' | 'duplicate' | 'error'
    /** duplicate/error 时的可读文案 */
    message?: string
    /** duplicate 时已在库的原书 id(跳转用,D-30) */
    bookId?: number
}

/**
 * 上传面板(FR-101/D-43/D-30):多选后循环调单文件接口,逐本展示结果;
 * 重复书提示"已在书库"并可跳到原书;损坏/DRM/超限透出后端可读文案;单本失败不打断整批。
 */
export function UploadPanel({onDone, onOpenBook}: {
    onDone: (results: UploadResult[]) => void
    onOpenBook: (bookId: number) => void
}) {
    const inputRef = useRef<HTMLInputElement>(null)
    const [results, setResults] = useState<UploadResult[]>([])
    const [uploading, setUploading] = useState(false)

    async function handleFiles(fileList: FileList | null) {
        if (!fileList || fileList.length === 0) return
        setUploading(true)
        try {
            const all: UploadResult[] = []
            for (const file of Array.from(fileList)) {
                // 顺序逐本上传(D-43:前端循环,后端零改动);失败继续下一本
                try {
                    const res = await api.uploadBook(file, file.name)
                    all.push(res.duplicate
                        ? {name: file.name, status: 'duplicate', message: '已在书库', bookId: res.id}
                        : {name: file.name, status: 'added', bookId: res.id})
                } catch (e) {
                    all.push({
                        name: file.name,
                        status: 'error',
                        message: e instanceof Error ? e.message : String(e),
                    })
                }
                setResults([...all]) // 逐本即时展示
            }
            onDone(all)
        } finally {
            setUploading(false)
            if (inputRef.current) inputRef.current.value = '' // 同名文件可重选
        }
    }

    return (
        <div className="upload-panel">
            <input
                ref={inputRef}
                id="upload-input"
                type="file"
                accept=".epub,application/epub+zip"
                multiple
                onChange={e => void handleFiles(e.target.files)}
                data-testid="upload-input"
            />
            <label htmlFor="upload-input" className={`upload-button ${uploading ? 'busy' : ''}`}>
                {uploading ? '上传中…' : '上传 EPUB(可多选)'}
            </label>
            {results.length > 0 && (
                <ul className="upload-results" data-testid="upload-results">
                    {results.map((r, i) => (
                        <li key={`${r.name}-${i}`} className={`upload-result ${r.status}`} data-testid="upload-result">
                            <span className="name">{r.name}</span>
                            <span className="status">
                                {r.status === 'added' && '已入库'}
                                {r.status === 'duplicate' && (r.message ?? '已在书库')}
                                {r.status === 'error' && `失败:${r.message}`}
                            </span>
                            {r.status === 'duplicate' && r.bookId != null && (
                                <button
                                    className="jump"
                                    data-testid="open-existing-book"
                                    onClick={() => onOpenBook(r.bookId!)}
                                >
                                    打开原书
                                </button>
                            )}
                        </li>
                    ))}
                </ul>
            )}
        </div>
    )
}
