/** 阅读器页(M1-05 落地:渲染/目录/设置;M1-07 划线;M1-08 进度)。 */
export function Reader({bookId, onExit}: { bookId: number; onExit: () => void }) {
    return (
        <main className="reader">
            <header className="reader-header">
                <button onClick={onExit} data-testid="back-to-library">← 书库</button>
                <h1>阅读器</h1>
            </header>
            <p className="hint">书籍 #{bookId}:阅读器主链路在 M1-05 交付。</p>
        </main>
    )
}
