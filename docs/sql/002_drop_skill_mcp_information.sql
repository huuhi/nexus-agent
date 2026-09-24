-- ============================================================================
-- 002_drop_skill_mcp_information.sql
-- ============================================================================
-- 目的     ：删除 skill_mcp_information 表。
--            随 P2-1「Skill 本地目录扫描」方案落地（决策 D3），
--            Skill 不再入库注册：实体 SkillMcpInformation、Mapper、Service
--            已在代码中整体删除（见 skills/README.md）。
--            MCP 服务的注册仍由 mcp_information 表承担，不受本文件影响。
-- 影响表   ：仅 skill_mcp_information（其外键 fk_skill_mcp_user 随表级联删除）。
-- 幂等性   ：可重放（DROP TABLE IF EXISTS）。
-- 破坏性   ：是（删表丢数据）。该表从未被任何代码路径写入过
--            （SkillMcpInformationServiceImpl 全链路无人调用，见 AGENTS.md §12.22），
--            理论上不存在有效数据；执行前可先 SELECT 确认。
-- 回滚     ：若需恢复，重跑 docs/sql/001_baseline.sql 的 3.12 节建表语句
--            （CREATE TABLE + 3 条 COMMENT + 外键 fk_skill_mcp_user）。
-- 执行方式 ：
--    psql -h <SERVICE_IP> -U postgres -d nexus_agent -f docs/sql/002_drop_skill_mcp_information.sql
-- ============================================================================

DROP TABLE IF EXISTS "public"."skill_mcp_information";
