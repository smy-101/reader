/**
 * @reader/api-client:后端 HTTP 契约的单一出处。
 * <p>
 * - token 经 createClient 注入,一切请求统一带 Bearer;不以 URL 查询参数传 token。
 * - 错误统一抛 ApiError(后端契约:{"error": 可读文案}),供 UI 直接展示。
 * - 无独立单测:契约由后端 Seam A 集成测试与 apps/web E2E 覆盖(M1 spec · Testing Decisions)。
 */

// ---- 共享类型(与后端 DTO 一一对应) ----

export interface BookListItem {
    id: number;
    title: string;
    author: string | null;
    coverUrl: string | null;
    /** 0-100;无进度为 null */
    progressPercent: number | null;
    /** 嵌入就绪摘要(S4,US 25):最新任务状态 + 是否就绪(与嵌入状态卡同源消费) */
    embedding?: EmbeddingSummary | null;
}

/** 书库列表项的嵌入就绪摘要:ready = done 且模型与当前配置一致(S3/S4 同一裁决口径)。 */
export interface EmbeddingSummary {
    status: 'none' | 'pending' | 'running' | 'done' | 'failed';
    model: string | null;
    ready: boolean;
}

export interface ChapterSummary {
    id: number;
    seq: number;
    title: string | null;
    href: string;
    textLength: number;
}

export interface BookDetail {
    id: number;
    title: string;
    author: string | null;
    language: string | null;
    coverUrl: string | null;
    fileHash: string;
    fileSize: number;
    chapterCount: number;
}

export interface UploadBookResponse {
    id: number;
    title: string;
    author: string | null;
    language: string | null;
    coverUrl: string | null;
    fileHash: string;
    fileSize: number;
    /** 同 file_hash 已在书库(幂等返回,D-30),UI 提示"已在书库" */
    duplicate: boolean;
    chapters: ChapterSummary[];
}

export interface Highlight {
    id: number;
    bookId: number;
    cfi: string;
    /** 选中文字快照 */
    text: string;
    note: string | null;
    color: string | null;
    device: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface Progress {
    bookId: number;
    cfi: string;
    percent: number;
    updatedAt: string;
}

export interface CreateHighlightInput {
    cfi: string;
    text: string;
    note?: string | null;
    color?: string | null;
    device?: string | null;
}

export interface UpdateHighlightInput {
    color?: string;
    note?: string;
}

export interface UpsertProgressInput {
    cfi: string;
    /** 0-100 整数 */
    percent: number;
}

// ---- 模型设置(M3,FR-401:5+2 项) ----

/** 单套配置(id 恒 1);api key 明文回显(FR-404 已接受姿态)。
 * 可空项语义:上下文上限空 = 按 8k 保守(D-27);embedding 独立配置空 = 跟随 chat(D-28)。 */
export interface ModelSettings {
    id: number;
    baseUrl: string | null;
    apiKey: string | null;
    chatModel: string | null;
    chatContextTokens: number | null;
    embeddingModel: string | null;
    embeddingBaseUrl: string | null;
    embeddingApiKey: string | null;
    updatedAt: string | null;
}

export interface ModelSettingsInput {
    baseUrl: string;
    apiKey?: string | null;
    chatModel: string;
    chatContextTokens?: number | null;
    embeddingModel?: string | null;
    embeddingBaseUrl?: string | null;
    embeddingApiKey?: string | null;
}

/** 测试连接单探针结果(FR-405);skipped 仅 embedding 未配置时出现 */
export interface ProbeOutcome {
    ok: boolean;
    skipped: boolean;
    message: string;
}

export interface TestConnectionResult {
    chat: ProbeOutcome;
    embedding: ProbeOutcome;
}

// ---- AI 对话(M3,FR-301/303/304) ----

export interface ChatSession {
    id: number;
    bookId: number | null;
    title: string;
    createdAt: string;
    updatedAt: string;
}

/** 引用来源(refs);首版三种:选中文字、章节与检索块(M4);S4 检索块另携书籍身份。 */
export interface ChatRef {
    type: 'selection' | 'chapter' | 'retrieval';
    text?: string;
    cfi?: string;
    chapterId?: number;
    chapterTitle?: string | null;
    seq?: number;
    /** retrieval 专用:章序与原文摘录 */
    chapterSeq?: number;
    excerpt?: string;
    chunkSeq?: number;
    /** S4 跨书检索块专用:书标识与书名快照(落库时定格,D-33 删书后靠快照降级占位) */
    bookId?: number;
    bookTitle?: string | null;
}

export interface ChatMessage {
    id: number;
    sessionId: number;
    role: 'user' | 'assistant';
    content: string;
    refs: ChatRef[] | null;
    createdAt: string;
}

/** 书级提问(S1 与 S2 同一通路,D-32:带 selection 即 S1;retrieval=true 即 S3,M4)。 */
export interface AskInput {
    content: string;
    sessionId?: number | null;
    chapterId?: number | null;
    cfi?: string | null;
    selection?: { text: string; cfi?: string | null } | null;
    /** S3 定位原文:显式检索式提问(前置不满足时 4xx 可读文案) */
    retrieval?: boolean;
}

/** 跨书提问(S4,D-36):无书 id、无 selection、无检索标志——跨书提问恒为检索式。 */
export interface GlobalAskInput {
    content: string;
    sessionId?: number | null;
}

export interface AskMeta {
    sessionId: number;
    sessionTitle: string;
    userMessageId: number;
    /** S3:检索引用随开场元数据事件下发(流式开始前即可渲染) */
    citations?: Citation[] | null;
}

/**
 * 检索引用(章节标识 + 标题 + 原文摘录;S3 跳转用)。S4 跨书时另携书籍身份:
 * bookId + 书名快照(书删除后前端据书库列表降级为"原书已删除"占位,D-33)。
 */
export interface Citation {
    chapterId: number;
    chapterTitle: string | null;
    chapterSeq: number;
    chunkSeq: number;
    excerpt: string;
    bookId?: number | null;
    bookTitle?: string | null;
}

export interface AskDone {
    assistantMessageId: number;
    note: string | null;
}

/** SSE 事件回调(显式事件类型,FR-303):onError 收尾则其余不再来。 */
export interface AskEvents {
    onMeta?: (meta: AskMeta) => void;
    onDelta?: (text: string) => void;
    onDone?: (done: AskDone) => void;
    onError?: (message: string) => void;
}

// ---- 嵌入任务(M4) ----

/** 嵌入状态:status ∈ none | pending | running | done | failed */
export interface EmbeddingStatus {
    bookId: number;
    status: 'none' | 'pending' | 'running' | 'done' | 'failed';
    model: string | null;
    chunkDone: number | null;
    chunkTotal: number | null;
    error: string | null;
    updatedAt: string | null;
}

// ---- 错误 ----

/** 非 2xx 响应统一错误;message 来自后端可读文案({"error": ...})。 */
export class ApiError extends Error {
    readonly status: number;

