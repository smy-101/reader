# Spec: M2 Tauri 桌面壳 —— apps/desktop 打包 Windows 安装包 + 连接设置 + CORS

Status: ready-for-agent
Created: 2026-08-28
Source: `docs/需求文档.md` v1.3(M2 章节、§3.3 功能×端矩阵、D-7/D-22/D-23、R-8)+ `CONTEXT.md` 术语表 + M1 落地现状
Milestone: M2(Tauri 桌面壳)

## Problem Statement

作为个人读者兼开发者,M1 之后我已经能在浏览器里读书、划线、接续进度,但"桌面端"至今只是需求文档里的一行字:仓库里 apps/desktop 只有一个 .gitkeep 占位。想读书,得先起后端、开浏览器、敲地址,阅读器混在几十个标签页里,没有独立窗口,没有"一个软件"的感觉;浏览器一清缓存,token 这类构建期注入的环境也没了着落。更具体的三道坎:(a)桌面壳是独立加载的 web 产物,后端地址与 token 目前只能构建期注入,做不到"装上就能连我自己的后端";(b)后端零 CORS 配置,WebView2 里从壳 origin 发起的跨域 API 请求会被直接拦下,连报错都到不了后端;(c)我的开发机是 WSL,无法原生产出 Windows 安装包,本机装整套 Rust/MSVC 工具链又与"本机零移动端工具链"(D-22)的克制背道而驰。

## Solution

一个装在 Windows 上的独立桌面阅读器:Tauri 2 壳加载 apps/web 的构建产物,独立窗口、独立任务栏图标,功能与 M1 网页完全一致(书库、批量上传、阅读、划线、进度——§3.3 矩阵);首次启动在应用内"连接设置"填一次后端地址与 token(localStorage 持久化,含测试连接),之后打开即用,进度划线与浏览器经同一后端互通。配套打通两处地基:后端补全局 CORS(跨域放行 Authorization 头,preflight 不被 token 拦截,401 防线跨域下不变);CI 增 windows job 云构建 NSIS 安装包并上传 artifact——本机零 Rust 工具链,WSL 无障碍。壳本身零业务逻辑:所有功能与自动化测试仍落在 apps/web 与后端两个既有 seam,壳只做打包,人工按验收标准("桌面日常可用,体验同 M1")验收。

## User Stories

1. As a reader, I want 在 Windows 桌面拥有一个独立的阅读器应用(独立窗口、任务栏图标、可最小化/最大化/调整大小), so that 读书是一个"软件"而不是一堆标签页里的一个网页。
2. As a reader, I want 拿到安装包双击安装、装完即用, so that 换电脑或重装系统后几分钟恢复读书环境。
3. As a reader, I want 首次启动在应用内填写后端地址和 token,之后自动记住, so that 只配置一次,以后打开就能连上我的书库。
4. As a reader, I want 连接设置里有"测试连接"按钮并能告诉我 ok 或可读的错误, so that 配错地址/token 当场就能发现,而不是面对一个打不开的书库列表。
5. As a reader, I want 桌面端功能与网页端完全一致(书库浏览/过滤、批量上传、阅读、划线、进度), so that 不用记"哪个功能在哪个端"(§3.3:桌面 = 壳内即 Web)。
6. As a reader, I want 在桌面端多选批量上传 EPUB 并看到逐本结果, so that 管理藏书不必切回浏览器。
7. As a reader, I want 桌面端与浏览器共用同一后端、进度与划线互通, so that 白天浏览器读一半,晚上桌面应用接着读,接续无感。
8. As a reader, I want 后端地址变了(比如将来从本机迁到云)时,只在连接设置里改个 URL 就切过去, so that 壳永远不用重新打包。
9. As a reader, I want token 配错或后端不可达时看到可读的中文错误提示, so that 我知道该改连接设置还是该去起后端,而不是白屏猜谜。
10. As a reader, I want 未配置连接时应用引导我去做连接设置而不是报一堆错, so that 首次体验是"被牵着走"。
11. As a reader, I want 字号/主题等阅读设置在桌面端调整后记住, so that 桌面端阅读舒适;它们本就仅存本地(FR-201),与浏览器各一份互不干扰。
12. As a reader, I want 桌面端窗口里的选字划线、目录跳转等手势行为与浏览器一致, so that 同一套肌肉记忆两端通用。
13. As an API consumer, I want 跨域 preflight(OPTIONS)请求被正确应答(正确的 Access-Control-Allow-Origin / -Headers / -Methods), so that WebView2 里的跨域 API 调用能发出真正的请求。
14. As an API consumer, I want 带自定义后端绝对地址的请求行为与同源部署完全一致(含错误文案格式), so that UI 层不需要任何跨域特判。
15. As an API consumer, I want 不带 token 的跨域请求依然 401(CORS 放行 ≠ 鉴权放行), so that 安全边界与 M0/M1 完全一致。
16. As an API consumer, I want preflight OPTIONS 请求不因缺 token 被拦截, so that 浏览器标准跨域握手不被鉴权过滤器误杀。
17. As a web user, I want 在浏览器里也能用同一个"连接设置"页配后端 URL 与 token, so that 不依赖构建期环境变量也能把 web 产物指向任意后端(叠加能力,默认行为不变)。
18. As a developer, I want Tauri 壳保持零业务逻辑(纯默认模板 + 加载 web 产物), so that 功能开发与测试全部留在 apps/web 与后端,壳永不成为第二个逻辑归宿(D-7)。
19. As a developer, I want 连接设置做成 apps/web 的运行时配置(localStorage 持久化,未配置时回退 M1 现状:同源 + 构建期注入 token), so that 它是叠加而非替换,既有 E2E 与开发流零回归。
20. As a developer, I want 后端 CORS 以全局配置落地(放行任意 origin、不放行 credentials、放行 Authorization 头), so that 不必维护各端 origin 白名单,且与"静态 token 是唯一防线"的已接受姿态(R-8)一致。
21. As a developer, I want CORS 行为进 Seam A 集成测试(preflight 应答、实际跨域请求、401 防线跨域下仍成立), so that 跨域口径是被验证的行为而非碰巧能用。
22. As a developer, I want E2E 增一条"桌面等效连接"用例(经连接设置 UI 配绝对后端 URL + token,走通 列表→打开→划线/进度 链路), so that 壳真正执行的那条代码路径(跨域绝对 baseUrl fetch)被自动化覆盖。
23. As a developer, I want CI 增 windows job 云构建安装包并上传 artifact, so that 本机零 Rust 工具链(D-22 的桌面版),WSL 开发无障碍。
24. As a developer, I want 打包 job 与既有 backend/web job 互不干扰(Playwright E2E 留在 ubuntu), so that 打包慢不影响测试反馈。
25. As a developer, I want Tauri 选型与"CI 云打包"通道写成简短 ADR, so that 关键选型留档(毕业纪律:关键选型写 ADR)。
26. As a developer, I want 安装包从 CI artifact 下载即得, so that 发布物可追溯(哪个 commit 出的包),不依赖某台机器的环境。

