# Spec: M3 AI 对话 —— 模型设置域 + S1 选中即问 + S2 当前书问答(SSE 流式)+ 删除书籍级联

Status: ready-for-agent
Created: 2026-08-29
Source: `docs/需求文档.md` v1.3(M3 章节、FR-104/301/302/303/304/401–405、D-25/27/28/31/32/33/37/42、§3.3 矩阵)+ `CONTEXT.md` 术语表 + M2 落地现状
Milestone: M3(AI 对话)

## Problem Statement

作为个人读者兼开发者,M2 之后我已经能在浏览器和桌面壳里读书、划线、接续进度,但"AI 阅读器"的 AI 一半都不存在:读书遇到看不懂的段落,只能切到别的窗口开一个通用 AI,把文字复制过去——书和讨论是割裂的。模型设置域至今是零:base URL、API key、chat 模型、上下文上限无处可填,配错了也无从发现;会话、消息、引用三个实体连表都没有,S1 选中即问与 S2 当前书问答只是需求文档里的两行字;S2 的上下文预算策略(整书 → 目标章 → 检索式降级、token 近似计数)是纯逻辑,尚未落地;此外删除书籍自 M1 起一直推迟("待 M3/M4 表齐后一并交付"),级联链的最后一环——该书会话——本里程碑表齐即可兑现。

## Solution

读着书就能问。阅读器里带一个 AI 面板:每本书有自己的会话列表,划一段文字直接"问 AI"(S1),提问自动落入该书最近活跃会话(无则新建),选中文字作为该条消息的引用;也可以对当前书直接提问(S2),后端按上下文预算装配 prompt——书内容优先占额,剩余装最近对话消息(装不下丢最旧、不做摘要),书内容塞不下按 整书 → 目标章 → 检索式 降级,单章超限给出明确文案优雅报错——回复以 SSE 流式逐字渲染。配套交付模型设置域:5 项 chat 配置 + 2 项可选 embedding 独立配置(空=跟随 chat),明文存库、明文回显,chat 与 embedding 分别"测试连接"(后端代理,配错当场发现)。同时兑现删除书籍:确认弹窗明示级联范围,书源文件、划线、进度、该书会话一并清除(向量级联 M4 在同一流程上补)。真实 LLM 的交互走人工验收;自动化中 LLM 一律以 OpenAI 兼容 stub 替身,CI 零新增外网依赖。

## User Stories

