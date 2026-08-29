# S4 验收记档(自动化侧)

Created: 2026-08-29(01–04 票转 done 记档;真实 embedding 人工项另列)

## 01–04 票状态

| 票 | 状态 | 证据 |
|---|---|---|
| 01 后端跨书会话域 tracer | done | `CrossBookAskIntegrationTest` 12 用例(SSE 同构、多书溯源、refs 快照、就绪排除、前置两态、路由、列表、D-33 契约、401);后端全量 142 绿 |
| 02 web 全局 AI 面板 | done | `s4-cross-book.spec.ts` 8 用例中的入口显隐 ×2 / 主链路 / 续问重命名删除 / 书级隔离 / FR-303;书级 S1/S2/S3 既有 E2E 回归绿 |
| 03 web 跨书引用跳转 | done | `s4-cross-book.spec.ts` 跳转用例(另一本书非首章定位 + 当前书引用同口径);`s3-citation.spec.ts` 回归绿 |
| 04 D-33 悬空引用降级 | done | Seam A 契约用例(删书留会话/refs 快照不动/续问收敛)+ Seam B 占位用例(不可点、未删书照常可跳、书级级联双面) |

## S4 spec 验收口径逐条勾验(自动化侧)

- **SSE 同构(meta → delta… → done/error,citations 随 meta)** →
  Seam A `跨书提问_SSE同构_citations含书身份且随meta下发_refs落库带书名快照`;
  Seam B 主链路用例(引用条在流式开始前可见、流式回复全文渲染)。
- **多书检索与〔书名·第 N 章〕溯源头(D-36 全库自动)** →
  Seam A:发给 chat stub 的 prompt 含两本书的检索块与《书名》·第 N 章 溯源头;命中跨两本书断言;
  Seam B:stub 请求体断言 prompt 含《fixture 正常书》·第 与《赤壁赋与前后出师表》·第。
- **refs 书名快照(落库时定格,不靠实时 join)** → Seam A refs::text 断言含 bookId/bookTitle;
  D-33 契约用例断言删书后 refs 逐字节不变。
- **就绪集合裁决(未嵌入/模型已换静默排除)** → Seam A `只嵌一本书…` / `模型已换的书被排除…`
  (命中全部来自就绪书、未就绪书内容不进 prompt);
  Seam B `全库无就绪书(嵌入失败)→ 入口隐藏;修复重嵌入后出现`。
- **前置两态(未配置 embedding / 全库无就绪书)可读中文错误** → Seam A 两用例(400 + 文案断言 + 不建会话不留孤儿);
  Seam B 未配置用例(入口隐藏不报错)。
- **入口显隐(FR-403,就绪摘要同源消费)** → Seam B 三用例:未配置隐藏 / 嵌入失败隐藏 / 就绪后出现;
  就绪摘要来自书库列表响应(US 25,Seam A `书库列表带嵌入就绪摘要` 断言字段)。
- **跨书引用跳转(复用 S3 口径)** → Seam B:点击另一本书(非首章)引用 → 打开该书阅读器 → 定位到引用章节
  并命中摘录正文;当前书引用同口径;`s3-citation.spec.ts` 书级跳转回归绿。
- **占位降级(D-33)** → Seam B:删书后重开跨书会话,被删书引用显示「《书名》(原书已删除)」且 disabled,
  未删书引用照常可跳;书级会话级联清(直连断言全局列表只剩跨书会话)双面回归。
- **会话管理(FR-304)+ 自动路由(D-32 精神)** → Seam A 缺省/显式 id 落同一会话、书级会话错配 404 可读、
  重命名删除经既有端点;Seam B 续问同会话、重命名、删除、跨书会话不出现在书级列表。
- **401 防线** → Seam A `新端点不带token一律401`(/api/ask、/api/sessions)。
- **书级 S1/S2/S3 零变化** → 后端 142 全绿(含 ChatAsk 13 / RetrievalAsk 7 / ChatSession 6);
  E2E 35 全绿(ai-chat / s3-citation / embedding / delete-book 等既有用例)。
- **桌面壳零改动继承** → apps/desktop 无改动(壳内即 Web);typecheck 经 apps/web 覆盖 api-client 消费侧。

## 实现备注(口径落定)

- 检索查询泛化:`DocumentChunkRepository.searchTopK(Collection<Long> bookIds, …)`,空集 = 全库;
  S3 单书传单元素集合,与 S4 共用同一条 SQL(不养两份)。
- 跨书预算:复用 `BudgetCalculator.calculate`(whole/chapter/selection 全 null + retrievalAvailable=true
  → 恒 RETRIEVAL),纯函数不动;与跨书语境不符的"目标章超出预算"降级文案在调用方剔除,断尾说明保留。
