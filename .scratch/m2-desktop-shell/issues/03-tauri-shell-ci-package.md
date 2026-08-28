# 03: Tauri 2 桌面壳 + CI 云打包出 Windows 安装包(ADR-0006)

**What to build:** apps/desktop 落地:Tauri 2 壳,加载 apps/web 构建产物,零自定义 Rust 逻辑(默认模板 + 配置即交付形态:产物路径、常规窗口配置、NSIS 安装包);WebView2 运行时缺失由 NSIS 引导安装(Tauri 默认行为)。CI 新增 windows job 云构建安装包并上传 artifact(触发时机实现时定,验收只看 job 绿 + 产物在)——本机零 Rust 工具链(WSL 无法原产出 Windows 安装包,对齐 D-22"本机零工具链"精神);backend / web(Playwright)两个既有 job 不受影响。附 ADR-0006 留档:Tauri 2 落地要点(零壳逻辑、静态嵌入 web 产物、NSIS)、CI 云打包通道取舍、CORS 放行任意 origin 的理由(静态 token 唯一防线,R-8 已接受;壳 origin 各平台不一,白名单徒增维护)。

**Blocked by:** 02(壳的可用性依赖运行时连接设置——打包产物里 token 只能经它配置;壳本身零逻辑)

**Status:** code-complete(待 push 后 CI windows job 绿 + artifact 人工烟测,随 04 记档转 done)

- [x] apps/desktop 就位:Tauri 2 默认模板 + 配置(指向 apps/web 构建产物、窗口、NSIS),无任何自定义 Rust 业务逻辑
- [ ] CI windows job 绿,产出 NSIS 安装包 artifact 并可下载(待 push 触发验证)
- [ ] artifact 安装包在 Windows 上安装成功,应用可启动、加载的 UI 与 web 端一致(人工烟测)
- [x] 打包 job 与 backend / web job 互不干扰(Playwright E2E 仍留在 ubuntu;打包 job 触发时机取 push-to-main + 手动,ADR-0006)
- [x] ADR-0006 落档(编号顺延现有 5 篇,内容见 What to build)
