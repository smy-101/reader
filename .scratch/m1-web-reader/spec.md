# Spec: M1 网页阅读器 —— apps/web 阅读、进度、划线 + 同步 API

Status: done(8/8 issue 交付;CI push 后验证)
Created: 2026-08-28
Source: `docs/需求文档.md` v1.3(M1 章节、FR-103/106/201/202/203、D-18/19/24/30/40/43/44)+ `CONTEXT.md` 术语表 + M0 落地现状
Milestone: M1(网页阅读器)

## Problem Statement

作为个人读者兼开发者,M0 之后书已经能进书库(curl 上传、查元数据、看章节列表),但我依然读不了书:仓库里没有任何页面,阅读、目录、划线、进度全部只存在于需求文档里。全项目最高风险件——foliate-js 浏览器渲染(R-6,无 npm 包、文档少)——至今未被验证,它每拖一天,建在它之上的进度与划线就越可能是空中楼阁。同时,划线与阅读进度连后端 API 都没有:highlight 与 reading_progress 两表尚未建,书库列表里的进度百分比还是 M0 留下的空占位。

## Solution

一个能日常读书的浏览器应用:打开书库看到封面/标题/作者/进度,拖一批 EPUB 进去批量导入;点开一本书,foliate-js 在浏览器里渲染正文,目录导航、字号/主题随手调;选中文字即可划线(颜色/备注),翻到哪进度自动记到哪;刷新页面、换一台设备,打开同一本书,进度与划线原样接续。前置一道 48 小时 foliate-js spike(渲染 + 选中取 CFI 两硬点),把最高风险件最先钉死;配套后端补齐书源文件下载、划线 CRUD、进度 upsert 与全量拉取(D-24),LWW 由服务器统一时钟裁决(D-19)。M1 验收原文"浏览器读书,刷新/换设备进度划线不丢"直接落成自动化 E2E 用例。

## User Stories

1. As a reader, I want 打开网页看到书库列表(封面、标题、作者、阅读进度百分比), so that 我一眼掌握藏书与每本书读到哪了(FR-103)。
2. As a reader, I want 书库列表按标题/作者即时过滤, so that 几百本书里立刻找到想读的那本(FR-106)。
3. As a reader, I want 在网页上传一本 EPUB 后它立即出现在书库, so that 上架即读,无需别的工具。
4. As a reader, I want 一次多选多本 EPUB 批量上传并看到逐本结果, so that 首次导入藏书不用一本本点(D-43)。
5. As a reader, I want 上传重复书籍时得到"已在书库"提示并跳到原书, so that 书库不出现重复条目、批量导入不被打断(D-30)。
6. As a reader, I want 上传损坏/疑似 DRM 的书时看到可读的错误文案, so that 我知道是文件的问题而不是系统坏了(M0 文案在网页透出)。
7. As a reader, I want 在浏览器里渲染并翻页阅读 EPUB, so that 不装任何阅读软件也能读书(foliate-js,D-18)。
8. As a reader, I want 点击目录条目跳到对应位置, so that 长书里快速定位(FR-201)。
9. As a reader, I want 调整字号与主题(亮/暗等)且下次打开还记得, so that 阅读舒适;这是端上偏好,只存本地,不跨端同步(FR-201)。
10. As a reader, I want 从上次读到的地方接续阅读, so that 换一天、换设备都不用自己找位置(FR-203)。
11. As a reader, I want 选中一段文字即可创建划线高亮, so that 重点内容在书里直观可见(FR-202)。
12. As a reader, I want 给划线选择颜色, so that 不同用途的重点可以区分。
13. As a reader, I want 给划线添加/修改备注, so that 当下的想法跟原文绑在一起。
14. As a reader, I want 删除某条划线, so that 误划与不再需要的高亮可以清理。
15. As a reader, I want 重新打开书时看到该书全部划线(全量拉取), so that 高亮永随书走(D-24)。
16. As a reader, I want 刷新页面后进度与划线不丢, so that 误关页面零代价(M1 验收原文)。
17. As a reader, I want 换设备/浏览器后进度与划线不丢, so that 桌面读一半,别处接着读(M1 验收原文)。
18. As a reader, I want 阅读进度自动上报而无需手动保存, so that 接续是无感的(FR-203)。
19. As an API consumer, I want 通过带 token 的请求拉取某书的完整 EPUB 原文件, so that 渲染引擎在浏览器拿到书源文件。
20. As an API consumer, I want 拉取某书全部划线(一次全量), so that 打开书一次拿齐(D-24)。
21. As an API consumer, I want 创建划线时提交 CFI、选中文字、颜色、备注与设备标识, so that 高亮可定位、可展示、可追溯来源。
22. As an API consumer, I want 单条更新划线(颜色/备注), so that 修改即同步到服务端。
23. As an API consumer, I want 删除划线, so that 端上与服务端保持一致。
24. As an API consumer, I want 读取某书单行进度(CFI + 百分比), so that 打开书即恢复位置。
25. As an API consumer, I want 单条 upsert 进度, so that 每次上报不需要先读后写。
26. As an API consumer, I want 冲突时后写胜(LWW)且由服务器时钟裁决, so that 双端交替使用不需要任何合并逻辑(D-19)。
27. As an API consumer, I want 书库列表接口返回每本书的进度百分比, so that 列表直接可渲染(M0 占位转实,FR-103)。
28. As an API consumer, I want 不带/带错 token 访问所有新端点一律 401, so that 安全边界与 M0 一致。
29. As an API consumer, I want 新端点的错误响应是可读文案, so that 前端能直接展示。
30. As a developer, I want 先用 48 小时 spike 验证 foliate-js 渲染与选中取 CFI 两个硬点, so that 全项目最高风险件最先定生死,不过则退 epub.js(D-18/R-6)。
31. As a developer, I want foliate-js 以 vendor 源码入库方式引入, so that 不依赖不存在的 npm 包、版本完全可控(ADR-0004)。
32. As a developer, I want apps/web 以 React + TypeScript 起步, so that 与既定架构(D-7)一致并复用我的 TS 经验。
33. As a developer, I want packages/api-client 承载共享 TS 类型与 API client(token 注入), so that 前端与后端契约有单一出处,后续桌面/安卓壳复用。
34. As a developer, I want highlight 与 reading_progress 经 Flyway 迁移建表, so that schema 变更始终可回溯(D-17)。
35. As a developer, I want 目录由前端从 EPUB 原文件渲染、不入库, so that 章节表口径与 D-40 保持一致。
36. As a developer, I want CI 为 apps/web 增加独立 job 跑 E2E, so that 前端从第一天就有回归防线(D-23)。
37. As a developer, I want E2E 用真实后端 + Testcontainers PG + 真 EPUB fixture, so that "刷新/换设备不丢"是被自动验证的行为而非口头承诺。
38. As a developer, I want 双端同开时接受"重开该书才可见"另一端变化, so that M1 不引入推送/轮询复杂度(D-44)。

