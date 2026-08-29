# 01: 模型设置域 tracer(单套配置 + 测试连接 + 设置页)

**What to build:** 模型设置闭环端到端可用:web/桌面的设置页可填 5 项 chat 配置(base URL、API key、chat 模型、上下文上限可空、embedding 模型可选)+ 2 项 embedding 独立配置(embedding_base_url / embedding_api_key,空=跟随 chat,D-28),保存后 API key 明文回显(FR-404);bge-m3 仅作 placeholder 提示不写死。"测试连接"由后端代理,chat 与 embedding 两个探针分别向各自生效的 base_url 发最小请求,各自返回 ok 或可读中文错误(连接失败/超时/401/404 分类文案),embedding 未配置时该探针明示跳过(FR-405)。后端单套配置(id 恒为 1,D-27)读写端点 + 测试连接端点,model_settings 经 Flyway 迁移建表。全部行为以 Seam A 集成测试落档(本地 OpenAI 兼容 stub 扮演上游),新端点 401 防线不变。

**Blocked by:** None(可立即开始)

**Status:** ready-for-agent

- [ ] 设置页可保存/回显 7 项配置;上下文上限与 embedding 各项可空,空语义 = 8k 保守 / 跟随 chat(FR-401 / D-27 / D-28)
- [ ] 测试连接:chat 探针 ok / 连接失败 / 超时 / 401 / 404 → 各自可读中文文案;embedding 未配置 → 明示跳过;已配置 → 独立 base_url 生效(FR-405)
- [ ] model_settings 单行表经 Flyway 迁移落地,id 恒为 1(D-17 / D-27)
- [ ] API key 明文存库、设置页明文回显(FR-404 已接受姿态)
- [ ] bge-m3 仅作 placeholder 提示(R9-Q5)
- [ ] 新端点不带/带错 token 一律 401;错误响应可读文案(与 M0–M2 一致)
- [ ] Seam A 集成测试(本地 stub 上游)覆盖上述全部行为,mvn test 绿,既有测试零回归
