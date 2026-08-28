# reader-backend

AI 阅读器后端:Spring Boot 3 + JDK 21 + Maven + MyBatis-Plus(D-9),Flyway 管 schema(D-17)。

## 本地启动

数据库是云 PG 的 `reader_dev` 库(专户 `reader_app`,D-11/D-39)。凭据在本机 `local/db.env`(不入库),启动前导出为环境变量:

```bash
set -a; source ../local/db.env; set +a
export READER_DB_URL='jdbc:postgresql://121.196.234.186:6666/reader_dev'
export READER_DB_USER=reader_app
export READER_DB_PASSWORD="$READER_APP_DB_PASSWORD"
mvn spring-boot:run
```

- 鉴权 token:`READER_AUTH_TOKEN` 环境变量,本地默认 `reader-dev-token`(D-4)
- 落盘目录:`READER_DATA_DIR`,默认 `./data`(已 gitignore)
- 端口:`READER_SERVER_PORT`,默认 8080

## 测试

```bash
mvn test
```

集成测试用 Testcontainers 拉起与生产同款 `pgvector/pgvector:pg18` 容器,**不碰云库**;本地需 Docker。

## 结构约定

- schema 只经 `src/main/resources/db/migration/` 的 Flyway 迁移变更,禁止手改(D-17)
- 选型决策见 `docs/adr/`