## Implementation Decisions

- **范围口径**:本 spec 只覆盖 M2(Tauri 桌面壳)。功能本身零新增——桌面端交付的就是 M1 已有的全部能力(§3.3);删除书籍级联仍等 M3/M4 的表(沿用 M1 spec 的 Out of Scope 口径)。M3+(AI 对话、向量检索、安卓端、上云)各出各的 spec。
- **模块划分**:
  - `apps/desktop`(新):Tauri 2 壳,frontendDist 指向 apps/web 构建产物;**零自定义 Rust 逻辑**(默认模板即交付形态);NSIS 安装包(默认 target)。WebView2 运行时缺失时由 NSIS 引导安装(Tauri 默认行为)。
  - `apps/web`(扩展):新增"连接设置"运行时配置页/入口——后端地址(绝对 URL)与 token,localStorage 持久化;含"测试连接"(调既有书库列表端点,ok / 可读错误),**后端零新增端点**。
  - 后端(扩展):全局 CORS 配置;无新表、无新端点、无迁移。
  - CI(扩展):新增 windows 打包 job。
- **连接设置语义**:api-client 的 baseUrl 沿用"空 = 同源"语义;已配置连接时以运行时配置优先,未配置时完全回退 M1 现状(同源 + 构建期 VITE_READER_TOKEN 注入)——对既有开发流与 E2E 是纯叠加。token 存 localStorage 明文(个人工具,与 FR-404 同一已接受姿态;不进 URL、不进壳二进制)。桌面端 WebView2 的 localStorage 与浏览器天然隔离:阅读设置(FR-201)与设备标识(device)在桌面端独立生效,无需任何代码处理。
- **CORS 口径**:后端全局放行任意 origin、不放行 credentials(鉴权走 Authorization 头而非 cookie),放行 Authorization / Content-Type 头与 GET/POST/PUT/DELETE/OPTIONS 方法;静态 token 鉴权与 CORS 正交——**跨域不带 token 一律 401 不变**;鉴权过滤器对 OPTIONS preflight 放行(不因缺 token 拦截)。放行任意 origin 的理由:静态 token 本就是唯一防线(R-8 已接受),而壳 origin 在不同平台并不相同(Windows WebView2 与其他平台前缀不一),白名单徒增维护;以 ADR 记录此取舍。
- **测试 seam(已与用户确认)**:复用两个既有 seam 并小幅扩展,**不为 Tauri 壳新建自动化 seam**:
  - Seam A(后端 HTTP × Testcontainers)扩 CORS 用例;
  - Seam B(Playwright 全栈 E2E)扩"桌面等效连接"用例——经连接设置 UI 配置绝对后端 URL(指向真实后端端口)+ 测试 token,走通 列表→打开→划线/进度 链路;该请求在浏览器视角即跨域(页面 origin 与后端端口不同、携带 Authorization 头触发 preflight),**天然同时覆盖 CORS 的 preflight 与实际请求路径**。
  - Tauri 壳与安装包:无自动化 seam(壳零逻辑);CI 打包 job 绿 + artifact 存在即构建验证;"桌面日常可用,体验同 M1"走人工验收清单。
