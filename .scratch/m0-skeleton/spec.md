# Spec: M0 工程骨架 —— Spring Boot 后端 + EPUB 上传链路

Status: ready-for-agent
Created: 2026-08-28
Source: `docs/需求文档.md` v1.3(M0 章节、FR-101/102/105、D-9/10/11/17/23/26/29/30/39/40/41)+ `CONTEXT.md` 术语表
Milestone: M0(工程骨架)

## Problem Statement

作为个人读者兼开发者,我已有完整的 v1.3 需求文档和就绪的云 PostgreSQL(pgvector)实例,但仓库还是空的:没有任何后端工程可供后续里程碑挂靠。每次想开始做"网页阅读器"或"AI 伴读",都得先解决一切功能的地基——工程骨架、数据库迁移、CI、鉴权、以及 EPUB 进库这条最基础的上传链路。没有这块地基,书连"被系统认识"都做不到,遑论阅读与 AI 讨论。

## Solution

一个可运行的 Spring Boot 后端:我用 curl 把一本 EPUB 上传上去,它**同步**解析出元数据、章节目录、章节纯文本与封面,入库并把书源文件落盘;重复上传同一文件幂等返回"已在书库";损坏文件与 DRM 书返回可读错误且不留任何痕迹;所有接口受静态 Bearer token 保护;Testcontainers 集成测试与 GitHub Actions CI 从第一天就绿;关键选型(MyBatis-Plus、PG+pgvector、Flyway、foliate-js)各有一篇"为什么"的 ADR。

## User Stories

1. As a reader, I want to upload a local EPUB into the 书库, so that the book becomes available to every future feature (阅读、划线、AI 伴读).
2. As a reader, I want 上传损坏的 EPUB 时收到"文件损坏"的可读错误文案, so that 我知道该去修文件而不是反复重试。
3. As a reader, I want 上传疑似 DRM 保护的 EPUB 时收到明确的"疑似 DRM 保护,不支持导入"提示, so that 我知道这类书从一开始就进不来(不解密、不留记录)。
4. As a reader, I want 重复上传同一本书(同 file_hash)时得到"已在书库"并看到原书记录, so that 书库里永远不会出现重复条目。
5. As a reader, I want 上传超过 100MB 的文件时快速收到 413 超限提示, so that 我不必等整个传输完成后才发现失败。
6. As a reader, I want 上传成功后书源文件与封面安全落盘到服务器, so that 后续各端能按需下载、书库列表能显示封面。
7. As a reader, I want 查看书库列表(封面、标题、作者), so that 我能浏览自己的藏书。
8. As a reader, I want 查看某一本书的元数据与章节列表, so that 我能了解书的结构。
9. As a reader, I want 只有"有正文的内容文件"入库为章节、按阅读顺序平铺, so that 章节列表与实际阅读内容一一对应,嵌套目录不产生假章节。
10. As a reader, I want 章节正文以干净纯文本入库(丢图、表格拍平为文本、脚注并入章末、代码块原样保留), so that 后续 AI 读到的原文忠实且可直接引用。
11. As an API consumer, I want 不带 token 或带错 token 请求任何接口都得到 401, so that 安全边界唯一且明确。
12. As an API consumer, I want 上传接口返回入库书籍的完整元数据, so that 前端能立即展示导入结果。
13. As an API consumer, I want 幂等重传返回 200 与已存在书籍, so that 前端能提示"已在书库"而不是报错。
14. As an API consumer, I want 所有错误响应是可读文案而非堆栈, so that 可以直接展示给用户。
15. As an API consumer, I want 通过 URL 拉取书籍封面图片, so that 书库列表渲染封面无需额外逻辑。
16. As a developer, I want 一个 Spring Boot 3 + JDK 21 + Maven + MyBatis-Plus 的标准骨架, so that 我在贴合国内工作场景的技术栈上完成学习目标(D-1/D-9)。
17. As a developer, I want Flyway 管理全部建表/改表(禁止手改 schema), so that 库结构变更有版本、可回溯(D-17)。
18. As a developer, I want Testcontainers 集成测试基建从 M0 就位, so that 每个里程碑都能按毕业标准交付带测试的代码(D-16)。
19. As a developer, I want GitHub Actions CI 从 M0 起跑 backend 测试, so that 每次提交都有红线保底(D-23)。
20. As a developer, I want 誊写 4 篇存量 ADR(MyBatis-Plus、PG+pgvector、Flyway、foliate-js), so that 关键选型的"为什么"落档可查(R9-Q6)。
21. As a developer, I want 应用以专户 reader_app 连库, so that 权限最小化、超管只留运维(D-39)。
22. As a developer, I want 开发期直连云 PG 的 reader_dev 库, so that 不必维护本地数据库实例(D-11)。
23. As a developer, I want 集成测试用 Testcontainers 本地起与生产同款的 pgvector 容器, so that 测试可重复、不污染云库。
24. As a developer, I want 上传解析走同步路径(不做异步任务), so that 代码路径简单可调试(D-41)。
25. As a developer, I want monorepo 目录结构一次就位(backend / apps / packages), so that 后续里程碑无需挪动结构(D-13)。
26. As a maintainer, I want M0 内通过解析 spike 定稿纯文本清洗细则, so that D-40 的高层口径有可执行细节。

## Implementation Decisions