    constructor(status: number, message: string) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
    }
}

// ---- client ----

export interface ClientOptions {
    /** API 根地址;同源部署(开发经 Vite 代理)留空即可 */
    baseUrl?: string;
    /** 静态 token(D-4);经 Authorization 头注入,绝不进 URL */
    token: string;
    /** 非空表示同源请求必不可用:调用方(桌面壳未配置连接)已判定,一切请求直接抛此可读文案 */
    sameOriginBlocked?: string;
}

export function createClient({baseUrl = '', token, sameOriginBlocked}: ClientOptions) {
    const headers = (): HeadersInit => ({
        Authorization: `Bearer ${token}`,
    })

    async function request<T>(path: string, init?: RequestInit): Promise<T> {
        if (!baseUrl && sameOriginBlocked) throw new ApiError(0, sameOriginBlocked)
        let res: Response
        try {
            res = await fetch(baseUrl + path, {
                ...init,
                headers: {...headers(), ...(init?.headers ?? {})},
            })
        } catch {
            // 网络层失败(后端不可达/DNS/断网):fetch 拖 TypeError,浏览器文案不可读;
            // 同源与跨域(绝对 baseUrl)口径一致,统一换可读文案(US 9/14)
            throw new ApiError(0, '无法连接到后端:请确认后端已启动,或在连接设置里检查后端地址')
        }
        if (!res.ok) {
            throw new ApiError(res.status, await errorMessage(res))
        }
        if (res.status === 204) return undefined as T
        const contentType = res.headers.get('content-type') ?? ''
        if (contentType.includes('application/json')) {
            return res.json() as Promise<T>
        }
        return res.blob() as unknown as Promise<T>
    }

    /** 列表端点契约校验:2xx 必须是 JSON 数组。任何非数组(典型:Tauri 壳资产协议对
     *  未命中路径回退 index.html → 200 text/html blob;或地址指向了别的服务)统一换
     *  可读错误——否则非数组流入 UI state,渲染期 .map 崩溃整树卸载(白屏)。 */
    async function requestList<T>(path: string): Promise<T[]> {
        const value = await request<unknown>(path)
        if (!Array.isArray(value)) {
            throw new ApiError(0, '后端响应不是预期的列表数据:请确认连接设置里的地址指向 Reader 后端')
        }
        return value
    }

    async function errorMessage(res: Response): Promise<string> {
        try {
            const body = await res.json()
            if (body && typeof body.error === 'string' && body.error) return body.error
        } catch {
            // 非 JSON 响应,退回状态文案
        }
        return `请求失败(${res.status})`
    }

    /** 发起 SSE 提问请求(书级/跨书共用):鉴权头 + JSON 体 + 网络层错误换可读文案。 */
    async function postSse(path: string, input: unknown, signal?: AbortSignal): Promise<Response> {
        if (!baseUrl && sameOriginBlocked) throw new ApiError(0, sameOriginBlocked)
        let res: Response
        try {
            res = await fetch(baseUrl + path, {
                method: 'POST',
                headers: {...headers(), 'Content-Type': 'application/json'},
                body: JSON.stringify(input),
                signal,
            })
        } catch (e) {
            if (e instanceof DOMException && e.name === 'AbortError') throw e
            throw new ApiError(0, '无法连接到后端:请确认后端已启动,或在连接设置里检查后端地址')
        }
        if (!res.ok) {
            throw new ApiError(res.status, await errorMessage(res))
        }
        if (!res.body) throw new ApiError(0, '后端未返回流式响应')
        return res
    }

    /** 消费 SSE 流(书级/跨书同构的事件序列):meta → delta… → done / error。 */
    async function consumeSse(res: Response, events: AskEvents): Promise<void> {
        const reader = res.body!.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        let sawTerminal = false // done 或 error 至少一个才算流正常收尾
        for (; ;) {
            const {done, value} = await reader.read()
            if (done) break
            buffer += decoder.decode(value, {stream: true})
            // SSE 事件以空行分隔;逐块解析完整的 event:/data: 对
            let sep: number
            while ((sep = buffer.indexOf('\n\n')) >= 0) {
                const block = buffer.slice(0, sep)
                buffer = buffer.slice(sep + 2)
                const name = /(?:^|\n)event:(.*)/.exec(block)?.[1]?.trim()
                const data = /(?:^|\n)data:(.*)/.exec(block)?.[1]?.trim()
                if (!name || data == null) continue
                let payload: any
                try {
                    payload = JSON.parse(data)
                } catch {
                    continue
                }
                switch (name) {
                    case 'meta':
                        events.onMeta?.(payload as AskMeta)
                        break
                    case 'delta':
                        events.onDelta?.((payload as { text: string }).text)
                        break
                    case 'done':
                        sawTerminal = true
                        events.onDone?.(payload as AskDone)
                        break
                    case 'error':
                        sawTerminal = true
                        events.onError?.((payload as { message: string }).message)
                        break
                }
            }
        }
        if (!sawTerminal) {
            // 连接中断且无终态事件(done/error):不悬挂,显式报错
            events.onError?.('AI 连接中断,请重试')
        }
    }

    return {
        // ---- 书库 ----

        /** 书库列表(FR-103):新上传在前 */
        listBooks(): Promise<BookListItem[]> {
            return requestList<BookListItem>('/api/books')
        },

        /** 书籍详情(含章节数等完整元数据)。 */
        getBook(bookId: number): Promise<BookDetail> {
            return request<BookDetail>(`/api/books/${bookId}`)
        },

        /** 上传 EPUB(FR-101);duplicate=true 表示已在书库(D-30) */
        async uploadBook(file: File | Blob, filename = 'book.epub'): Promise<UploadBookResponse> {
            const form = new FormData()
            form.append('file', file, filename)
            return request<UploadBookResponse>('/api/books', {method: 'POST', body: form})
        },

        /** 删除书籍(FR-104):后端执行完整级联(文件/划线/进度/会话) */
        deleteBook(bookId: number): Promise<void> {
            return request<void>(`/api/books/${bookId}`, {method: 'DELETE'})
        },

        /** 封面图(带 token 程序化拉取;objectURL 交给 <img>) */
        async fetchCover(coverUrl: string): Promise<Blob> {
            return request<Blob>(coverUrl)
        },

        /** 书源文件(M1-04):渲染引擎的原料,程序化带 token 拉取 */
        async fetchBookFile(bookId: number): Promise<Blob> {
            return request<Blob>(`/api/books/${bookId}/file`)
        },

        // ---- 划线 ----

        /** 按书全量拉取(D-24) */
        listHighlights(bookId: number): Promise<Highlight[]> {
            return requestList<Highlight>(`/api/books/${bookId}/highlights`)
        },

        createHighlight(bookId: number, input: CreateHighlightInput): Promise<Highlight> {
            return request<Highlight>(`/api/books/${bookId}/highlights`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(input),
            })
        },

        /** 单条更新(颜色/备注);LWW 后写胜 */
        updateHighlight(id: number, input: UpdateHighlightInput): Promise<Highlight> {
            return request<Highlight>(`/api/highlights/${id}`, {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(input),
            })
        },

        deleteHighlight(id: number): Promise<void> {
            return request<void>(`/api/highlights/${id}`, {method: 'DELETE'})
        },

        // ---- 进度 ----

        /** 单行读取;404(暂无进度)时返回 null,端上从书首开始 */
        async getProgress(bookId: number): Promise<Progress | null> {
            try {
                return await request<Progress>(`/api/books/${bookId}/progress`)
            } catch (e) {
                if (e instanceof ApiError && e.status === 404) return null
                throw e
            }
        },

        /** 单条 upsert(FR-203);CFI 与百分比由 foliate-js 产出,服务端原样存储 */
        upsertProgress(bookId: number, input: UpsertProgressInput): Promise<Progress> {
            return request<Progress>(`/api/books/${bookId}/progress`, {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(input),
            })
        },

        // ---- 模型设置 ----

        /** 读取单套配置;从未保存过返回全空字段(空表单) */
        getModelSettings(): Promise<ModelSettings> {
            return request<ModelSettings>('/api/settings/model')
        },

        /** 保存(整行覆盖);base URL 与 chat 模型必填 */
        saveModelSettings(input: ModelSettingsInput): Promise<ModelSettings> {
            return request<ModelSettings>('/api/settings/model', {
                method: 'PUT',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(input),
            })
        },

        /** 测试连接(FR-405):chat 与 embedding 双探针;不传 input 则测已保存配置 */
        testModelConnection(input?: ModelSettingsInput): Promise<TestConnectionResult> {
            return request<TestConnectionResult>('/api/settings/model/test', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: input ? JSON.stringify(input) : undefined,
            })
        },

        // ---- 章节 ----

        /** 章节列表(spine 阅读序;AI 目标章映射用,D-31) */
        listChapters(bookId: number): Promise<ChapterSummary[]> {
            return requestList<ChapterSummary>(`/api/books/${bookId}/chapters`)
        },

        // ---- AI 对话 ----

        /** 某书会话列表,按最近活跃排序。 */
        listSessions(bookId: number): Promise<ChatSession[]> {
            return requestList<ChatSession>(`/api/books/${bookId}/sessions`)
        },

        /** 跨书会话列表(S4):仅 book_id 为空的会话,按最近活跃排序。 */
        listGlobalSessions(): Promise<ChatSession[]> {
            return requestList<ChatSession>('/api/sessions')
        },

        /** 某书嵌入状态(最新任务);未建任务返回 none。 */
        getEmbeddingStatus(bookId: number): Promise<EmbeddingStatus> {
            return request<EmbeddingStatus>(`/api/books/${bookId}/embedding`)
        },

        /** 触发嵌入(一入口多态:首次嵌入 / 失败重试 / 换模型全量重嵌入;
         * pending/running 幂等返回当前状态;未配置 embedding 拋 4xx 可读文案)。 */
        triggerEmbedding(bookId: number): Promise<EmbeddingStatus> {
            return request<EmbeddingStatus>(`/api/books/${bookId}/embedding/trigger`, {method: 'POST'})
        },

        /** 会话全部消息(含 refs),打开会话一次拿齐。 */
        listSessionMessages(sessionId: number): Promise<ChatMessage[]> {
            return requestList<ChatMessage>(`/api/sessions/${sessionId}/messages`)
        },

        renameSession(sessionId: number, title: string): Promise<ChatSession> {
            return request<ChatSession>(`/api/sessions/${sessionId}`, {
                method: 'PATCH',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({title}),
            })
        },

        deleteSession(sessionId: number): Promise<void> {
            return request<void>(`/api/sessions/${sessionId}`, {method: 'DELETE'})
        },

        /** 书级提问(SSE 流式):meta → delta… → done / error;4xx(未配置/预算不足等)
         * 在流开始前抛 ApiError(可读文案);流中错误经 onError 回调收尾,不悬挂。 */
        async askStream(bookId: number, input: AskInput, events: AskEvents, signal?: AbortSignal): Promise<void> {
            const res = await postSse(`/api/books/${bookId}/ask`, input, signal)
            await consumeSse(res, events)
        },

        /** 跨书提问(S4,SSE 流式与书级同构):meta → delta… → done / error,
         * citations 随 meta 下发并携带书籍身份;4xx(未配置 embedding/全库无就绪书等)
         * 在流开始前抛 ApiError(可读文案)。 */
        async askGlobalStream(input: GlobalAskInput, events: AskEvents, signal?: AbortSignal): Promise<void> {
            const res = await postSse('/api/ask', input, signal)
            await consumeSse(res, events)
        },
    }
}

export type ApiClient = ReturnType<typeof createClient>