## Implementation Decisions

- **范围口径**:本 spec 只覆盖 M1(网页阅读器)。M2+(桌面壳、AI 对话、向量检索、安卓端、上云)各出各的 spec。
- **前置 spike(全项目最高风险件最先)**:48 小时时间盒,验证两硬点——(a)foliate-js 在现代浏览器渲染 EPUB;(b)选中文字取出 EPUB CFI。产出简短 spike 结论(过/不过 + 关键坑位清单)。不过 → 切换 epub.js,其余架构不变,损失锁定在 48h(D-18);若切换,修订 ADR-0004。**不为"双引擎"预设渲染抽象层**——单引擎,不过就整引擎换,不提前付抽象税。
- **模块划分**:
  - `apps/web`(新):React + TypeScript(Vite 工程);渲染引擎 foliate-js 以 vendor 源码入库(D-18/ADR-0004)。
  - `packages/api-client`(新):共享 TS 类型 + API client;token 经配置注入,请求统一带 Bearer;M1 只覆盖书库/上传/划线/进度所需端点。
  - 后端(扩展):沿用既有分层与静态 token 拦截,新增端点与两张表。
- **后端新端点**(均受既有 token 拦截):
  - **书源文件下载**:按书籍 id 流式返回 EPUB 原文件。渲染引擎需要完整文件;下载走带 token 的程序化请求,**不以 URL 查询参数传 token**。
  - **划线**:按书全量拉取;创建(CFI、选中文字、颜色、备注、设备标识);单条更新(颜色/备注);单条删除。
  - **进度**:按书单行读取;单条 upsert(CFI + 百分比)。CFI 与百分比均由前端(foliate-js)产出、服务端原样存储。
  - **书库列表补齐进度百分比**(M0 占位转实,FR-103)。
