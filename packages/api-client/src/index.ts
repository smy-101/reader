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
}

export interface ChapterSummary {
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
}

export function createClient({baseUrl = '', token}: ClientOptions) {
    const headers = (): HeadersInit => ({
        Authorization: `Bearer ${token}`,
    })

    async function request<T>(path: string, init?: RequestInit): Promise<T> {
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

    async function errorMessage(res: Response): Promise<string> {
        try {
            const body = await res.json()
            if (body && typeof body.error === 'string' && body.error) return body.error
        } catch {
            // 非 JSON 响应,退回状态文案
        }
        return `请求失败(${res.status})`
    }

    return {
        // ---- 书库 ----

        /** 书库列表(FR-103):新上传在前 */
        listBooks(): Promise<BookListItem[]> {
            return request<BookListItem[]>('/api/books')
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
            return request<Highlight[]>(`/api/books/${bookId}/highlights`)
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
    }
}

export type ApiClient = ReturnType<typeof createClient>
