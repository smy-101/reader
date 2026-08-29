# Spec: M4 向量检索 —— embedding 嵌入任务(自动 + 手动重嵌入)+ 切块入库 + S3 定位原文与跳转 + S2 检索式降级兑现

Status: ready-for-agent
Created: 2026-08-29
Source: `docs/需求文档.md` v1.3(M4 章节、FR-302(检索式降级)/FR-104(向量级联)/FR-403、§6.4 数据模型、§6.5 切块策略、D-28/36、§9 待定项)+ `CONTEXT.md` 术语表 + M3 落地现状
Milestone: M4(向量检索;S4 跨书对比**不在本 spec**,S3 落地后另出小 spec——已与用户确认)

## Problem Statement

作为个人读者兼开发者,M3 之后读书已经能问 AI 了,但 AI 只能"看到"上下文预算装得下的那部分书:整书装不下降级到目标章,单章都装不下就只剩一句"上下文不足,请换大上下文模型或配置 embedding"——降级链的最后一环(检索式)是空的。更要命的是"作者在哪讨论过 X?"这类定位式问题根本没有通路:现在的问答只能凭装进 prompt 的片段作答,既给不出可点击的原文位置,也常常答非所问。设置页里的 embedding 三项配置从 M3 起就能填、还能测试连接,但它是个"死配置"——没有任何东西真正用它:嵌入任务不存在、向量块不存在、检索不存在;上传的书不会进向量库,也无处可见嵌入状态;换 embedding 模型后更是无从重嵌。

## Solution

配置好 embedding 之后,书一上传就在后台自动切块、嵌入入库(嵌入任务有状态与进度,失败可重试);对嵌入完成的书,在 AI 面板用"定位原文"直接问"作者在哪讨论过 X"(S3)——问题经 embedding 检索该书最相关的向量块,回复照常流式渲染,并携带可点击的原文引用(章节 + 摘录),点击跳转到阅读器对应章节并尽量定位到摘录文字。同时把 S2 降级链补上最后一环:整书、单章都装不下时不再报错,而是降级为检索式上下文(检索最相关块装配 prompt),降级说明如实告知。未配置 embedding 时一切相关入口隐藏(FR-403);在配置 embedding 之前上传的存量书可手动触发首次嵌入;换 embedding 模型可一键全量重嵌入。S4(跨书对比)待 S3 落地后按实际体验专盘另出小 spec。自动化中 embedding 与 chat 一律以 OpenAI 兼容 stub 替身、向量确定性,CI 零新增外网依赖;真实 embedding(bge-m3)走人工验收。

## User Stories

