# apps/web · Reader 网页应用(React + TypeScript + Vite)

渲染引擎 foliate-js 以 vendor 源码入库(`vendor/foliate-js/`,ADR-0004);API 契约在 `packages/api-client`。

## 本地开发

```bash
# 后端(默认 8080;见 backend/README.md)
# 前端(5173,/api 代理到 http://localhost:8080,可用 READER_BACKEND_URL 覆盖)
npm run dev          # 仓库根目录
npm run typecheck -w @reader/web
```

- token:环境变量 `VITE_READER_TOKEN`(默认 `reader-dev-token`,与后端开发默认一致);
  由 api-client 统一经 Authorization 头携带,绝不进 URL。

## E2E(全栈:真实浏览器 × 真实后端 × Docker PG)

```bash
cd apps/web && npx playwright test
```

global-setup 自编排:Docker 起 `pgvector/pgvector:pg18` → `mvn -DskipTests package` 打后端 →
`java -jar` 真实进程(18080)→ Playwright webServer 起 Vite。fixture 在 `e2e/fixtures/`
(生成器 `generate_fixtures.py`)。

### Arch/无 root WSL 环境(本机)注记

系统缺 chromium 依赖与字体时(报 `libnspr4.so` 缺失或元素高度为 0),用用户级补丁:

```bash
export LD_LIBRARY_PATH=$HOME/.local/pw-libs/extracted/usr/lib   # 解包的 Arch 包库
export FONTCONFIG_FILE=$HOME/.local/pw-libs/fonts.conf          # 用户字体(Noto CJK)
npx playwright test
```

(CI 的 ubuntu runner 走 `npx playwright install --with-deps chromium`,不需要以上步骤。)

## 目录约定

- `src/pages` 页面(书库/阅读器);`src/components` 组件;`src/client.ts` client 单例 + 设备标识
- `e2e/` Playwright(spec、global-setup、fixtures)
- `vendor/foliate-js/` 渲染引擎(裁剪口径见其 VENDORED.md)
- `spike/` M1-01 spike 页(结论见 `.scratch/m1-web-reader/spike-conclusion.md`)