1. As a reader, I want 读书时划选一段文字即可"问 AI",提问自动落入该书最近活跃会话(无则新建)、选中文字作为该条消息的引用, so that 提问不打断阅读流,讨论留在书里(S1 / FR-301 / D-32)。
2. As a reader, I want 划选提问后回复逐字流式出现, so that 首字等待短,体验与主流 AI 产品一致(D-25 / FR-303)。
3. As a reader, I want 对当前书直接提问(如"总结第 5 章方法论"), so that 不必复制粘贴到别处再贴回来(S2)。
4. As a reader, I want 提问默认携带当前阅读位置所在章、显式点名章节时用点名的章, so that "这章"这类指代被正确理解(D-31)。
5. As a reader, I want 长书配小上下文模型时系统自动降级(整书装不下 → 装目标章), so that 便宜的小上下文模型也能用,而不是悄悄截断或报错(FR-302)。
6. As a reader, I want 单章都装不下时看到明确文案("上下文不足,请换大上下文模型或配置 embedding"), so that 我知道该换模型,而不是以为系统坏了(FR-302)。
7. As a reader, I want 预算不够丢最旧对话时,降级与断尾有可读说明, so that 我知道 AI 实际看到了什么(FR-302)。
8. As a reader, I want 每本书有独立的会话列表、点开可继续聊, so that 讨论按书组织,互不混杂(FR-301)。
9. As a reader, I want 重命名会话, so that 长会话可辨识(FR-304)。
10. As a reader, I want 删除会话, so that 无用的讨论可清理(FR-304)。
11. As a reader, I want 刷新页面/重开书后该书会话与消息原样还在, so that 讨论是持久的,不是一次性的。
12. As a reader, I want 消息能看到它引用的来源(选中文字/章节), so that 回答可回溯到原文(FR-301 引用)。
13. As a reader, I want 流式中途出错或中断时看到显式错误提示而不是永久转圈, so that 界面永不悬挂(FR-303)。
14. As a reader, I want 尚未配置模型设置就提问时得到引导去设置页的文案, so that 首次体验是被牵着走,不是面对报错。
15. As a reader, I want 在设置页填写 5 项 chat 配置(base URL、API key、chat 模型、上下文上限、embedding 模型可选), so that 任何 OpenAI 兼容服务都能接入(FR-401)。
16. As a reader, I want embedding 可再配独立 base URL 与 API key(留空则跟随 chat), so that chat 与 embedding 各用一家(如 DeepSeek + bge-m3)是原生支持的(D-28)。
17. As a reader, I want 上下文上限留空时按 8k 保守计算, so that 不填也不会把小上下文模型撑爆(D-27 / FR-302)。
18. As a reader, I want 设置页明文回显 API key, so that 个人工具所见即所存,改配一目了然(FR-404)。
19. As a reader, I want 测试连接分别探测 chat 与 embedding 并各自返回 ok 或可读错误, so that 配错 base URL 当场发现,不必等到提问(FR-405)。
20. As a reader, I want embedding 模型输入框有 bge-m3 的 placeholder 提示, so that 有参考建议但不被写死(R9-Q5)。
21. As a reader, I want 删除书籍时确认弹窗明示级联范围(书源文件、划线、进度、该书全部会话), so that 我清楚知道将失去什么(FR-104)。
22. As a reader, I want 删除后书籍从书库消失、相关数据全部清净, so that 书库不留尸块。
23. As a reader, I want 桌面端(壳内即 Web)拥有同样的 AI 对话与模型设置, so that 不用记哪个功能在哪个端(§3.3 矩阵)。
24. As a reader, I want 提问按字符近似计数控制预算(宁可高估提前降级), so that 不引 tokenizer 也有稳定可依赖的口径(D-37)。
25. As an API consumer, I want 读写单套模型设置(id 恒为 1,7 项字段,明文回显), so that 配置有单一事实源(FR-401 / D-27)。
26. As an API consumer, I want 一个测试连接端点返回 chat 与 embedding 两个探针各自的结果(embedding 未配置时明示跳过), so that 一次点击、两种判定(FR-405)。
27. As an API consumer, I want 拉取某书会话列表(按最近活跃排序、含标题与更新时间), so that S1 的"最近活跃会话"路由与端上列表共用同一口径。
28. As an API consumer, I want 拉取会话全部消息(含每条的 refs), so that 打开会话一次拿齐(全量口径,与划线 D-24 同精神)。
29. As an API consumer, I want 重命名(PATCH)与删除(DELETE)会话端点, so that FR-304 可达。
30. As an API consumer, I want 书级提问端点:content 必填,session_id 可选(缺省=该书最近活跃、无则新建),chapter_id/CFI 可选(目标章,缺省由前端填当前阅读位置),selection 可选(S1 选中文字),响应为 text/event-stream, so that S1 与 S2 走同一消息通路(D-31 / D-32 / D-25)。
31. As an API consumer, I want SSE 事件类型显式:开场元数据(落定的会话/消息标识)、增量文本、完成、错误, so that 前端不悬挂、可恢复上下文(FR-303)。
32. As an API consumer, I want 用户消息提问即落库、助手消息流结束落库(中断时已到内容照常落库), so that 对话记录完整、重开可见。
33. As an API consumer, I want DELETE 书籍端点执行完整级联(磁盘书源文件与封面、划线、进度、该书会话),且 book_id 为空的跨书会话不受影响, so that FR-104 与 D-33 的边界被严格执行。
34. As an API consumer, I want 所有新端点不带/带错 token 一律 401, so that 安全边界与 M0–M2 完全一致。
35. As an API consumer, I want 新端点的错误响应是可读中文文案, so that 前端能直接展示。
36. As a developer, I want 预算计算器是纯函数并用单测覆盖整书/整章/降级/断尾文案/空上限默认 8k/D-37 计数各边界, so that M3 验收标准原文("预算计算器纯函数单测绿")达成(FR-302)。
37. As a developer, I want LLM 接入仅实现 OpenAI 兼容协议并预留 Adapter 接口, so that 不为 Anthropic/Google 原生协议付实现成本(FR-402 / D-12)。
38. As a developer, I want 自动化测试里 LLM 一律以 OpenAI 兼容 stub 替身(后端集成测试本地 stub + E2E harness 内置流式 stub), so that 断言的是本系统行为、CI 零新增外网依赖。
39. As a developer, I want token 字符近似计数的落地取舍写进 ADR, so that 关键选型留档(D-37 / 毕业纪律)。
40. As a developer, I want chat_session / chat_message / model_settings 经 Flyway 迁移建表、删书级联经外键落地, so that schema 变更可回溯(D-17)。
41. As a developer, I want packages/api-client 扩展新端点类型与 SSE 消费能力, so that 前后端契约单一出处,桌面壳零改动自动继承。
42. As a developer, I want 新测试全部挂在既有 CI job 上(backend / web E2E), so that 反馈节奏不变、无新 job 维护面(D-23)。

