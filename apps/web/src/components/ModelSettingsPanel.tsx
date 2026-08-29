import {useEffect, useState} from 'react'
import {api} from '../client'
import type {ModelSettingsInput, TestConnectionResult} from '@reader/api-client'

/**
 * AI 模型设置(M3-01,FR-401/404/405):5 项 chat 配置 + 2 项 embedding 独立配置。
 * API key 明文回显(FR-404 已接受姿态);bge-m3 仅作 placeholder 不写死(R9-Q5);
 * "测试连接"用表单当前值探测(未保存也可测),chat 与 embedding 各自返回 ok/可读文案。
 * 上下文上限留空 = 8k 保守(D-27);embedding 独立配置留空 = 跟随 chat(D-28)。
 */
export function ModelSettingsPanel({onClose}: { onClose: () => void }) {
    const [baseUrl, setBaseUrl] = useState('')
    const [apiKey, setApiKey] = useState('')
    const [chatModel, setChatModel] = useState('')
    const [contextTokens, setContextTokens] = useState('')
    const [embeddingModel, setEmbeddingModel] = useState('')
    const [embeddingBaseUrl, setEmbeddingBaseUrl] = useState('')
    const [embeddingApiKey, setEmbeddingApiKey] = useState('')
    const [loadError, setLoadError] = useState<string | null>(null)
    const [testing, setTesting] = useState(false)
    const [testResult, setTestResult] = useState<TestConnectionResult | null>(null)
    const [saveError, setSaveError] = useState<string | null>(null)
    const [saved, setSaved] = useState(false)

    useEffect(() => {
        void (async () => {
            try {
                const s = await api.getModelSettings()
                setBaseUrl(s.baseUrl ?? '')
                setApiKey(s.apiKey ?? '')
                setChatModel(s.chatModel ?? '')
                setContextTokens(s.chatContextTokens != null ? String(s.chatContextTokens) : '')
                setEmbeddingModel(s.embeddingModel ?? '')
                setEmbeddingBaseUrl(s.embeddingBaseUrl ?? '')
                setEmbeddingApiKey(s.embeddingApiKey ?? '')
            } catch (e) {
                setLoadError(e instanceof Error ? e.message : String(e))
            }
        })()
    }, [])

    const valid = baseUrl.trim() !== '' && chatModel.trim() !== ''

    function formInput(): ModelSettingsInput {
        const tokens = Number.parseInt(contextTokens.trim(), 10)
        return {
            baseUrl: baseUrl.trim(),
            apiKey: apiKey,
            chatModel: chatModel.trim(),
            chatContextTokens: contextTokens.trim() === '' || Number.isNaN(tokens) ? null : tokens,
            embeddingModel: embeddingModel.trim() === '' ? null : embeddingModel.trim(),
            embeddingBaseUrl: embeddingBaseUrl.trim() === '' ? null : embeddingBaseUrl.trim(),
            embeddingApiKey: embeddingApiKey.trim() === '' ? null : embeddingApiKey.trim(),
        }
    }

    async function testConnection() {
        if (!valid) return
        setTesting(true)
        setTestResult(null)
        try {
            setTestResult(await api.testModelConnection(formInput()))
        } catch (e) {
            setTestResult({
                chat: {ok: false, skipped: false, message: e instanceof Error ? e.message : String(e)},
                embedding: {ok: false, skipped: false, message: ''},
            })
        } finally {
            setTesting(false)
        }
    }

    async function save() {
        if (!valid) return
        setSaveError(null)
        setSaved(false)
        try {
            await api.saveModelSettings(formInput())
            setSaved(true)
        } catch (e) {
            setSaveError(e instanceof Error ? e.message : String(e))
        }
    }

    return (
        <div className="dialog-backdrop" data-testid="model-settings-dialog">
            <div className="dialog model-settings" role="dialog" aria-modal="true" aria-label="AI 模型设置">
                <h2>AI 模型设置</h2>
                <p className="dialog-hint">
                    任意 OpenAI 兼容服务(FR-402):Base URL 应形如 https://api.example.com/v1;
                    上下文上限留空按 8k 保守;embedding 独立配置留空则跟随 chat(可与 chat 各用一家)。
                </p>

                {loadError && <p className="test-result error">{loadError}</p>}

                <label className="dialog-field">
                    Base URL
                    <input type="text" inputMode="url" placeholder="https://api.example.com/v1"
                           value={baseUrl} onChange={e => setBaseUrl(e.target.value)}
                           data-testid="model-base-url"/>
                </label>
                <label className="dialog-field">
                    API key(明文保存与回显)
                    <input type="text" placeholder="sk-…(本地服务可留空)"
                           value={apiKey} onChange={e => setApiKey(e.target.value)}
                           data-testid="model-api-key"/>
                </label>
                <label className="dialog-field">
                    Chat 模型
                    <input type="text" placeholder="如 deepseek-chat"
                           value={chatModel} onChange={e => setChatModel(e.target.value)}
                           data-testid="model-chat-model"/>
                </label>
                <label className="dialog-field">
                    上下文上限(留空 = 8k 保守)
                    <input type="number" min={1} placeholder="8192"
                           value={contextTokens} onChange={e => setContextTokens(e.target.value)}
                           data-testid="model-context-tokens"/>
                </label>
                <label className="dialog-field">
                    Embedding 模型(可选;未配置则 AI 检索相关功能隐藏)
                    <input type="text" placeholder="bge-m3"
                           value={embeddingModel} onChange={e => setEmbeddingModel(e.target.value)}
                           data-testid="model-embedding-model"/>
                </label>
                <label className="dialog-field">
                    Embedding Base URL(可选,留空跟随 chat)
                    <input type="text" inputMode="url" placeholder="https://api.example.com/v1"
                           value={embeddingBaseUrl} onChange={e => setEmbeddingBaseUrl(e.target.value)}
                           data-testid="model-embedding-base-url"/>
                </label>
                <label className="dialog-field">
                    Embedding API key(可选,留空跟随 chat)
                    <input type="text" placeholder="sk-…"
                           value={embeddingApiKey} onChange={e => setEmbeddingApiKey(e.target.value)}
                           data-testid="model-embedding-api-key"/>
                </label>

                {testResult && (
                    <div className="probe-results" data-testid="model-test-results">
                        <p className={`test-result ${testResult.chat.ok ? 'ok' : 'error'}`}
                           data-testid="model-test-chat-result">
                            Chat:{testResult.chat.message}
                        </p>
                        <p className={`test-result ${testResult.embedding.skipped ? '' : testResult.embedding.ok ? 'ok' : 'error'}`}
                           data-testid="model-test-embedding-result">
                            Embedding:{testResult.embedding.message}
                        </p>
                    </div>
                )}
                {saveError && <p className="test-result error" data-testid="model-save-error">{saveError}</p>}
                {saved && <p className="test-result ok" data-testid="model-saved-hint">已保存</p>}

                <div className="dialog-actions">
                    <button onClick={() => void testConnection()} disabled={!valid || testing}
                            data-testid="model-settings-test">
                        {testing ? '测试中…' : '测试连接'}
                    </button>
                    <button className="primary" onClick={() => void save()} disabled={!valid}
                            data-testid="model-settings-save">
                        保存
                    </button>
                    <button onClick={onClose} data-testid="model-settings-close">关闭</button>
                </div>
            </div>
        </div>
    )
}
