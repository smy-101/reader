import {useState} from 'react'
import {ApiError, createClient} from '@reader/api-client'
import {clearConnection, loadConnection, normalizeBaseUrl, saveConnection} from '../connection'

/**
 * 连接设置(M2):后端绝对地址 + token 的运行时配置,localStorage 持久化。
 * "测试连接"用表单当前值临时建 client 调既有书库列表端点(后端零新增端点),
 * 返回 ok / 可读错误;保存/清除后整页 reload 让 client 重建(见 client.ts 口径)。
 */
export function ConnectionSettings({onClose}: { onClose: () => void }) {
    const saved = loadConnection()
    const [url, setUrl] = useState(saved?.baseUrl ?? '')
    const [token, setToken] = useState(saved?.token ?? '')
    const [testing, setTesting] = useState(false)
    const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null)

    const valid = url.trim() !== '' && token.trim() !== ''

    async function testConnection() {
        if (!valid) return
        setTesting(true)
        setResult(null)
        try {
            // 临时 client:用表单当前值(未保存也可测),真实走一遍鉴权 + 网络路径
            const client = createClient({baseUrl: normalizeBaseUrl(url), token: token.trim()})
            await client.listBooks()
            setResult({ok: true, message: '连接成功'})
        } catch (e) {
            if (e instanceof ApiError) {
                setResult({
                    ok: false,
                    message: e.status === 401
                        ? `认证失败:${e.message}(请检查 token)`
                        : e.status === 0 ? e.message : `请求失败(${e.status}):${e.message}`,
                })
            } else {
                setResult({ok: false, message: e instanceof Error ? e.message : String(e)})
            }
        } finally {
            setTesting(false)
        }
    }

    function save() {
        if (!valid) return
        saveConnection({baseUrl: normalizeBaseUrl(url), token: token.trim()})
        window.location.reload() // client 随页面重建(见 client.ts)
    }

    function reset() {
        clearConnection()
        window.location.reload()
    }

    return (
        <div className="dialog-backdrop" data-testid="connection-dialog">
            <div className="dialog" role="dialog" aria-modal="true" aria-label="连接设置">
                <h2>连接设置</h2>
                <p className="dialog-hint">
                    配置后端地址与 token(只需一次);清除后回退默认:同源 + 构建期注入 token。
                </p>

                <label className="dialog-field">
                    后端地址
                    <input
                        type="text"
                        inputMode="url"
                        placeholder="例如 http://127.0.0.1:8080"
                        value={url}
                        onChange={e => setUrl(e.target.value)}
                        data-testid="connection-url"
                    />
                </label>

                <label className="dialog-field">
                    Token
                    <input
                        type="password"
                        placeholder="后端静态 Bearer token"
                        value={token}
                        onChange={e => setToken(e.target.value)}
                        data-testid="connection-token"
                    />
                </label>

                {result && (
                    <p className={`test-result ${result.ok ? 'ok' : 'error'}`} data-testid="connection-test-result">
                        {result.message}
                    </p>
                )}

                <div className="dialog-actions">
                    <button onClick={() => void testConnection()} disabled={!valid || testing}
                            data-testid="connection-test">
                        {testing ? '测试中…' : '测试连接'}
                    </button>
                    <button className="primary" onClick={save} disabled={!valid} data-testid="connection-save">
                        保存并重载
                    </button>
                    {saved && <button onClick={reset} data-testid="connection-clear">恢复默认</button>}
                    <button onClick={onClose} data-testid="connection-cancel">取消</button>
                </div>
            </div>
        </div>
    )
}