## Implementation Decisions

- **范围口径**:本 spec 覆盖 M3(AI 对话:模型设置域 + S1 + S2 + 会话管理 + 删除书籍级联兑现)。S3/S4、embedding 嵌入与向量检索(M4)、安卓端(M5)、上云(M6)各出各的 spec。
- **模块划分**:
  - 后端(扩展)新增三个域:模型设置域(单套配置读写 + 测试连接代理)、会话/消息域(会话 CRUD、消息拉取、提问 SSE 端点)、LLM 接入(OpenAI 兼容 chat completions 客户端,SSE 流式中继,预留 Adapter 接口);预算计算器为独立纯函数模块。沿用既有分层与静态 token 拦截。
  - `apps/web`(扩展):模型设置页(7 项 + 测试连接 + 明文回显);阅读器侧边 AI 面板(会话列表、消息流、输入框、流式增量渲染);划选菜单增"问 AI"入口(复用 M1 已验证的拖选手势链路);删书入口与级联范围确认弹窗。
  - `packages/api-client`(扩展):新端点类型与方法、SSE 消费(fetch 流式解析);桌面壳零改动。
- **数据迁移**(第三个 Flyway 迁移):
  - `chat_session`:id、book_id(可空外键,ON DELETE CASCADE)、title、created_at、updated_at(服务器时钟,新消息/重命名时刷新——"最近活跃会话"的裁决字段,不反查消息表)。book_id 可空为 M4 跨书会话预留;级联删除天然不触及空 book_id 会话(D-33)。
  - `chat_message`:id、session_id(外键级联)、role、content、refs(jsonb:引用来源,首版含选中文字引用与章节引用,形状实现定)、created_at。
  - `model_settings`:单行(id 恒为 1,D-27),base_url、api_key、chat_model、chat_context_tokens(可空,空=8k 保守)、embedding_model(可空)、embedding_base_url(可空=跟随 chat)、embedding_api_key(可空=跟随 chat)、updated_at。
  - 删除书籍在同一流程清磁盘书源文件与封面(沿用既有文件存储组件),向量清理是 M4 在此流程上的唯一增量。
