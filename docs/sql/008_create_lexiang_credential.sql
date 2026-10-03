-- ============================================================================
-- 008_create_lexiang_credential.sql
-- ============================================================================
-- 目的     ：新增腾讯乐享知识库的接入凭证表，让用户把自己的乐享知识库
--            "挂"到 nexus-agent 上，Agent 直接检索他们的私有知识。
--            **只做检索，不做上传**（用户文件仍由用户在乐享侧管理）。
-- 影响表   ：新建 lexiang_credential（不改动任何既有表）。
-- 幂等性   ：可重放（CREATE TABLE IF NOT EXISTS）。
-- 破坏性   ：无，纯新增表。
-- 回滚     ：DROP TABLE IF EXISTS "public"."lexiang_credential";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U <DB_USERNAME> -d nexus_agent -f docs/sql/008_create_lexiang_credential.sql
--
-- 数据模型（对齐乐享官方层级）：
--   team  (团队空间)  →  space (知识库)  →  entry (知识条目)
--   本表一个用户一行，存他默认检索哪个 space。
--
-- 字段说明：
--   app_key          乐享 AppKey，明文（它是标识符不是密钥，且换 token 时要原样传回）
--   app_secret       乐享 AppSecret，**密文**（用 EncryptorFactory + 用户 salt 加密）
--   staff_id         发起检索的成员账号。所有 AI 搜索与写操作都必须带 x-staff-id，
--                     且响应只返回该成员有权限的内容 —— 这是权限隔离维度。
--                     填 "system-bot" 则只能检索「全公司公开」的知识。
--   default_space_id 默认检索的知识库（对应 targets.type = space）
--   default_team_id  该知识库所属团队（供"列出知识库"时用）
--
-- 安全说明：
--   * app_secret 必须密文存储。绝不能明文入库，也绝不能在接口响应里回显。
--   * 表里不存 access_token —— 它只有 2 小时有效期，由应用侧缓存，不落库。
-- ============================================================================

CREATE TABLE IF NOT EXISTS "public"."lexiang_credential" (
  "id"                bigserial PRIMARY KEY,
  "user_id"           bigint       NOT NULL,
  "app_key"           varchar(128) NOT NULL,
  "app_secret"        text         NOT NULL,
  "staff_id"          varchar(64)  NOT NULL,
  "default_team_id"   varchar(64),
  "default_space_id"  varchar(64),
  "created_at"        timestamp    NOT NULL DEFAULT now(),
  "updated_at"        timestamp    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  "public"."lexiang_credential" IS '用户的腾讯乐享知识库接入凭证（一个用户一条）';
COMMENT ON COLUMN "public"."lexiang_credential"."user_id"          IS 'nexus-agent 的用户 id';
COMMENT ON COLUMN "public"."lexiang_credential"."app_key"          IS '乐享 AppKey（明文标识符）';
COMMENT ON COLUMN "public"."lexiang_credential"."app_secret"       IS '乐享 AppSecret（密文，用 EncryptorFactory 加密）';
COMMENT ON COLUMN "public"."lexiang_credential"."staff_id"         IS '发起检索的成员账号（x-staff-id）；system-bot 只能检索公开知识';
COMMENT ON COLUMN "public"."lexiang_credential"."default_team_id"  IS '默认检索知识库所属团队 id';
COMMENT ON COLUMN "public"."lexiang_credential"."default_space_id" IS '默认检索的知识库 id（targets.type=space）';

-- 一个用户只保留一份配置，保存时按 user_id 覆盖
CREATE UNIQUE INDEX IF NOT EXISTS "idx_lexiang_credential_user"
  ON "public"."lexiang_credential" ("user_id");

-- 反查：某个 space_id 被哪些用户绑定（排查"为什么检索不到"时用）
CREATE INDEX IF NOT EXISTS "idx_lexiang_credential_space"
  ON "public"."lexiang_credential" ("default_space_id");