1. As a reader, I want 配置好 embedding 后上传的书自动在后台切块嵌入, so that 我什么都不用做,S3 与检索式降级天然可用。
2. As a reader, I want 嵌入过程中看到进度(已完成块数 / 总块数), so that 知道后台在干活、心里有数。
3. As a reader, I want 嵌入失败时看到可读错误与"重试"入口, so that 一次网络抖动不至于让这本书永远无法检索。
4. As a reader, I want 未配置 embedding 时看不到任何嵌入与 S3 入口, so that 功能优雅隐藏而不是点了报错(FR-403)。
5. As a reader, I want 对嵌入完成的书直接问"作者在哪讨论过 X", so that 得到有出处的答案而不是凭上下文片段瞎猜(S3)。
6. As a reader, I want S3 的回复携带可点击的原文引用(章节标题 + 原文摘录), so that 答案可回溯、可核验。
7. As a reader, I want 点击引用跳转到阅读器对应章节并尽量定位到摘录文字, so that 从答案一步回到原文。
8. As a reader, I want S3 回复同样流式渲染, so that 体验与 S1/S2 完全一致(FR-303 / D-25)。
9. As a reader, I want S2 长书小预算时降级链走到底:整书装不下 → 目标章 → 检索式, so that 不再存在"上下文不足"的死角(FR-302)。
10. As a reader, I want 检索式降级时知道 AI 实际看到的是检索片段, so that 理解答案的边界(FR-302 可读说明)。
11. As a reader, I want 已配置 embedding 但书还没嵌入完成时,提问文案引导"等待嵌入完成或触发嵌入", so that 知道该等而不是误以为要换模型。
12. As a reader, I want 换 embedding 模型后能一键全量重嵌入, so that 换模型不用删书重传(R-3 处置)。
13. As a reader, I want 换模型后、重嵌入完成前,S3 明确提示"模型已更换,需重新嵌入", so that 不会拿到维度错配的坏结果。
14. As a reader, I want 重嵌入过程中进度同样可见, so that 知道什么时候能继续用 S3。
15. As a reader, I want 在配置 embedding 之前上传的存量书也能手动触发首次嵌入, so that 老书同样可检索。
16. As a reader, I want 嵌入在后台跑、上传立即返回, so that 上传体验不变(同步解析口径 D-41 不受影响)。
17. As a reader, I want 嵌入失败不影响上传成功与正常阅读, so that 嵌入是增益不是门槛。
18. As a reader, I want 删除书时向量块与嵌入任务一并清除, so that 书库不留尸块(FR-104 级联最后一环)。
19. As a reader, I want S3 提问也落入该书最近活跃会话(无则新建), so that 定位式讨论与其他讨论同处一处(D-32 精神)。
20. As a reader, I want S3 提问中途出错时看到显式错误而不是永久转圈, so that 界面永不悬挂(FR-303)。
21. As a reader, I want 桌面端(壳内即 Web)拥有同样的嵌入状态与 S3 能力, so that 不用记哪个功能在哪个端(§3.3 矩阵)。
22. As a reader, I want 同一本书不会有两个嵌入任务并发跑, so that 状态与数据不自相矛盾。
23. As an API consumer, I want 查询某书嵌入状态(状态 / 进度 / 所用模型 / 错误), so that 书库与详情页有单一事实源。
24. As an API consumer, I want 一个触发端点统一覆盖首次嵌入 / 失败重试 / 换模型全量重嵌入, so that 语义简单、多态一入口。
25. As an API consumer, I want 上传时若已配置 embedding 自动创建任务, so that 端上不需要记得再调一次触发。
26. As an API consumer, I want 同模型且已完成时触发端点幂等返回当前状态, so that 重复点击无害。
27. As an API consumer, I want 书级提问端点支持显式检索式提问(S3),前置不满足时返回可读错误(未配置 / 嵌入中 / 失败未重试 / 模型已更换各态文案), so that 前端能给出准确引导。
28. As an API consumer, I want 检索引用随 SSE 开场元数据事件下发并落库到助手消息 refs, so that 流式开始前引用已可渲染、刷新后仍在。
29. As an API consumer, I want S2 预算降级自动进检索式时 prompt 装配的是检索块, so that 降级链真正兑现(经 stub 断言)。
30. As an API consumer, I want embeddings 调用走 embedding 独立 base_url/api_key(空=跟随 chat), so that chat 与 embedding 各用一家原生支持(D-28)。
31. As an API consumer, I want embeddings 请求分批有界, so that 大书不会一次打爆上游。
32. As an API consumer, I want DELETE 书籍端点级联清向量块与嵌入任务, so that FR-104 的向量环节补齐。
33. As an API consumer, I want 新端点不带/带错 token 一律 401, so that 安全边界与 M0–M3 完全一致。
34. As an API consumer, I want 新端点错误响应是可读中文文案, so that 前端能直接展示。
35. As a developer, I want 切块器是纯函数并单测覆盖目标块长 / 段落与中文标点断点 / 超长段强切 / 空章 / 多章独立 / token 计数口径, so that 切块行为确定性钉死(§6.5)。
36. As a developer, I want 向量维度与检索策略(无 typmod 列 + 个人量级顺序 cosine 扫描、ANN 索引延后)录 ADR, so that 关键选型留档(毕业纪律)。
37. As a developer, I want 向量块与嵌入任务两表经 Flyway 迁移建表、删书级联经外键落地, so that schema 变更可回溯(D-17)。
38. As a developer, I want embeddings 客户端挂在既有 OpenAI 兼容接入上(同一 Adapter 口径), so that 协议面不发散(FR-402 / D-12)。
39. As a developer, I want 自动化里 embedding 一律以 OpenAI 兼容 stub 替身且向量确定性(关键词 → 维度映射), so that 检索排序可断言、CI 零新增外网依赖。
40. As a developer, I want 嵌入任务后台单线程队列执行、同书串行, so that 无并发竞态、个人量级足够。
41. As a developer, I want 失败重试与换模型重嵌入都从头重跑(先清该书旧块), so that 幂等简单,不做断点续传。
42. As a developer, I want packages/api-client 扩展嵌入状态 / 触发端点类型与 refs 新形状, so that 前后端契约单一出处,桌面壳零改动。
43. As a developer, I want 新测试全部挂既有 CI job(backend / web E2E), so that 反馈节奏不变、无新 job 维护面(D-23)。

