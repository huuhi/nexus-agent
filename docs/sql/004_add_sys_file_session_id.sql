-- ============================================================================
-- 004_add_sys_file_session_id.sql
-- ============================================================================
-- 目的     ：sys_file 增加 session_id 列（P2-10 产物交付）。
--            用于把 AI 产出的「交付物」按会话归属，便于会话内追溯与清理。
-- 影响表   ：sys_file（仅新增一列，不改动既有列与索引）。
-- 幂等性   ：可重放（ADD COLUMN IF NOT EXISTS）。
-- 破坏性   ：无（可空列，存量行保持 NULL）。
-- 回滚     ：ALTER TABLE "public"."sys_file" DROP COLUMN IF EXISTS "session_id";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U postgres -d nexus_agent -f docs/sql/004_add_sys_file_session_id.sql
--
-- 说明     ：
--   1. biz_type 已有列（varchar(64) 存枚举名），**新增 ARTIFACT 取值不需要改表**，
--      只在应用侧枚举里加一项（见 em/BizType.java）。
--   2. session_id 可空：只有产物的记录会写它；用户上传的聊天附件不依赖本列。
--   3. 未建索引：当前没有"按会话查产物"的接口。等真要做产物列表时再加
--      （按 docs/sql/README.md 的约定，无真实查询就不加索引）。
-- ============================================================================

ALTER TABLE "public"."sys_file"
  ADD COLUMN IF NOT EXISTS "session_id" varchar(64);

COMMENT ON COLUMN "public"."sys_file"."session_id"
  IS '归属会话 ID（仅 biz_type=ARTIFACT 写；用于产物按会话追溯与清理）';
