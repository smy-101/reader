import {ChildProcess, execFileSync, execSync, spawn} from 'node:child_process'
import {setTimeout as sleep} from 'node:timers/promises'
import {request} from '@playwright/test'

/**
 * E2E 编排(M1-03,细节实现时定、验收只看用例绿):
 * 1) Docker 起 pgvector/pgvector:pg18(与生产/后端集成测试同款镜像),端口 127.0.0.1:15433
 * 2) 后端以真实进程启动:mvn -DskipTests package → java -jar(端口 18080,token reader-dev-token)
 * 3) teardown:反向全停(容器 --rm)
 * Vite(webServer)由 Playwright 自己管理,代理 /api → 18080。
 * 书籍删除 API 不在 M1 范围,用例隔离靠 resetBackend(直连测试容器清表)。
 */

const PG_CONTAINER = 'reader-e2e-pg'
const PG_PORT = 15433
const BACKEND_PORT = 18080
const TOKEN = 'reader-dev-token'

export const e2e = {PG_CONTAINER, PG_PORT, BACKEND_PORT, TOKEN}

let backend: ChildProcess | null = null

export async function upPostgres() {
    try {
        execSync(`docker rm -f ${PG_CONTAINER}`, {stdio: 'ignore'})
    } catch {
        // 没有旧容器,忽略
    }
    console.log(`[e2e] 启动 PG 容器(${PG_CONTAINER}:${PG_PORT})…`)
    execFileSync('docker', [
        'run', '-d', '--rm', '--name', PG_CONTAINER,
        '-e', 'POSTGRES_DB=reader_e2e',
        '-e', 'POSTGRES_USER=reader_app',
        '-e', 'POSTGRES_PASSWORD=e2e',
        '-p', `127.0.0.1:${PG_PORT}:5432`,
        'pgvector/pgvector:pg18',
    ])
    for (let i = 0; i < 30; i++) {
        try {
            execFileSync('docker', ['exec', PG_CONTAINER, 'pg_isready', '-U', 'reader_app', '-d', 'reader_e2e'],
                {stdio: 'ignore'})
            return
        } catch {
            await sleep(1000)
        }
    }
    throw new Error('PG 容器未就绪')
}

export async function upBackend() {
    const backendDir = `${process.cwd()}/../../backend`
    const jar = process.env.READER_E2E_JAR ?? `${backendDir}/target/reader-backend-0.0.1-SNAPSHOT.jar`
    if (!process.env.READER_E2E_JAR) {
        console.log('[e2e] 打包后端(mvn -DskipTests package)…')
        execFileSync('mvn', ['-q', '-DskipTests', 'package', '-f', `${backendDir}/pom.xml`], {stdio: 'inherit'})
    }
    console.log(`[e2e] 启动后端(${jar.split('/').pop()}:${BACKEND_PORT})…`)
    backend = spawn('java', ['-jar', jar], {
        env: {
            ...process.env,
            READER_DB_URL: `jdbc:postgresql://127.0.0.1:${PG_PORT}/reader_e2e`,
            READER_DB_USER: 'reader_app',
            READER_DB_PASSWORD: 'e2e',
            READER_AUTH_TOKEN: TOKEN,
            READER_SERVER_PORT: String(BACKEND_PORT),
            READER_DATA_DIR: `${process.cwd()}/e2e/.data`,
        },
        stdio: ['ignore', 'pipe', 'pipe'],
    })
    backend.stdout!.on('data', d => process.env.E2E_BACKEND_DEBUG && console.log(`[backend] ${d}`))
    backend.stderr!.on('data', d => process.env.E2E_BACKEND_DEBUG && console.error(`[backend] ${d}`))

    // 健康检查:/api/books 带 token 返回 200(库为空也行)
    for (let i = 0; i < 60; i++) {
        try {
            const ctx = await request.newContext({
                baseURL: `http://localhost:${BACKEND_PORT}`,
                extraHTTPHeaders: {Authorization: `Bearer ${TOKEN}`},
            })
            const res = await ctx.get('/api/books')
            await ctx.dispose()
            if (res.ok()) return
        } catch {
            // 还没起来,继续等
        }
        await sleep(1000)
    }
    throw new Error('后端未就绪(60s)')
}

/** 清空书库(E2E 容器专属;级联会带走章节/划线/进度,storage 残留无害:同 hash 重传幂等)。 */
export async function resetBackend() {
    execFileSync('docker', [
        'exec', PG_CONTAINER,
        'psql', '-U', 'reader_app', '-d', 'reader_e2e',
        '-c', 'TRUNCATE highlight, reading_progress, chapter, book RESTART IDENTITY CASCADE',
    ], {stdio: 'ignore'})
}

export default async function globalSetup() {
    await upPostgres()
    await upBackend()
    return async function globalTeardown() {
        console.log('[e2e] teardown:停后端 + 删容器')
        backend?.kill('SIGTERM')
        try {
            execSync(`docker rm -f ${PG_CONTAINER}`, {stdio: 'ignore'})
        } catch {
            // 已不在,忽略
        }
    }
}
