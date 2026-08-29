import {createServer, type IncomingMessage, type Server, type ServerResponse} from 'node:http'

/**
 * E2E 本地流式 stub LLM(M3 spec · Seam B):OpenAI 兼容替身,网络层。
 * - GET {base}/models → 200(设置页测试连接用)
 * - POST {base}/chat/completions → 多块 SSE(400ms 间隔,逼出逐块流式渲染)→ [DONE]
 * - GET /_last → 最近一次 chat/completions 请求体(prompt 形状断言:目标章进 prompt)
 * - GET /_reset → 清空记录
 * 回复固定三段:E2E_STUB_REPLY = '流式回复' + '的第' + '三段内容'。
 */
export const E2E_STUB_PORT = 18081
export const E2E_STUB_BASE_URL = `http://127.0.0.1:${E2E_STUB_PORT}/v1`
export const E2E_STUB_REPLY = ['流式回复', '的第', '三段内容']
export const E2E_STUB_REPLY_FULL = E2E_STUB_REPLY.join('')

const CHUNK_GAP_MS = 400

let server: Server | null = null
let lastChatRequest: { headers: IncomingMessage['headers']; body: string } | null = null

/** 经 HTTP 访问 stub 的记录(Playwright globalSetup 与测试文件是独立模块图,
 *  不能直接读模块内状态;一律走 HTTP 才能拿到服务进程里的真状态)。 */
async function fetchLast(): Promise<string | null> {
    const res = await fetch(`http://127.0.0.1:${E2E_STUB_PORT}/_last`)
    if (!res.ok) throw new Error(`stub /_last 响应 ${res.status}`)
    return ((await res.json()) as { body: string | null }).body
}

/** 清空 stub 记录(跨进程安全)。 */
export async function stubLlmReset(): Promise<void> {
    await fetch(`http://127.0.0.1:${E2E_STUB_PORT}/_reset`, {method: 'POST'})
}

/** 最近一次 chat/completions 请求体(prompt 形状断言;未发过为 null)。 */
export async function stubLastRequestBody(): Promise<string | null> {
    return fetchLast()
}

export const stubLlm = {
    start(): Promise<void> {
        if (server) return Promise.resolve()
        server = createServer((req, res) => void handle(req, res))
        return new Promise(resolve => {
            server!.listen(E2E_STUB_PORT, '127.0.0.1', resolve)
        })
    },

    stop(): Promise<void> {
        return new Promise(resolve => {
            if (!server) return resolve()
            server.close(() => {
                server = null
                resolve()
            })
        })
    },
}

async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = req.url ?? ''
    try {
        if (req.method === 'GET' && url === '/v1/models') {
            res.writeHead(200, {'Content-Type': 'application/json'})
            res.end(JSON.stringify({object: 'list', data: [{id: 'stub-chat'}, {id: 'bge-m3'}]}))
            return
        }
        if (req.method === 'POST' && url === '/v1/chat/completions') {
            const body = await readBody(req)
            lastChatRequest = {headers: req.headers, body}
            res.writeHead(200, {
                'Content-Type': 'text/event-stream',
                'Cache-Control': 'no-cache',
                Connection: 'keep-alive',
            })
            for (const chunk of E2E_STUB_REPLY) {
                res.write(`data: ${JSON.stringify({choices: [{delta: {content: chunk}}]})}\n\n`)
                await sleep(CHUNK_GAP_MS)
            }
            res.write('data: [DONE]\n\n')
            res.end()
            return
        }
        if (req.method === 'GET' && url === '/_last') {
            res.writeHead(200, {'Content-Type': 'application/json'})
            res.end(JSON.stringify({body: lastChatRequest?.body ?? null}))
            return
        }
        if (req.method === 'GET' && url === '/_reset' || req.method === 'POST' && url === '/_reset') {
            lastChatRequest = null
            res.writeHead(204)
            res.end()
            return
        }
        res.writeHead(404, {'Content-Type': 'application/json'})
        res.end(JSON.stringify({error: {message: `Unknown path ${url}`}}))
    } catch (e) {
        console.error('[stub-llm] 处理异常:', e)
        try {
            res.writeHead(500)
            res.end()
        } catch {
            // 连接已断
        }
    }
}

function readBody(req: IncomingMessage): Promise<string> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = []
        req.on('data', (c: Buffer) => chunks.push(c))
        req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
        req.on('error', reject)
    })
}

function sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms))
}
