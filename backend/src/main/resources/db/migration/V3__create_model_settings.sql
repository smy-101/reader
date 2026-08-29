-- M3:模型设置(需求文档 §6.4 / M3 spec · FR-401, D-27/D-28)
-- schema 变更一律走 Flyway 迁移,禁止手改(D-17,ADR-0003)
-- v1 单套配置:id 恒为 1(CHECK 钳死,D-27);多 profile 放 v2
-- API key 明文存库已接受(FR-404/R-2:单用户 + token 兜底,M6 上云前重评)

CREATE TABLE model_settings
(
    id                  INT PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- 单套配置(id 恒 1,D-27)
    base_url            TEXT NOT NULL, -- OpenAI 兼容 base URL,应形如 https://api.example.com/v1
    api_key             TEXT NOT NULL DEFAULT '', -- 明文(FR-404 已接受姿态);本地服务可空
    chat_model          TEXT NOT NULL,
    chat_context_tokens INT, -- 可空:空 = 按 8k 保守计(D-27/FR-302)
    embedding_model     TEXT, -- 可空:未配置则 embedding 探针跳过、S3/S4 入口隐藏(FR-403)
    embedding_base_url  TEXT, -- 可空 = 跟随 chat(D-28:chat 与 embedding 可各用一家)
    embedding_api_key   TEXT, -- 可空 = 跟随 chat(D-28)
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
