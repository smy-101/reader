# 02: 嵌入任务域 tracer(两表 + embeddings 客户端 + 后台编排 + 状态/触发端点)

**What to build:** 配置好 embedding 后,curl 上传一本书:上传立即返回(同步解析口径 D-41 不变),后台自动创建**嵌入任务**并开跑;查该书嵌入状态可见 pending → running(进度 chunk_done/chunk_total)→ done,**向量块**按切块器切块、embedding 后入库(同书维度一致)。未配置 embedding 的上传不建任务。stub 上游失败 → 任务 failed 带可读中文 error,触发重试从头重跑(旧块清净后重嵌);换 embedding 模型后触发 → 全量重嵌入(新维度可断言);配置 embedding 之前的存量书可手动触发首次嵌入;同模型已完成时触发幂等返回当前状态。embeddings 调用走 embedding 独立 base_url/api_key(空=跟随 chat,D-28)、分批有界;后台单线程队列,同书串行无并发竞态。`document_chunk`(embedding 列 vector 不带 typmod)与 `embedding_job` 两表经 Flyway 落地、外键随书级联;**维度与检索策略**(无 typmod + 个人量级顺序 cosine 扫描、ANN 索引延后)录 ADR。api-client 增嵌入状态/触发方法。自动化:OpenAI 兼容 stub 增 `/embeddings` 端点返回**确定性向量**(关键词 → 维度映射),Seam A 用例落档,CI 零新增外网依赖。

**Blocked by:** 01(切块器)

**Status:** ready-for-agent

- [ ] 已配置 embedding:上传成功后自动创建嵌入任务,状态端点可见 pending/running(进度推进)/done;上传响应不等嵌入,嵌入失败不影响上传结果与阅读
- [ ] 向量块行落库:条数=切块数、book/chapter 归属正确、seq 有序、token_count 齐、同书维度一致
- [ ] 未配置 embedding:上传不建任务,状态端点明示未嵌入
- [ ] 上游 embeddings 失败/超时:任务 failed + 可读中文 error;触发重试 → 从头重跑,旧块清净后重新入库,终态 done
- [ ] 换模型触发:清旧块全量重嵌入,块维度随新模型变化可断言;同模型且 done:触发幂等返回当前状态
- [ ] 存量书(未配置期上传)手动触发首次嵌入可达 done
- [ ] embeddings 请求发往 embedding 独立 base_url/api_key(独立配置时;留空跟随 chat,D-28);请求分批有界,大书不打爆上游
- [ ] 同书任务串行:同一本书不会出现两个并发执行的任务
- [ ] 两表经 Flyway 迁移落地、外键 ON DELETE CASCADE;维度与检索策略 ADR 落档
- [ ] 新端点不带/带错 token 一律 401;错误响应为可读中文文案
