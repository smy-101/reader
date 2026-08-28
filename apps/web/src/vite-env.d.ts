/// <reference types="vite/client" />

interface ImportMetaEnv {
    /** 后端静态 token(D-4);本地默认与后端开发默认一致 */
    readonly VITE_READER_TOKEN?: string
}

interface ImportMeta {
    readonly env: ImportMetaEnv
}