## Implementation Decisions

- **范围口径**:本 spec 覆盖 M4 主体 = embedding 嵌入基建(自动 + 手动重嵌入)+ 切块入库 + S3 定位原文与跳转 + S2 检索式降级翻真 + 删书向量级联。**S4 跨书对比不在本 spec**(跨书会话创建入口、检索范围等 §9 待定项,S3 落地后按实际体验专盘另出小 spec——已与用户确认);安卓(M5)、上云(M6)各出各的 spec。
- **模块划分**:
  - 后端新增**嵌入域**:切块器(纯函数,无 Spring)、embeddings 客户端(扩展现有 OpenAI 兼容接入:POST {生效 base_url}/embeddings,分批有界,embedding 独立 base_url/api_key 空=跟随 chat,D-28)、嵌入任务编排(后台异步、状态机、重试)、向量检索查询(cosine 距离、按书过滤、top-k,k 与引用摘录长度实现定)。沿用既有分层与静态 token 拦截。
  - `apps/web`(扩展):书嵌入状态卡(状态 / 进度 / 错误 / 触发与重试入口,未配置 embedding 时整体隐藏);AI 面板"定位原文"提问入口(该书嵌入完成才显示,FR-403);回复引用条渲染与点击跳转(复用 M3 会话/消息通路与 M1 阅读器跳转能力)。
  - `packages/api-client`(扩展):嵌入状态 / 触发端点类型、检索引用 refs 形状;桌面壳零改动。
- **数据迁移**(第五个 Flyway 迁移):
  - `document_chunk`(向量块):id、book_id(外键 ON DELETE CASCADE)、chapter_id(外键级联)、seq(书内章节正文顺序)、content、token_count、embedding(**vector 不带 typmod**,维度随嵌入模型)、created_at。表内 book_id 过滤检索是主路径。
  - `embedding_job`(嵌入任务):id、book_id(外键级联)、model(嵌入所用模型)、status(pending/running/done/failed)、chunk_done、chunk_total、error、created_at、updated_at(服务器时钟,D-19 同源)。表内可存历史,对外每书读最新一条。
