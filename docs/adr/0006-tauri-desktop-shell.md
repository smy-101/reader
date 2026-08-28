# ADR-0006: Tauri 2 桌面壳——零壳逻辑、静态嵌入 web 产物、NSIS、CI 云打包、CORS 放行任意 origin

- 状态:已接受(2026-08,M2 spec · Implementation Decisions)
- 关联决策:D-7(壳零逻辑)、D-22(本机零工具链)、D-23(CI)、R-8(静态 token 唯一防线,需求文档 §6.1)

## 背景

需求文档 M2 要求"apps/desktop 打包 Windows 安装包",且 §3.3 矩阵明确桌面 = 壳内即 Web(功能与 apps/web 完全一致)。开发机是 WSL,无法原生产出 Windows 安装包;给本机装整套 Rust/MSVC 工具链与"本机零移动端工具链"(D-22)的克制姿态相悖。另有两道配套地基:壳独立加载 web 产物,后端地址与 token 只能运行时配置(连接设置);WebView2 里从壳 origin 发起的跨域 API 请求需要后端 CORS 放行。

## 决策

**Tauri 2 桌面壳**,四条落地要点:

1. **零壳逻辑**:apps/desktop 即 Tauri 2 默认模板 + 配置——不注册任何 command、不引 serde、capabilities 不授权任何权限。一切功能与自动化测试留在 apps/web 与后端两个既有 seam;壳永不成为第二个逻辑归宿(D-7)。
2. **静态嵌入 web 产物**:`frontendDist` 指向 apps/web 构建产物(打 web 构建时**不注入构建期 token**,token 一律经 web 层连接设置运行时配置,不进壳二进制);后端地址同理,改后端只需改连接设置,壳永不重打包。桌面壳特有增强(托盘/快捷键/离线缓存/自动更新等)均 v2 候选,不在本期。
3. **NSIS 安装包**:默认 bundle target;WebView2 运行时缺失时由 NSIS 引导安装(Tauri 默认行为)。仅出 Windows 包(设备是 Windows;macOS/Linux 理论可扩,不承诺)。
4. **CI 云打包通道**:GitHub Actions windows runner 云构建并上传 NSIS artifact,本机零 Rust/MSVC 工具链(WSL 无障碍)。触发时机取 **push 到 main + 手动**,PR 不打包:打包不改变代码正确性(backend/web 两个 job 已覆盖测试),换得 PR 反馈轻;代价是壳层破坏最多延迟到 main push 才暴露,个人项目可接受。发布物可追溯(哪个 commit 出的包),Cargo.lock 入库保证构建可复现。

**后端 CORS 放行任意 origin**:全局放行、不放行 credentials(鉴权走 Authorization 头而非 cookie)、放行 Authorization/Content-Type 头与常用方法;静态 token 鉴权与 CORS 正交——跨域不带 token 仍 401,preflight 不被鉴权误杀。放行任意 origin 的理由:静态 token 本就是唯一防线(R-8 已接受),壳 origin 各平台不一(Windows WebView2 为 `http://tauri.localhost`),白名单徒增维护;不放 credentials,浏览器侧无 cookie 可滥用,攻击面无实质变化。

## 理由

- 桌面壳的评估标准是"装上就能用",不是"壳里能写代码":零逻辑壳把功能开发、测试、修复全部留在两个既有 seam,测试策略零新增(M2 spec · Testing Decisions:壳无自动化 seam,CI job 绿 + artifact 在即构建验证,人工按验收清单验运行)。
- WebView2 与浏览器同为 Chromium 内核,foliate-js 渲染与手势行为风险低;若壳内出现特有异常,优先在 web 层修(壳零逻辑原则),仅窗口/协议层问题才动壳配置。
- 云打包与"本机零工具链"一脉相承(D-22 的桌面版);若 windows runner 排队/时长成为负担,再评估本机 Windows 侧工具链或自托管 runner,届时修订本 ADR。

## 后果

- 每个 push 到 main 都会产出一个 NSIS artifact(约数百 KB 安装包 + Rust 构建缓存);PR 合并前看不到打包结果,壳层破坏延迟暴露(已接受)。
- 连接设置成为后续里程碑的复用地基:M6 上云后所有端只改 URL;M3 模型设置的"测试连接"可参考本次交互。
- token 明文存 localStorage(与 FR-404 同一已接受姿态);阅读设置(FR-201)与设备标识在桌面端 localStorage 与浏览器天然隔离,各一份是预期行为。
- 打包时 web 构建不含构建期 token:未配置连接时壳内是可读 401 错误 + 连接设置引导,而非静默同源回退。
