import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// 开发/E2E:/api 纯转发到真实后端;token 由 api-client 经 Authorization 头携带,
// 代理不注入任何凭据(不经 URL 传 token 的口径落在 client 一侧)。
export default defineConfig({
    plugins: [react()],
    // 相对路径产物(tauri 加固):内置资源经 http://tauri.localhost 自定义协议伺服时,
    // ./assets 相对引用不依赖 origin 根解析,规避绝对路径在壳内 404 的白屏风险;
    // 常规 web 伺服(根路径)行为不变,dev server 不受影响
    base: './',
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