- **维度与检索策略(R-3 落地,必录 ADR)**:向量列不带 typmod——同一本书全部块由同一模型一次任务嵌入,维度天然一致;查询向量与块向量同模型由 S3 前置校验保证(不同维度比较在 PG 层天然报错,不额外防御)。个人量级(几百本、几万块)顺序 cosine 扫描即达 P95 < 500ms(§5.1),**HNSW/IVFFlat 索引不进本里程碑**;换模型 = 清块全量重嵌,量级可忽略。检索距离用 cosine。
- **切块策略(§6.5 实现定稿)**:按章节正文(`chapter.content`,D-26/D-40 已入库的纯文本)切块;段落优先、中文标点感知断点,目标约 500 字/块;超长无断点段强切;空章/纯空白章跳过;块 token_count 用 TokenEstimator 口径(D-37,与预算同源)。参数若出现关键取舍顺延编号补记 ADR。
- **嵌入任务编排**:上传成功入库后(同步解析返回口径不变,D-41),若 model_settings 已配 embedding → 创建 job 并交后台**单线程队列**异步执行(同书串行,无并发竞态);嵌入失败不影响上传结果。执行 = 切块 → 分批调 embeddings → 批量入库 → 增量更新 chunk_done → done;上游失败/超时 → failed + 可读中文 error。**失败重试与换模型重嵌入都从头重跑**(先清该书旧块再嵌,幂等),不做断点续传。
- **触发端点语义(一入口多态)**:对书触发嵌入——未嵌入 → 首次嵌入(覆盖存量书);failed → 重试;done 且模型未变 → 幂等返回当前状态;done 但模型已变 → 全量重嵌入(清旧块新任务)。并发触发被任务串行语义吸收。
- **S3 端点契约**:复用 M3 书级提问端点(S1/S2/S3 同一通路,D-32 精神),新增可选**显式检索式提问标志**;请求/响应契约其余不变(SSE:开场元数据 → 增量 → 完成/错误)。检索先于 LLM 调用发生,**检索引用随开场元数据事件下发**(前端流式开始前即可渲染引用条),并落库到助手消息 refs(新增检索引用形状:章节标识 + 标题 + 原文摘录)。S1(带选中文字)不受影响,优先级最高。
- **S3 前置校验**(“该书嵌入完成”= 最新 job 为 done 且 job.model = 当前配置 embedding_model):未配置 embedding / 嵌入中 / 失败未重试 / 模型已更换四态各自可读中文文案,显式检索式提问前置不满足走错误事件或 4xx(实现定,行为可断言即可)。
- **S2 检索式降级翻真**(M3 预埋兑现):`retrievalAvailable` = 已配置 embedding 且该书嵌入完成(当前模型);为真时 BudgetCalculator 的 RETRIEVAL 分支由调用方执行——以问题向量检索该书 top-k 块装配书内容槽,降级说明如实告知"已降级为检索式上下文"。为假时行为与 M3 完全一致(INSUFFICIENT 文案),但文案按"未配置 embedding"与"已配置未嵌入完成"两态区分引导方向(BudgetCalculator 纯函数逻辑不动,文案区分由调用方侧完成)。
- **跳转口径**:引用携带章节标识与原文摘录;前端跳转到该章(经 foliate-js 既有 CFI 能力),并在章内尝试以摘录文字定位(命中则滚动到命中处,未命中停在章首)。D-40 清洗后的纯文本与渲染 DOM 无一一对应,**不承诺 CFI 级精确定位**——章 + 摘录定位是 v1 口径。
- **删书级联补全**:向量块与嵌入任务经外键随书级联清除,挂进 M3 已落地的删书流程,零新入口。
- **ADR**:向量维度与检索策略(无 typmod + 顺序扫描 + 量级论证)必录一篇;切块参数取舍视情补记。
- **CORS 与鉴权**:沿用 M2 全局 CORS 与静态 token 拦截,新端点一律 401 防线;无新运维面。

## Testing Decisions

