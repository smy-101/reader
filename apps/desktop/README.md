# apps/desktop · Tauri 2 桌面壳

零自定义 Rust 逻辑(D-7 / ADR-0006):默认模板 + 配置即交付形态,加载 apps/web 构建产物。
后端地址与 token 由 web 层**连接设置**运行时配置(localStorage),壳不感知,永不因换后端重打包。

## 结构

- **frontendDist 路径口径**:配置值为 `../../web/dist`——CLI 从 apps/desktop 发起时按
  `cwd + src-tauri/../../web/dist` 解析,恰为 apps/web/dist;若按配置文件目录(官方口径)解析
  同样得 apps/web/dist。两级 `../` 在两种解析基下都正确,一级会错指 apps/desktop/web/dist。

- `src-tauri/tauri.conf.json`:`frontendDist: "../../web/dist"`(见下方路径口径)、窗口、NSIS 打包
- `src-tauri/src/` 默认模板(main.rs + lib.rs,无任何 command);`capabilities/` 不授权任何权限
- `src-tauri/icons/` 脚本生成的极简图标(蓝底白书)

## 打包(CI 云构建,本机零 Rust/MSVC 工具链)

Windows 安装包由 GitHub Actions **windows runner** 产出(`.github/workflows/ci.yml` 的
`desktop-package` job,push 到 main / 手动触发),NSIS 安装包上传 artifact `reader-windows-nsis`,
从哪个 commit 下载即哪个 commit 的包。本地不装工具链(WSL 无法原生产出 Windows 包,ADR-0006)。

如确需本机构建(如调试壳层):装 Rust(MSVC)后

```bash
npm run build -w apps/web        # 先产 web 产物(frontendDist 指向它)
cd apps/desktop && npx tauri build
```

## 壳内后端地址

WebView2 页面 origin(`http://tauri.localhost`)与后端不同源,跨域 API 依赖后端全局 CORS
(M2-01);token 仅经 Authorization 头,与浏览器端同一 api-client 代码路径。

### 首启未配置连接(白屏事故记因,M2-03 排查续)

未配置连接时同源回退(`fetch('/api/books')` 打到 `http://tauri.localhost`)在壳内必失败:
Tauri 资产协议对未命中路径**一律回退 index.html(200 + text/html)**,api-client 收到
2xx 非数组 → 曾直接 `setBooks(Blob)` → `filtered.map` 渲染崩溃整树卸载(白屏,
压缩报错 `ll.map is not a function`)。双层防御:

1. `connection.ts` `isTauriShell()` + `client.ts`:壳内未配置连接时,请求直抛可读文案
   “桌面端尚未配置后端连接…”,书库页展示并引导打开连接设置(首启即 onboarding);
2. api-client 列表端点契约守卫(`requestList`):任何 2xx 非数组响应换可读 ApiError,
   杜绝同类“垃圾数据流入 UI state → 渲染崩溃”问题(浏览器端错指其它服务同受保护)。

回归用例:`e2e/desktop-connection.spec.ts` “同源响应非列表(等效壳内资产回退)”。