- **范围口径**:本 spec 只覆盖 M0。里程碑 M1+(网页阅读器、AI 对话、向量检索、安卓端、上云)各出各的 spec。
- **模块**:后端模块(Spring Boot 3、JDK 21 LTS、Maven、MyBatis-Plus);monorepo 顶层按需求文档 §6.3 布局一次就位,前端与共享包目录留占位。
- **数据库迁移**:Flyway;首个迁移只建本里程碑需要的两张表——`book`(title/author/language/cover_path/file_hash 唯一/file_size/created_at/updated_at)与 `chapter`(book_id 外键/seq 阅读顺序/title/href/content 纯文本/text_length)。其余表(highlight、progress、会话、向量块等)随各自里程碑的迁移追加。
- **上传链路**(FR-101/102/105):
  1. 接收文件,校验大小上限 100MB,超限 413;
  2. 计算 file_hash,查重命中 → 幂等 200 返回已存在书籍,不重复解析、不重复落盘(D-30);
  3. 未命中 → **同步**解析:元数据、章节目录、章节纯文本、封面(D-41,无异步任务);
  4. **先解析后落盘**(D-29):解析失败 → 400 + 可读文案,区分"文件损坏"与"疑似 DRM 保护"(识别加密标记);不建 book 记录、不落任何文件;
  5. 成功 → 入库(book + chapter 行)+ 书源文件与封面落盘到服务器磁盘目录(该目录进 gitignore)。
- **章节口径**(D-40):chapter 仅收录 EPUB 中"有正文的内容文件",按阅读顺序平铺(seq);嵌套目录只是导航视图,不入库(目录渲染是 M1 前端的事,直接读 EPUB 原文件)。`chapter.content` 清洗口径:丢图、表格拍平为文本、脚注并入章末、代码块原样保留;细则在 M0 内以解析 spike 定稿并记录。
- **查询 API**:书库列表(封面/标题/作者;进度字段 M0 恒为空,接口占位)、书籍详情、章节列表、封面文件服务。均受 token 保护。
- **鉴权**:静态 Bearer token,经配置注入;所有 HTTP 接口统一 401 拦截,无用户概念。
- **数据库连接**:应用以专户 reader_app 连云 reader_dev 库(D-11/D-39);凭据只存本机不入库。建表/扩展装设一律走 Flyway 与既有运维流程。
- **CI**:GitHub Actions,job 仅跑后端 `mvn test`(内含 Testcontainers,runner 自带 Docker),无 CD(D-23)。**前置**:git 初始化并建 GitHub 远程仓库(否则 CI 无处跑)。
- **ADR**:誊写 4 篇存量决策——D-9(MyBatis-Plus)、D-10(PG+pgvector)、D-17(Flyway)、D-18(foliate-js),放入仓库 ADR 目录,格式遵循项目 ADR 惯例。
- **验收即行为**:M0 验收标准(curl 上传可查、坏书不落库不落盘、幂等、Testcontainers 绿、CI 红→绿)全部是外部可观察行为,无隐藏验收项。

## Testing Decisions

- **好测试的标准**:只断言外部可观察行为——HTTP 状态码、响应体(含错误文案)、数据库行、磁盘文件存在性;不 mock 内部模块(解析器、ORM、迁移都不在被测边界之外),不测实现细节。
- **唯一测试 seam(已与用户确认)**:后端 HTTP API × Testcontainers。测试拉起真实 Spring Boot 应用 + 本地 `pgvector/pgvector:pg18` 容器(与生产同款镜像),用真实 EPUB fixture 文件打接口。
- **被测模块**:后端整体(上传、查询、鉴权、迁移、落盘全部在一个边界内)。
- **fixture 策略**:仓库内置小型 EPUB fixture——一本正常书、一个损坏文件(截断或非 zip)、一本带加密标记的 DRM 构造书;体积保持小巧,可随测试构造。
- **关键用例**:正常上传全链路(响应 + book/chapter 行 + 磁盘文件)、同 hash 幂等(第二次 200 且不新增行/文件)、损坏 400 文案、DRM 400 文案 + 断言无记录无文件、超限 413、无/错 token 401。
- **Prior art**:无——本 spec 的测试就是仓库第一批测试,将作为后续里程碑的先例(含"云库不进测试、Testcontainers 隔离"的惯例)。

## Out of Scope

- 前端(apps/web、foliate-js 渲染、目录导航、进度、划线)—— M1
- 批量上传(D-43:前端循环单文件接口)—— M1
- 删除书籍及其级联(文件、划线、向量、该书会话)—— 后续里程碑,M0 验收未含
- 划线/阅读进度/会话/模型设置全部 API —— M1/M3
- AI 对话(S1–S4)、上下文预算、token 计数 —— M3
- embedding、向量块、切块 —— M4
- Tauri 桌面壳 / 安卓端 —— M2/M5
- 部署上云、备份脚本 —— M6
- 异步解析任务(明确不做,D-41)
- 书籍元数据编辑、TXT/PDF、书内搜索等需求文档 §3.2 全部"不做"项

## Further Notes

- 需求唯一基准是 `docs/需求文档.md` v1.3;术语以 `CONTEXT.md` 术语表为准(书籍/章节/书源文件/书库 vs 书架等,行文勿混用)。
- 决策编号(D-xx)与风险编号(R-x)均指需求文档,spec 内不重复论证。
- 数据库连接串与密码在本机 `local/`(已 gitignore),实施 agent 需要时向用户索取,不入库。
- 云 PG 与专户 reader_app 已就绪并复验(需求文档 §10);集成测试不碰云库。
- 大书解析耗时:同步路径若实测超 30s 再议(D-41 附注),M0 不预设优化。
- 后续里程碑各自出 spec;本 spec 完成后 M1(含 48h foliate-js spike 前置)是下一个。