- **好测试的标准**(沿用 M1–M3):只断言外部可观察行为——HTTP 状态码、响应体(含错误文案与 SSE 事件序列)、数据库行(向量块归属/条数/进度)、浏览器里可见的结果与持久化效果;不 mock 被测边界内的模块(embeddings 客户端、切块器、任务编排、ORM 都在边界内);唯一被替身的是系统边界外的 embedding/chat 服务,且替身打在网络层(OpenAI 兼容 stub)。
- **纯函数单测(唯一新缝,最窄,已与用户确认)**:切块器,普通 JUnit、无 Spring,沿用 BudgetCalculator/TokenEstimator 模式。覆盖边界:目标块长附近的断点选择 / 段落优先与中文标点断点 / 超长无断点段强切 / 空章与纯空白章 / 多章独立编号 / token_count 与 TokenEstimator 口径一致。
- **Seam A(既有扩展,已与用户确认)**:后端 HTTP × Testcontainers(pgvector/pg18 容器 M0 起在用,零基建改动)+ OpenAiStubServer 增 `/embeddings` 端点,**确定性向量**(关键词 → 固定维度映射),使 top-k 检索排序可断言。覆盖:配置 embedding 上传 → 自动建 job → 进度 → done,向量块行(条数 / book/chapter 归属 / token_count / 维度);未配置上传 → 不建 job;stub 上游失败 → job failed + 可读 error,重试 → done;换模型触发重嵌入 → 旧块清净、新块全量、状态翻 done;embeddings 请求发往 embedding 独立 base_url(独立配置时);S3 显式检索式提问 → SSE 事件序列 + 引用随开场元数据下发 + refs 落库 + 发给 chat stub 的 prompt 含检索块文本;S2 小上下文 + 长书 + 嵌入完成 → prompt 为检索式而非报错,未嵌入完成 → INSUFFICIENT 文案正确引导;S3 前置四态各自可读错误;删书级联清块与任务;新端点 401。**Prior art**:M3 ChatAskIntegrationTest、OpenAiStubServer、IntegrationTestBase(容器与 storage 按 JVM 单例复用)。
- **Seam B(既有扩展,已与用户确认)**:Playwright 全栈 E2E,harness stub-llm 增 `/embeddings`(确定性,与后端 stub 同思路):设置页配 embedding → 上传书 → 嵌入状态卡进度至完成 → AI 面板"定位原文"提问 → 流式回复带引用条 → 点击引用跳转到对应章节(resetBackend / 页面断言)→ 刷新后引用仍在;未配置 embedding → 无"定位原文"入口、无嵌入状态卡(FR-403);换模型 → 提示需重嵌入 → 重嵌入入口跑通。**Prior art**:ai-chat.spec.ts、stub-llm.ts、M1 highlights helpers(resetBackend、shadow DOM 断言缝)。
- **真实 embedding(bge-m3):人工验收清单**,对应 M4 验收核心:真书上传自动嵌入全流程;真实问句"作者在哪讨论过 X"检索命中并跳转到原文位置;真实换模型全量重嵌入跑通;真实嵌入失败的错误文案可读。
- **packages/api-client**:无独立单测(沿用既有口径,经 Seam A 契约与 Seam B 链路间接覆盖);桌面壳无自动化(零逻辑,人工烟测)。

## Out of Scope

- **S4 跨书对比全部**:纯向量多书检索(D-36)、跨书会话创建入口、S4 检索范围(全库 vs 勾选书单)、跨书 refs"原书已删除"降级渲染——S3 落地后专盘另出小 spec(§9 待定项口径,已与用户确认)。
- ANN 索引(HNSW/IVFFlat)——顺序扫描在个人量级达标,列为后续优化。
- 嵌入断点续传(重试/重嵌入全量重跑已定);嵌入任务定时调度/清理策略。
- 关键词/多路召回(D-36,v2);中文分词。
- CFI 级精确跳转(D-40 清洗口径决定不可能,v1 = 章 + 摘录定位)。
- 划线作为 AI 上下文输入(D-42,v2)、对话摘要、多模型 profile(v2)——M3 已排除项继续排除。
- 安卓端一切(M5)、上云与 HTTPS(M6)。
- embedding 计费 / 配额 / 速率管理;书内全文搜索(v2 候选)。
- 桌面壳特有增强(沿用 M2/M3 spec 口径)。

## Further Notes

- **M4 验收标准原文映射**:"问'作者在哪讨论过 X'返回可点击跳转的原文位置" → Seam B(stub)+ 人工(真实);"嵌入任务状态可见、失败可重试" → Seam A + Seam B;"未配 embedding 时入口隐藏" → Seam B;"S3 以'该书嵌入完成'为前置" → Seam A(前置四态);"换模型全量重嵌入可跑通" → Seam A(stub 换模型)+ 人工(真实)。
- **M3 预埋清单兑现对照**:预算函数检索式分支标志翻真 ✅(本 spec);refs jsonb 形状扩展(检索引用)✅;删书流程向量清理挂点 ✅;chat_session.book_id 可空留给 S4 mini-spec。
- **R-1/R-2 姿态不变**:embedding api_key 明文存库沿用 FR-404 已接受口径;M6 上云前再评。
- **embedding 维度陷阱(R-3)本 spec 落地方式**:同书同模型一次嵌入保证维度一致;换模型清块重嵌;查询侧由 S3 前置校验兜住模型一致性。
- "该书嵌入完成"的裁决口径 = 最新 embedding_job(status=done 且 model=当前配置),与"最近活跃会话 = updated_at"同为服务器时钟单一事实源精神(D-19)。
- S4 mini-spec 依赖信号:S3 落地后实际体验(引用命中率、跳转定位体验、检索质量)作为入口形态与检索范围的决策输入,届时专盘。
