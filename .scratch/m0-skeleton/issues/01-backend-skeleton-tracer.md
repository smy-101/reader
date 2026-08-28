# 01: 后端骨架 tracer:Spring Boot + 鉴权 + Flyway + Testcontainers

**What to build:** 从空仓库到第一个可运行、可验证的后端切片:monorepo 目录结构一次就位(backend / apps / packages,前端与共享包留占位);Spring Boot 3 + JDK 21 + Maven + MyBatis-Plus 应用以专户 reader_app 连云 reader_dev 库本地启动;静态 Bearer token 拦截一切 HTTP 接口,不带 token 或带错 token 一律 401;Flyway 首个迁移建立 book 与 chapter 两表(file_hash 唯一约束);Testcontainers 以 pgvector/pgvector:pg18 镜像(与生产同款)起测试库的集成测试基建就位;git 仓库初始化。完成后可演示:`mvn test` 本地全绿;curl 带 token 请求任一接口通过、不带 token 得 401。

**Blocked by:** None(可立即开始)

**Status:** done (2026-08-28)

- [x] monorepo 目录结构按需求文档 §6.3 就位,apps / packages 有占位,git 仓库初始化并完成首次提交
- [x] 应用以专户 reader_app 连云 reader_dev 库本地启动;连接凭据只存本机 local/(已 gitignore),不入库
- [x] 所有 HTTP 接口:无 token 或错 token → 401;正确 token → 放行
- [x] Flyway 首个迁移建 book 与 chapter 两表(file_hash 唯一);此后 schema 只经迁移变更,禁止手改
- [x] Testcontainers 集成测试基建就位:拉起与生产同款 pgvector 容器,应用连测试库跑真实请求
- [x] 集成测试断言鉴权行为(401 / 200),`mvn test` 本地全绿
