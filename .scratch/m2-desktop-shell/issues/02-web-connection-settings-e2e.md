# 02: web 运行时连接设置 + "桌面等效连接" E2E

**What to build:** apps/web 新增"连接设置"运行时配置:后端绝对地址 + token,localStorage 持久化,含"测试连接"按钮(调既有书库列表端点,返回 ok / 可读错误;后端零新增端点)。已配置时 api-client 以运行时配置优先(绝对 baseUrl + token);未配置时完全回退 M1 现状(同源 + 构建期注入 token)——对既有开发流与 E2E 是纯叠加,零回归。书库拉取失败(后端不可达 / token 错)时给出可读中文错误并引导进连接设置。E2E(Seam B)新增"桌面等效连接"用例:经连接设置 UI 配置真实后端的绝对 URL + 测试 token,走通 列表→打开→划线/进度 链路——页面 origin 与后端端口不同且携带 Authorization 头,天然触发跨域 preflight,这正是 Tauri 壳将执行的代码路径。

**Blocked by:** 01(跨域绝对 URL 的 E2E 依赖后端 CORS 放行)

**Status:** ready-for-agent

- [ ] 连接设置 UI:后端 URL + token 可填写、保存后 localStorage 持久化、重载页面仍在
- [ ] 测试连接:正确配置 → ok;错 token → 401 可读文案;不可达 URL → 可读错误提示,设置页不崩
- [ ] 已配置时一切 API 走绝对 baseUrl + 运行时 token;未配置时回退现状,既有 E2E 用例零改动全绿
- [ ] 书库拉取失败时 UI 显示可读错误并提供连接设置入口(首次体验被引导而非白屏)
- [ ] E2E"桌面等效连接"用例:经 UI 配置绝对 URL + 正确 token → 列表→打开→划线/进度链路走通且落库(经后端可验证)
- [ ] token 不进 URL、不进日志,维持仅经 Authorization 头(D-4 口径)