- **提问端点契约**:挂在书级;请求体含 content(必填)、session_id(可选,缺省=该书最近活跃会话,无则新建;自动会话标题取首条提问截断,可重命名)、chapter_id/CFI(可选,目标章;缺省由前端把当前阅读位置映射为目标章填入,显式点名章节用显式值,服务端不反查 reading_progress,D-31)、selection(可选,S1 的选中文字与 CFI,作为该条用户消息的引用落 refs)。响应 `text/event-stream`。**S1 与 S2 是同一端点的同一通路**(D-32):带 selection 即 S1——预算函数的"书内容"槽 = 选中文字(已与用户确认),剩余额度照常装最近对话;不带 selection 即 S2——书内容槽按降级链装配。
- **预算计算器(纯函数,可单测)**:输入 = 上下文上限(空按 8000)、书内容候选(整书 token 数 / 目标章 token 数 / 选中文字 token 数)、最近消息序列(各自 token 数,新→旧)、检索式可用标志(M3 恒 false,M4 翻真);输出 = 装配计划:模式(整书 / 目标章 / 选中文字 / 检索式 / 错误)、纳入的书内容、保留的消息前缀(装不下丢最旧、**不做摘要**),以及降级、断尾、上下文不足三类可读文案。检索式分支在 M3 不可达:单章超限且检索式不可用 → 错误文案"上下文不足,请换大上下文模型或配置 embedding",优雅报错不炸。token 计数按 D-37 字符近似:中文 ≈ 1 token/字、英文 ≈ 1 token/4 字符、向上取整、宁可高估提前降级;逐章计数从章节正文实算。
- **SSE 契约**(FR-303 / D-25):事件类型显式下发——至少:开场元数据(落定的会话与消息标识,前端据此路由与恢复)、增量文本、完成、错误。上游 LLM 超时、断流、非 2xx 一律转错误事件收尾,前端不悬挂。用户消息在提问受理时即落库;助手消息在流结束后落库,中断时已收到的内容照常落库(可读优先,v1 不做完成度标记)。事件字段命名实现定,验收看行为。
- **LLM 接入**:仅 OpenAI 兼容协议(chat completions,stream=true),SSE 中继经 Spring MVC 异步响应;预留 Adapter 接口,不做 Anthropic/Google 原生协议(FR-402)。模型未配置时提问返回引导文案错误。
- **测试连接**(FR-405):后端代理(端上不直连 LLM);chat 与 embedding 两个探针分别向各自生效的 base_url(embedding 空则跟随 chat)发最小请求(如 GET /models),各自返回 ok / 可读中文错误(连接失败、超时、401、404 分类文案);embedding 未配置时该探针返回明示跳过。
- **模型设置语义**:v1 单套(id 恒为 1,D-27),不做多 profile;上下文上限空 = 按 8k 保守;embedding_base_url/api_key 空 = 跟随 chat(D-28);API key 明文存库、明文回显(FR-404 已接受,R-2 同姿态);bge-m3 仅作 placeholder 提示不写死;保存不做连通性强制校验(测试连接是显式动作)。
- **删书**(FR-104,纳入本里程碑,已与用户确认):DELETE 书籍端点;前端确认弹窗明示级联范围(书源文件/封面、划线、进度、该书全部会话);级联经外键(划线/进度 M1 已建,会话本次)+ 磁盘文件清理;跨书(book_id 空)会话不受影响;向量级联为 M4 在同一删除流程上的增量。
- **自动化替身策略**:测试不打真 LLM。后端集成测试(Seam A)以本地 OpenAI 兼容 stub(如 MockWebServer)作为上游,经 model_settings 指向;E2E(Seam B)在 harness 内起一个本地流式 stub LLM 服务(多块 SSE 响应);真实 LLM 交互(4k + 长书降级、真实网络错误、真实流式体验)走人工验收。CI 零新增外网依赖。
- **ADR**:token 字符近似计数(D-37)必录一篇;LLM 客户端/SSE 中继若在实现中出现关键取舍,顺延编号补记。
- **CORS 与鉴权**:沿用 M2 全局 CORS 与静态 token 拦截,新端点一律受 401 防线保护;无新运维面。

## Testing Decisions

