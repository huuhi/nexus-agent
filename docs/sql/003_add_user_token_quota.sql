-- ============================================================================
-- 003_add_user_token_quota.sql
-- ============================================================================
-- 目的     ：为 users 表增加 **token 配额** 与 **已用总量** 两列（P2-8）。
--            应用侧在每次对话前校验配额、对话结束后把实际 token 用量累加进来。
-- 影响表   ：users（仅新增两列，不改动任何既有列与索引）。
-- 幂等性   ：可重放（ADD COLUMN IF NOT EXISTS）。
-- 破坏性   ：无（新列有默认值，存量行自动补 0）。
-- 回滚     ：
--   ALTER TABLE "public"."users" DROP COLUMN IF EXISTS "token_used";
--   ALTER TABLE "public"."users" DROP COLUMN IF EXISTS "token_quota";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U postgres -d nexus_agent -f docs/sql/003_add_user_token_quota.sql
--
-- 说明     ：
--   1. token_quota 为 NULL 或 <= 0 表示**不限制**（默认放行），
--      这样老用户行为不变 —— 要不要限、限多少由部署者决定。
--   2. token_used 是**累计总量**，单调递增、不清零。
--      按周期（日/月）配额需要额外记录"周期起点"，当前不做（见 重构计划 P2-8）。
--   3. users.api_quota 是历史字段（"API 调用配额"，smallint 默认 100），
--      代码中**从未被读写**。本次不动它：删列属于破坏性变更，
--      且保留可避免与既有数据/导出脚本产生意外差异。
-- ============================================================================

ALTER TABLE "public"."users"
  ADD COLUMN IF NOT EXISTS "token_quota" bigint;

ALTER TABLE "public"."users"
  ADD COLUMN IF NOT EXISTS "token_used" bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN "public"."users"."token_quota"
  IS 'token 配额上限（累计，NULL 或 <=0 表示不限制）';
COMMENT ON COLUMN "public"."users"."token_used"
  IS '已累计消耗的 token 总量（由对话结束时累加，单调递增）';
