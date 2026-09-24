-- ============================================================================
-- 005_add_sys_file_session_index.sql
-- ============================================================================
-- 目的     ：为 sys_file 增加 (session_id, user_id) 索引（P2-10 产物列表）。
-- 影响表   ：sys_file（仅新增一个索引，不改动任何列）。
-- 幂等性   ：可重放（CREATE INDEX IF NOT EXISTS）。
-- 破坏性   ：无。
-- 回滚     ：DROP INDEX IF EXISTS "public"."idx_sys_file_session_user";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U postgres -d nexus_agent -f docs/sql/005_add_sys_file_session_index.sql
--
-- 为什么现在才加：
--   004 建 session_id 列时刻意**没建索引** —— 当时还没有"按会话查产物"的接口，
--   按 docs/sql/README.md 的约定「无真实查询就不加索引」。
--   现在有了（ArtifactServiceImpl.listBySession / ArtifactController），按约补齐。
--
-- 对应查询（ArtifactServiceImpl.listBySession）：
--   WHERE user_id = ? AND session_id = ? AND biz_type = 'ARTIFACT' ORDER BY create_time DESC
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_sys_file_session_user
  ON "public"."sys_file" ("session_id", "user_id");
