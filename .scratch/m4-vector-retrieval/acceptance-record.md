# M4 验收记档(自动化侧)

Created: 2026-08-29(01–06 票转 done 记档;真实 embedding 人工项另列)

## 01–06 票状态

| 票 | 状态 | 证据 |
|---|---|---|
| 01 切块器纯函数 | done | `ChapterChunkerTest` 10 用例(纯 JUnit 无 Spring) |
| 02 嵌入任务域 tracer | done | `EmbeddingJobIntegrationTest` 9 用例;V5 迁移;ADR-0008 |
| 03 检索式上下文 tracer | done | `RetrievalAskIntegrationTest` 7 用例;`ChatAskIntegrationTest` 13 用例回归 |
| 04 web 嵌入状态卡 | done | `embedding.spec.ts` 5 用例(含换模型重嵌入)|
| 05 web S3 定位原文 | done | `s3-citation.spec.ts` 2 用例 |
| 06 删书向量级联 | done | `BookDeletionIntegrationTest` 5 用例(含嵌入中删书竞态);`delete-book.spec.ts` 2 用例回归 |

## M4 验收标准逐条勾验(自动化侧)

- **“问‘作者在哪讨论过 X’返回可点击跳转的原文位置”** → Seam B stub 全链路绿
  (`s3-citation.spec.ts`:检索提问 → 引用条 → 跳转第 4 章并定位摘录 → 刷新仍在);
  真实 embedding 命中质量另需人工核验(见下)。
- **“嵌入任务状态可见、失败可重试”** → Seam A(`EmbeddingJobIntegrationTest`:进度推进、
  failed + 可读 error、重试至 done)+ Seam B(`embedding.spec.ts`:状态卡进度至完成、
  独立 base_url 不可达失败 → 修复重试至完成)。
- **“未配 embedding 时入口隐藏”** → Seam B(`embedding.spec.ts` 状态卡隐藏;
  `s3-citation.spec.ts` 无「定位原文」入口且 S2 照常);真机核验另需人工。
- **“S3 以‘该书嵌入完成’为前置”** → Seam A 前置五态
  (`RetrievalAskIntegrationTest`:未配置 / 未嵌入 / 进行中 / 失败未重试 / 模型已更换)。
- **“换模型全量重嵌入可跑通”** → Seam A stub(`换模型触发…维度随新模型变化`);
  真实模型切换另需人工。

## S2 降级链兑现

- 整书 → 目标章 → 检索式全链:`RetrievalAskIntegrationTest.S2小上下文长书_自动降级检索式`
  (prompt 为检索式装配、done 事件降级说明如实告知、不再 INSUFFICIENT);
  已配置未嵌入完成 → INSUFFICIENT 文案引导“等待嵌入完成”,与“未配置”两态区分。

## 评审处置(双轴 code-review,2026-08-29)

- Standards P1:嵌入任务无启动恢复 → 已修(非终态任务启动时重置 pending 重新入队;重启孤儿任务不再卡"嵌入中")。注:重启恢复路径本身无自动化用例(需重启应用上下文,重),留作残余风险。
- Standards P2:ChatService 重复 import / latest-job 查询与就绪度解读两处重复 / BookService 过时挂点注释 / stub "默认 32" 注释 / trigger 与上传建任务并发建行 / 状态卡拉取失败永久停摆 / MessageBubble `__i` key hack → 全部已修(去重、抽公共 latestJob、createJobIfNeeded synchronized、拉取失败 5s 重试、正规 key)。
- Spec P2:US13"换模型提示需重嵌入"web 侧缺失 → 已修(状态卡 done+模型不匹配明示"模型已更换,需重新嵌入",embedding.spec.ts 增换模型重嵌入 E2E,共 5 用例)。
- Spec P2:前置"四态"实现为五态(多 NOT_EMBEDDED,存量书引导触发嵌入)→ 维持,benign 超集,记档。

## 人工验收清单(真实 embedding,待办)

以下项需真实 OpenAI 兼容 embedding 服务(如 bge-m3)人工核验,对应票 07 未勾项:

1. 真书上传 → 自动嵌入 → 状态完成,全程无人工干预。
2. 真实问句“作者在哪讨论过 X”检索命中并跳转到原文位置可读(引用命中率、跳转定位体验、
   检索质量——同时是 S4 mini-spec 的决策输入,见 spec · Further Notes)。
3. 未配 embedding 时入口隐藏(真机核验,FR-403)。
4. 真实换 embedding 模型全量重嵌入跑通,新模型生效后 S3 可用。
5. 真实嵌入失败形态(断网 / 错 key)错误文案可读、可重试。
