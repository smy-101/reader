# 03: E2E 基建:Playwright 全栈 harness + CI web job

**What to build:** M1 spec 的 Seam B 落地,也是后续 UI 票的 prefactor(挂用例的基建)。全栈 E2E harness:Playwright 驱动真实浏览器操作 apps/web,后端以真实进程连 Testcontainers PG(pgvector/pgvector:pg18 同款镜像)起动,书源来自仓库内置的多章带目录 EPUB fixture(供本票与 05/07/08 复用)。首批用例覆盖 02 已交付的行为:单本上传→列表出现(封面、标题);批量上传→全部出现;重复上传→"已在书库"提示;按标题过滤生效。GitHub Actions 新增 apps/web job 跑 E2E,与既有 backend job 并列(D-23,无 CD)。编排细节(后端如何拉起、fixture 如何进库)实现时定,验收只看用例与 CI 绿。

**Blocked by:** 02(apps/web + api-client)

**Status:** ready-for-agent

- [ ] E2E harness 一键拉起:真实后端进程 + Testcontainers PG + apps/web,Playwright 驱动真实浏览器
- [ ] E2E 用例:上传单本 → 书库列表出现(封面、标题可见)
- [ ] E2E 用例:批量上传 → 全部出现;重复上传 → "已在书库"提示
- [ ] E2E 用例:按标题过滤生效
- [ ] 多章带目录的 E2E fixture EPUB 入库,供 05/07/08 复用
- [ ] GitHub Actions 新增 apps/web job,E2E 全绿;backend job 不受影响