- **好测试的标准**(沿用 M1/M2):只断言外部可观察行为——HTTP 状态码、响应体(含错误文案与 SSE 事件序列)、数据库行、浏览器里可见的结果与持久化效果;不 mock 被测边界内的模块(LLM 客户端、预算计算器、ORM 都在边界内),不测实现细节;唯一被替身的是系统边界外的 LLM 服务,且替身打在网络层(OpenAI 兼容 stub)。
- **纯函数单测(唯一新 seam,最窄,已与用户确认)**:预算计算器 + D-37 字符近似计数,普通 JUnit 单测、无 Spring 上下文。覆盖边界:整书装下 / 整书超限降目标章 / 单章超限且无检索式 → 错误文案 / 消息断尾丢最旧 / 上限空默认 8k / 中英混合文本计数 / 选中文字槽(S1)。M3 验收标准原文要求,先于端点测试写。
- **Seam A(既有扩展,已与用户确认)**:后端 HTTP × Testcontainers + 本地 OpenAI 兼容 stub 上游,覆盖:模型设置读写与明文回显;测试连接(ok / 连接失败 / 401 / 404 各文案 / embedding 未配置跳过);会话列表(最近活跃排序)、消息拉取、重命名、删除;提问端点——S1 落最近活跃会话、无则新建、指定 session_id 三种路由,用户/助手消息落库,refs 落库;SSE 事件序列(增量→完成 / 上游错误→错误事件 / 上游超时→不悬挂);发给上游 stub 的 prompt 形状抽查(选中文字进 prompt、整书超限时 prompt 实为目标章)作为预算行为的兜底断言;未配置模型设置提问 → 引导文案;删书完整级联(文件、划线、进度、该书会话全清;跨书空 book_id 会话不动);新端点 401。**Prior art**:M0–M2 集成测试类与 IntegrationTestBase(容器与 storage 按 JVM 单例复用、每测试前清库)、CorsIntegrationTest。
- **Seam B(既有扩展,已与用户确认)**:Playwright 全栈 E2E,harness 增本地流式 stub LLM(多块 SSE):经设置页 UI 配置指向 stub → 划选提问(复用 M1 已验证的拖选手势)→ 回复流式增量渲染 → 会话与消息落库(resetBackend 直连断言)→ 刷新/重开仍在 → 重命名/删除会话 → S2 提问默认携带当前章 → 删书确认弹窗与级联生效。**Prior art**:M1 highlights / acceptance 用例与 helpers(resetBackend、拖选手势、shadow DOM 断言缝)。
- **真实 LLM:人工验收清单**,对应 M3 验收后两条:4k 上下文配置 + 长书人工验证降级与优雅报错;配错 base URL → 测试连接给出可读错误;真实流式体验。
- **packages/api-client**:无独立单测(沿用既有口径,经 Seam A 契约与 Seam B 链路间接覆盖);桌面壳无自动化(零逻辑,人工烟测)。

## Out of Scope

- S3 定位原文、S4 跨书对比、embedding 嵌入任务、向量块、检索式降级的实际执行(M4;预算函数只留分支标志与输入参数)。
- 跨书会话创建入口、S4 会话入口与检索范围(M4 前专盘,§9 待定项);refs"原书已删除"降级渲染(M3 会话均挂书,无跨书悬空场景)。
- 多模型 profile(v2)、划线作为 AI 上下文输入(D-42,v2 候选)、对话摘要(FR-302 明确不做)。
- Anthropic / Google 原生协议适配(仅留 Adapter 接口,FR-402)。
- 安卓端 AI 对话与模型设置(§3.3 矩阵:安卓 v2)及安卓端一切(M5)。
- 上云、HTTPS、API key 加密存储(明文已接受,FR-404 / R-2)。
- tokenizer 精确计数(D-37 已定字符近似,M3 ADR 录档后不再议)。
- 桌面壳特有增强(沿用 M2 spec 口径)。

## Further Notes

- **M3 验收标准原文映射**:"划选提问流式返回" → Seam B E2E(stub 流式)+ 人工(真实流式);"预算计算器纯函数单测绿(整书/整章/降级/断尾文案各边界,含 D-37 近似计数)" → 新纯函数 seam;"4k 配置 + 长书人工验证降级与优雅报错" → 人工验收清单;"配错 base URL → 测试连接给出可读错误" → Seam A(stub 错误形态)+ 人工(真实网络错误)。
- **R-1 提示**:需求文档风险表要求"M3 起 API key 入库,届时重评"——本 spec 沿用 FR-404/R-2 已接受姿态(单用户、明文、token 兜底),M6 上云前再评一次;此处记档视为已重评。
- "最近活跃会话"裁决口径 = chat_session.updated_at(服务器时钟,新消息/重命名刷新),与 LWW 时钟纪律(D-19)同源。
- M4 预埋清单:预算函数的检索式分支标志、refs jsonb 形状、chat_session.book_id 可空、删书流程的向量清理挂点——M4 spec 在此之上续写,不动 M3 交付面。
- 流式部分回复的留存口径(中断时已到内容落库)是 v1 决定,若实际体验发现半截回复误导,再议补完成度标记;不在本里程碑。
