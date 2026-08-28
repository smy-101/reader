import {defineConfig} from '@playwright/test'

/**
 * M1-03 全栈 E2E harness(Seam B):
 * 真实浏览器 × 真实后端进程 × Docker PG(pgvector/pgvector:pg18,与生产同款)× 真 EPUB fixture。
 * 编排:global-setup 负责 PG 容器 + 后端 jar;webServer 起 Vite(dev,代理 /api → 后端)。
 * token:后端与前端共用默认 reader-dev-token(E2E 环境即本地开发口径)。
 */
export default defineConfig({
    testDir: './e2e',
    timeout: 60_000,
    expect: {timeout: 10_000},
    fullyParallel: false, // 单后端单库,串行隔离(每用例自清数据)
    workers: 1,
    retries: process.env.CI ? 1 : 0,
    reporter: process.env.CI ? [['list'], ['html', {open: 'never'}]] : 'list',
    use: {
        baseURL: 'http://localhost:5173',
    },
    globalSetup: './e2e/global-setup.ts',
    webServer: {
        command: 'npx vite --port 5173 --strictPort --host',
        url: 'http://localhost:5173',
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
        env: {
            READER_BACKEND_URL: 'http://localhost:18080',
            VITE_READER_TOKEN: 'reader-dev-token',
        },
    },
})