- **打包通道(已与用户确认)**:GitHub Actions windows runner 云构建,上传安装包 artifact;本机不装 Rust/MSVC 工具链(WSL 无法原生产出 Windows 安装包,对齐 D-22"本机零工具链"精神)。本地开发循环维持浏览器 + vite dev,壳层表现(窗口、WebView2 特有行为)在 CI 产物上人工验证。打包 job 触发时机(push / 手动 / tag)实现时定,验收只看 job 绿 + 产物在。
- **ADR**:新增一篇简短 ADR——Tauri 2 桌面壳落地要点(零壳逻辑、静态嵌入 web 产物、NSIS、CI 云打包、CORS 放行策略),编号顺延现有 5 篇。
- **体验同 M1 的验收口径**:桌面端能完成"配置连接 → 书库浏览 → 批量上传 → 打开读书 → 划线 → 重开应用接续进度"全链路,与浏览器经同一后端数据互通。

## Testing Decisions

- **好测试的标准**(沿用 M1):只断言外部可观察行为——HTTP 状态码/响应头/响应体(含错误文案)、数据库行、浏览器里可见的结果与持久化效果;不 mock 被测边界内的模块,不测实现细节。
- **Seam A(既有扩展)**:CORS 集成测试,全部挂在既有 Testcontainers 基建上:
  - OPTIONS preflight(带 Origin、Access-Control-Request-Method/Headers)→ 2xx + Access-Control-Allow-Origin / -Allow-Headers / -Allow-Methods 正确;且**不带 token 也放行**(preflight 不携带凭据是浏览器语义)。
  - 实际跨域请求(带 Origin + token)→ 响应携带 Access-Control-Allow-Origin;业务行为与同源请求一致。
  - 跨域不带 token → 401 不变;**CORS 不豁免鉴权**。
  - Prior art:M0/M1 六个集成测试类与 IntegrationTestBase。
- **Seam B(既有扩展)**:"桌面等效连接"E2E 用例,复用既有全栈 harness(真实后端进程 × Testcontainers PG × 真 EPUB fixture):
  - 经连接设置 UI 配置绝对后端 URL + 正确 token → 书库列表可用、设置重载页面后仍在(持久化)、读书→划线/进度链路走通(数据落库)。
  - 错 token → 401 可读文案;不可达 URL → 可读错误提示(连接设置页不崩)。
  - Prior art:M1 的 library / highlights / acceptance 用例与 helpers(resetBackend 直连断言)。
- **Tauri 壳/安装包**:无自动化 seam。构建验证 = CI windows job 绿 + artifact 存在;运行验证 = 人工验收清单(安装 → 首次配置 → 读一本书 → 划线 → 重开接续),对应 M2 验收标准原文。
- **packages/api-client**:无独立单测(沿用 M1 口径,经 Seam A 契约与 Seam B 链路间接覆盖)。

## Out of Scope

- 一切新功能:删除书籍与完整级联(FR-104,依赖 M3/M4 表)、AI 对话与模型设置域(M3)、embedding/向量检索(M4)、安卓端(M5)、后端部署上云(M6——届时桌面端只改连接设置指向云后端)。
- 桌面壳特有增强(均为 v2 候选):系统托盘、全局快捷键、离线缓存、.epub 文件关联双击导入、Tauri 自动更新(updater)、多窗口、跟随系统深色模式。
- macOS/Linux 安装包(设备是 Windows;Tauri 理论可扩,不承诺)。
- HTTPS/域名(R-8 口径不变,连接地址默认 http)。
- 阅读设置跨端同步(FR-201 明确仅存本地;桌面端与浏览器各一份是预期行为而非缺陷)。
- 双端同开实时可见(D-44 已接受"重开才可见")。
- token 加密存储(明文已接受,FR-404 同姿态)。

## Further Notes

- M2 验收标准原文映射:"apps/desktop 打包 Windows 安装包" → CI windows job 产出 NSIS artifact;"桌面日常可用,体验同 M1" → 人工验收全链路清单(见 Testing Decisions)。
- 风险提示:WebView2 的 foliate-js 渲染与浏览器 Chromium 内核一致(同为 Chromium),风险低;若发现壳内特有异常,优先在 web 层修复(壳零逻辑原则),仅窗口/协议层问题才动壳配置。
- 连接设置是后续里程碑的复用地基:M6 上云后所有端只改 URL;M3 模型设置域的"测试连接"(FR-405)可参考本次连接测试的交互。
- 若 CI windows runner 排队/时长成为负担,再评估本机 Windows 侧工具链或自托管 runner,届时修订 ADR;不在本里程碑内。
- 后端 CORS 上线后,攻击面无实质变化(静态 token 唯一防线,R-8 已接受;CORS 不放行 credentials,浏览器侧无 cookie 可滥用)。