- 显式会话 id 指向书级会话 → 404 可读文案(与书级会话错配同口径,NoSuchElementException 全站映射)。
- 重开面板收敛在途回复:点击跨书引用会带着进行中的流切进阅读器,重开面板时末条仍是 user →
  每 500ms 重拉直至助手消息落库(上限 30s;无推送,v1 口径 D-44;评审后已加 askError 非空即停护栏)。
- 入口亮起双通道(评审后收敛,不新增轮询):书库列表在挂载/上传/删书/设置变更时重拉 +
  嵌入状态卡转入 done 的边沿回调重拉列表(手动触发/失败重试场景);FR-403 两态(未配置 / 全库无就绪)由就绪摘要统一表达。

## 评审处置(双轴 code-review,2026-08-29)

- Standards P2(重复就绪裁决三处)→ 已修:抽 `EmbeddingJobService.isReady(job, currentModel)` 单一裁决函数,
  `readyBookIds` / `embeddingSummaries` / `ChatService.embeddingReadiness` 三处同源(spec Further Notes 口径)。
- Standards P2(两面板整段重复 ~180 行,且已开始分岐)→ 已修:抽 `useChatPanel`(会话/消息/流式/错误/说明/重命名删除全状态机)
  + `SessionList`(会话列表条目含重命名 UI),两面板变薄注入式;全量 E2E 35 回归绿。
- Standards P2(stripRetrievalDegradeNote 字符串裸匹配降级文案,无测试钉住)→ 已修:`BudgetCalculator.RETRIEVAL_DEGRADE_NOTE`
  提为公共常量两处同源,新增断言跨书 done.note 为 null(降级文案不得外漏);纯函数行为不变。
- Standards P2(测试名“400可读错误”实断 404;“全局会话列表”碰 avoid 术语)→ 已修:改名 404/跨书会话列表。
- Standards P2(FQN Collectors)→ 已修(改常规 import)。
- Standards P2("同源消费"两处解读:状态卡仍逐书轮询)→ 维持并记档:状态卡逐书轮询是 M4 既有行为(进度展示必需,
  不可去除);S4 口径的"同源" = 就绪裁决同一函数 + 入口显隐只吃书库列表摘要 + 嵌入转入 done 时状态卡边沿回调重拉列表联动。
- Spec P2(跨书面板出现显式"新会话"按钮,spec §9 决议无显式新建)→ 已修:移除按钮(提问自动路由已覆盖全流程),
  书级面板按钮不动(M3 既有行为)。
- Spec P2(书库页新增 2s 列表轮询,与"不新增推送/轮询"决策相抵)→ 已修:移除轮询;
  收敛靠挂载/上传/删书/设置变更重拉 + 状态卡转入 done 边沿回调重拉(双通道均为既有模式)。
- Spec P2(报告不修,记档):①在途回复重开收敛轮询(500ms×60 上限,已加 askError 非空即停护栏;上游报错无回复的会话重开时会
  空转 30s 后自停,个人量级可接受);②受理后检索前用户消息已落库,检索失败会留无回复的悬垂 user 行且下次提问进历史
  ——与书级链路 M3 以来同序同形(受理即落库是既定口径),如需回滚需两端同步改,移 v2。

## 人工验收清单(真实 embedding,待办)

以下项需真实 OpenAI 兼容 embedding + chat 服务(如 bge-m3)人工核验,对应票 05 未勾项:

1. 真实跨书问句(“A 书与 B 书怎么看 X”)的多书命中与对比质量:两本真实书嵌入完成后,
   在书库页「AI 伴读」提问,核对引用条是否合理命中两本书、对比回答是否基于检索段落
   (M4 验收记录人工清单第 3 项的顺延闭环,亦是 spec · Further Notes 的入口形态决策输入)。
2. 真实删书后的占位渲染:删除其中一本,重开跨书会话核验「《书名》(原书已删除)」占位
   与未删书引用可跳转(自动化已覆盖 stub 侧,真机再核验一遍视觉与交互)。
3. 真机核验入口显隐三态(未配置 / 无就绪书 / 就绪后出现)与刷新后收敛(无推送)。

## 全量回归记录(2026-08-29)

- 后端:`mvn test` → **142 通过,0 失败**(含既有 130 + S4 新增 12)。
- web E2E:`playwright test` → **35 通过,0 失败**(既有 27 + S4 新增 8;单 worker 串行)。
- typecheck:apps/web `tsc --noEmit` 通过(api-client 类型扩展经消费侧覆盖;桌面壳零 TS)。
