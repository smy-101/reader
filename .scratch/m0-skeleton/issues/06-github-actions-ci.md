# 06: GitHub Actions CI

**What to build:** 提交保底线:建立 GitHub 远程仓库并推送;workflow 仅跑 backend `mvn test`(内含 Testcontainers,runner 自带 Docker),无 CD;以一次红→绿验证 CI 真的在拦截。完成后可演示:每次推送自动跑后端测试,红了能看见。

**Blocked by:** 01(后端骨架 tracer:Spring Boot + 鉴权 + Flyway + Testcontainers)

**Status:** ready-for-agent

- [ ] GitHub 远程仓库建立,本地分支推送成功
- [ ] workflow 仅跑 backend `mvn test`,无部署环节(D-23)
- [ ] 验证过一次红→绿(故意引入失败或以等价方式证明拦截有效),结果记录在案
- [ ] CI 与本地 `mvn test` 结果一致(Testcontainers 在 runner 上可跑)
