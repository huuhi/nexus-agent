-- ============================================================================
-- 007_add_user_token_quota_period.sql
-- ============================================================================
-- 目的     ：为 token 配额增加**周期重置**能力（P2-8 遗留）。
--            003 里 token_used 是「累计总量、只增不减」，没有每日/每月重置，
--            导致配了额度的用户用完就永久被拒。本脚本只加两列，不改变既有语义：
--              token_period       周期类型（NONE=不重置，行为与 003 完全一致）
--              token_period_start 当前周期的起点，用于判断"是否该重置了"
-- 影响表   ：users（仅新增两列，不改动任何既有列与索引）。
-- 幂等性   ：可重放（ADD COLUMN IF NOT EXISTS）。
-- 破坏性   ：无。token_period 有默认值 'NONE'，
--            **存量用户行为完全不变**（等同于今天的实现）。
-- 回滚     ：
--   ALTER TABLE "public"."users" DROP COLUMN IF EXISTS "token_period_start";
--   ALTER TABLE "public"."users" DROP COLUMN IF EXISTS "token_period";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U postgres -d nexus_agent -f docs/sql/007_add_user_token_quota_period.sql
--
-- 说明     ：
--   1. 周期按**服务端默认时区**计算（DAILY=当天 00:00、MONTHLY=当月 1 号 00:00）。
--      要换时区请设 JVM 默认时区，别在这里改列类型。
--   2. 重置是**惰性**的：不跑定时任务，而是在「对话开始前校验配额」时顺手判断
--      （period_start < 当前周期起点 → 把 token_used 清零并写入新起点）。
--      好处：不需要任何后台任务，也不会有"定时任务挂了导致大家配额不刷新"的故障模式。
--      代价：长期不说话的用户，其 token_used 不会被清零（但他也没在用额度）。
--   3. 重置用的是一条 UPDATE ... WHERE period_start < ?，天然幂等：
--      并发的两个请求只会有一个命中（另一个的 WHERE 已不成立）。
--   4. 单个用户可以在库里覆盖全局配置：
--        UPDATE users SET token_period='MONTHLY', token_quota=1000000 WHERE id=...;
--      全局默认值由 nexus.agent.quota.period 配置（默认 NONE）。
-- ============================================================================

ALTER TABLE "public"."users"
  ADD COLUMN IF NOT EXISTS "token_period" varchar(16) NOT NULL DEFAULT 'NONE';

ALTER TABLE "public"."users"
  ADD COLUMN IF NOT EXISTS "token_period_start" timestamp;

COMMENT ON COLUMN "public"."users"."token_period"
  IS 'token 配额周期：NONE=不重置（累计），DAILY=每日重置，MONTHLY=每月重置';
COMMENT ON COLUMN "public"."users"."token_period_start"
  IS '当前周期的起点时间；小于本次计算出的周期起点时触发 token_used 清零（服务端本地时区）';

-- 可选：给"要按周期重置的用户"加个提示性索引。
-- 数据量小时不需要；真要跑批重置时再建。
-- CREATE INDEX IF NOT EXISTS idx_users_token_period
--     ON "public"."users" ("token_period") WHERE token_period <> 'NONE';
