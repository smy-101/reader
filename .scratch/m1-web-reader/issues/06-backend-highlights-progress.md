# 06: 后端:划线 + 进度同步域(建表 + API + LWW)

**What to build:** 阅读同步域的后端全量,Seam A 交付。Flyway 新迁移建两张表:highlight(book_id、cfi、text、note、color、device、created_at、updated_at)与 reading_progress(book_id 唯一、cfi、percent、updated_at);外键均按级联删除建,为 FR-104 的完整级联(M3/M4 表齐后一并交付)预留;updated_at 一律服务器时钟、客户端不传时间戳(D-19)。端点:按书全量拉取划线、创建划线(CFI+文字快照+颜色+备注+设备标识)、单条更新、单条删除;按书单行读进度 + 单条 upsert(CFI+percent,重复 upsert 覆盖)。书库列表接通真实进度百分比(M0 占位转实,FR-103)。冲突整行 LWW 后写胜、无合并(§5.3);全部端点走既有 token 拦截。

**Blocked by:** None(可立即开始;与前端票并行)

**Status:** done

- [x] 两表经 Flyway 迁移落地;外键级联删除;updated_at 服务器时钟,客户端时间戳不参与裁决(D-19)
- [x] 划线:按书全量拉取 / 创建 / 单条改颜色备注 / 单条删除,CFI、文字快照、颜色、备注、设备标识落库(D-24)
- [x] 进度:单行读取 + 单条 upsert,重复 upsert 覆盖不产生多行
- [x] LWW:同一划线两次更新,后写胜(断言最终值为后写);无合并 UI
- [x] 书库列表返回真实进度百分比,无进度的书该字段为空(M0 占位转实)
- [x] 新端点无/错 token 一律 401;Seam A 集成测试覆盖上述行为,`mvn test` 全绿