- **数据迁移**(第二个 Flyway 迁移):`highlight`(book_id 外键、cfi、text、note、color、device、created_at、updated_at)与 `reading_progress`(book_id 唯一外键、cfi、percent、updated_at)。两表外键均按级联删除建,为 FR-104 的完整级联(待 M3/M4 表齐后一并交付)预留。**updated_at 一律服务器时钟(D-19),客户端不传时间戳**;device 为端上自报标识(本地生成持久化),仅展示/追溯用,不参与裁决。
- **同步策略**(D-24/D-19/D-44):打开书一次全量拉取该书全部划线 + 单行进度;上报一律单条 upsert/CRUD,不做增量协议;划线/进度整行 LWW 后写胜,无合并 UI;双端同开接受"重开该书才可见"(无推送/轮询)。
- **划线定位**:以 EPUB CFI 为准(foliate-js 产出);选中文字作为 text 快照存储,用于列表展示与 CFI 失效时的降级文案。
- **目录导航**:前端用 foliate-js 直接从 EPUB 原文件解析并渲染嵌套目录,**不调用后端、不入库**(嵌套目录只是导航视图,D-40);后端章节列表继续服务未来 AI 用途。
- **阅读设置**:字号/主题仅存 localStorage,不进后端、不跨端同步(FR-201)。
- **批量上传**(D-43):前端多选后循环调既有单文件上传接口,后端零改动;逐本展示结果,重复书籍按幂等(D-30)提示"已在书库"并继续下一本。
- **书库过滤**(FR-106):纯前端按标题/作者即时过滤,几百本量级不做服务端搜索。
- **进度上报节奏**:前端节流(如翻页/滚动稳定后上报一次),后端纯 upsert 不做节流与合并。
- **CI**(D-23):backend job 不动;新增 apps/web job 跑 E2E(内含后端拉起 + Testcontainers PG);无 CD。E2E 编排细节(如何拉起后端与依赖)实现时定,验收只看 job 绿。

## Testing Decisions

- **好测试的标准**:只断言外部可观察行为——HTTP 状态码、响应体(含错误文案)、数据库行、浏览器里可见的结果与持久化效果;不 mock 被测边界内的模块(渲染引擎、ORM、迁移都不在边界之外),不测实现细节。
- **Seam A(沿用既有,零新增)**:后端 HTTP API × Testcontainers。划线 CRUD、进度 upsert/单行读、全量拉取、LWW 后写胜(两次写断言后行胜)、文件下载、列表进度百分比、新端点 401 全部在此覆盖。**Prior art**:M0 六个集成测试类与 IntegrationTestBase(容器与 storage 目录按 JVM 单例复用、每测试前清库),直接扩展。
- **Seam B(唯一新 seam,已与用户确认)**:Playwright 全栈 E2E——真实浏览器 × 真实后端进程 × Testcontainers PG × 真 EPUB fixture,一条链路贯穿 **上传 → 书库列表 → 打开 → 渲染 → 目录跳转 → 选中划线 → 刷新 → 进度划线不丢 → 第二个浏览器上下文(=换设备)→ 接续不丢**。这条用例就是 M1 验收标准的原文。**不**为 apps/web 另建组件测试 seam;packages/api-client 无独立单测,经 Seam A 契约与 Seam B 链路间接覆盖。
- **fixture 策略**:复用 M0 集成测试的 EPUB fixtures(正常书/损坏/DRM);E2E 需要一本多章、带目录结构的稍大 fixture,随用例构造入库。
- **选中手势自动化**:若 foliate-js 的事件/选区模型可被 Playwright 稳定驱动,则"选中→划线"全程自动化;若 spike 证明手势模拟不可靠,E2E 以程序化创建划线验证渲染接续与同步,选中手势留人工验收清单。由 spike 结论定,不提前假设。
- **foliate-js 两硬点**由 spike 人工验证兜底(时间盒 48h),不追求渲染引擎本身的单测覆盖。

## Out of Scope

- **删除书籍及其级联**(FR-104):级联的完整形态(该书会话、向量)依赖 M3/M4 的表,届时一并交付;本里程碑已通过外键级联预留。
- Tauri 桌面壳(M2)、AI 对话四场景与模型设置(M3)、embedding/向量检索(M4)、安卓端(M5)、上云(M6)。
- 离线划线队列、同步冲突的人机合并界面、阅读设置跨端同步、双端同开实时可见(D-44 已接受"重开才可见")。
- 书内全文搜索、划线/笔记导出(v2 候选);TXT/PDF 格式。
- 划线作为 AI 上下文输入(D-42,v2 候选)。
- 安卓端本地副本/文件缓存逻辑(M5 域)。

## Further Notes

- **最高风险前置**:spike 是 M1 第一张 issue,时间盒硬顶 48h;结论直接决定渲染引擎与 E2E 手势策略,其余 issue 均视其结论开工。
- M1 验收标准("浏览器读书,刷新/换设备进度划线不丢")即 Seam B 的核心 E2E 用例,验收即测试、测试即验收,无隐藏验收项。
- 若 spike 触发引擎切换(foliate-js → epub.js),修订 ADR-0004 并在本 spec 追加变更记录;进度上报节流参数等实现细节不进 ADR。
- 后端连接、鉴权、存储目录口径全部沿用 M0,无新增运维面。
