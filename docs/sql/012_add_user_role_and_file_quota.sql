-- ============================================================================
-- 012_add_user_role_and_file_quota.sql
-- ============================================================================
-- 目的     ：引入**用户角色**与**文件/产物配额**（2026-10-05）。
--            之前只有 token 配额（003/007），文件与产物完全没有数量约束；
--            现在按角色给出默认额度，注册即生效。
-- 影响表   ：users（新增 role / file_quota 两列，并回填 token_quota / token_period）。
-- 幂等性   ：可重放（ADD COLUMN IF NOT EXISTS；回填语句带 WHERE，可反复执行）。
-- 破坏性   ：无。两列都有默认值，存量行自动归为 NORMAL 档。
-- 回滚     ：
--   ALTER TABLE "public"."users" DROP COLUMN IF EXISTS "file_quota";
--   ALTER TABLE "public"."users" DROP CONSTRAINT IF EXISTS "ck_users_role";
--   ALTER TABLE "public"."users" DROP COLUMN IF EXISTS "role";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U <DB_USERNAME> -d nexus_agent -f docs/sql/012_add_user_role_and_file_quota.sql
--
-- 三档角色（与 em/UserRole.java 一一对应，改一处请同步另一处）：
--   NORMAL 普通用户  —— 注册默认：每日 100 万 token、每日 100 个文件+产物
--   TEST   测试用户  —— 每日 1000 万 token、文件+产物不限制
--   VIP    会员用户  —— 预留，当前不发放：每日 1000 万 token、每日 1000 个文件+产物
--
-- 说明     ：
--   1. role 只决定**默认值**；用户在库里的 token_quota / file_quota / token_period
--      一旦有值就以库里的为准 —— 想给谁单独加额度，直接 UPDATE 那三列即可，
--      不需要改角色，也不需要改代码重新编译。
--   2. file_quota 为 NULL 或 <= 0 表示**不限制**（沿用 003 里 token_quota 的约定）。
--      TEST 档刻意写 NULL 而不是 -1：-1 在数据库侧没有"不限制"的语义，
--      应用侧 UserRole.FILE_UNLIMITED 只是代码里的哨兵值，不落库。
--   3. 文件与产物共用一份额度：二者都落在 sys_file 表，按 create_time 统计当日条数。
--   4. 周期与 token 一致走**惰性重置**（不跑定时任务，详见 007 的说明）：
--      token_period='DAILY' 时，token 用量每天 00:00 之后首次对话时清零；
--      文件额度则是直接按 create_time >= 当天 00:00 计数，天然按天滚动，不需要重置。
--   5. 本脚本执行后，**存量用户会被回填成 NORMAL 档**（每日 100 万 token / 100 个文件）。
--      如果不希望动存量用户（比如他们原本 token_quota 为 NULL = 不限），
--      把下面第 3 段整段注释掉即可 —— 那只影响"新列有没有值"，不影响结构。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 角色列
-- ---------------------------------------------------------------------------
ALTER TABLE "public"."users"
  ADD COLUMN IF NOT EXISTS "role" varchar(16) NOT NULL DEFAULT 'NORMAL';

-- 约束单独加（幂等：先删再加），避免重放时报 "constraint already exists"
ALTER TABLE "public"."users" DROP CONSTRAINT IF EXISTS "ck_users_role";
ALTER TABLE "public"."users"
  ADD CONSTRAINT "ck_users_role"
  CHECK ("role" IN ('NORMAL', 'TEST', 'VIP'));

COMMENT ON COLUMN "public"."users"."role"
  IS '用户角色：NORMAL=普通（注册默认）/ TEST=测试 / VIP=会员（预留）。只决定额度默认值，库里的 token_quota、file_quota 优先';

-- ---------------------------------------------------------------------------
-- 2. 文件 + 产物配额列（NULL 或 <=0 表示不限制）
-- ---------------------------------------------------------------------------
ALTER TABLE "public"."users"
  ADD COLUMN IF NOT EXISTS "file_quota" bigint;

COMMENT ON COLUMN "public"."users"."file_quota"
  IS '每日文件+产物数量上限（NULL 或 <=0 表示不限制）；按 sys_file.create_time 统计当日条数，与 token 配额各自独立';

-- ---------------------------------------------------------------------------
-- 3. 存量用户回填为 NORMAL 档（不想动存量就把这一段整段注释掉）
-- ---------------------------------------------------------------------------
UPDATE "public"."users"
   SET "token_quota"   = 1000000,
       "token_period"  = 'DAILY',
       "file_quota"    = 100
 WHERE "token_quota" IS NULL
   AND "file_quota" IS NULL;

-- ---------------------------------------------------------------------------
-- 4. 常用操作备忘（按需手动执行，不在本脚本里自动跑）
-- ---------------------------------------------------------------------------
-- 把某个用户升级为测试用户（额度放大 + 文件不限制）：
--   UPDATE users SET role='TEST', token_quota=10000000, token_period='DAILY', file_quota=NULL
--    WHERE email='someone@example.com';
--
-- 升级为会员（预留档，每日 1000 万 token + 1000 个文件）：
--   UPDATE users SET role='VIP', token_quota=10000000, token_period='DAILY', file_quota=1000
--    WHERE email='someone@example.com';
--
-- 按角色统一重刷默认值（改了 UserRole 里的数字之后用）：
--   UPDATE users SET token_quota=1000000,  file_quota=100   WHERE role='NORMAL';
--   UPDATE users SET token_quota=10000000, file_quota=NULL  WHERE role='TEST';
--   UPDATE users SET token_quota=10000000, file_quota=1000  WHERE role='VIP';
--
-- 查看各角色人数与额度分布：
--   SELECT role, count(*), min(token_quota), max(token_quota), min(file_quota), max(file_quota)
--     FROM users GROUP BY role;
