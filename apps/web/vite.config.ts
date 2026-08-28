import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// 开发/E2E:/api 纯转发到真实后端;token 由 api-client 经 Authorization 头携带,
// 代理不注入任何凭据(不经 URL 传 token 的口径落在 client 一侧)。
export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: process.env.READER_BACKEND_URL ?? 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
    preview: {
        port: 5173,
        proxy: {
            '/api': {
                target: process.env.READER_BACKEND_URL ?? 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
})
