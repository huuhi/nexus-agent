-- ============================================================================
-- 009_drop_local_knowledge_base.sql
-- ============================================================================
-- 目的     ：下线本地知识库（pgvector 向量库），知识库检索只保留第三方（乐享）。
--            对应 commit：移除 RagTool / KnowledgeBaseService / KnowledgeController
--                      / PgVectorEmbeddingFactory 与 langchain4j-pgvector 依赖。
-- 影响表   ：删除 knowledge_base、knowledge_base_file、knowledge_embedding 三张表。
-- 幂等性   ：可重放（全部 DROP ... IF EXISTS，CASCADE）。
-- 破坏性   ：⚠️ **不可逆**，会丢掉本地已入库的所有知识片段与向量。
--            执行前请确认 knowledge_base / knowledge_base_file 里没有要留的数据。
--
-- 前置条件（重要）：
--   1. **必须先部署新版 jar 再跑本脚本**。顺序反了会出问题：
--      新 jar 已经不读这三张表，先跑脚本最安全；
--      若先跑脚本再启旧 jar，旧 jar 的 EmbeddingStore Bean 初始化会直接失败。
--   2. 确认聊天附件上传不受影响 —— 本脚本**不碰** sys_file 表与
--      /api/file 接口（那是会话附件在用的，与知识库无关）。
--
-- 关于 pgvector 扩展：
--   **本脚本不删 vector 扩展**。knowledge_embedding 可能是该库内唯一用到
--   vector 的表，但删扩展是不可逆的，且若将来要恢复知识库还得重建。
--   如需一并删除扩展，请自行评估后执行（务必先确认库内无其它 vector 列/表）：
--     DROP EXTENSION IF EXISTS vector;
--     DROP EXTENSION IF EXISTS pg_trgm;   -- ⚠️ user_memory 的模糊检索还在用它，别删！
--
-- 回滚     ：本脚本删表即删数据，回滚需从备份恢复。
--            结构层面可参考 001_baseline.sql 重建三张表（含 vector(1024)
--            与 HNSW 索引），但向量数据无法凭空恢复。
--
-- 执行方式：
--   psql -h <SERVICE_IP> -U <DB_USERNAME> -d nexus_agent -f docs/sql/009_drop_local_knowledge_base.sql
-- ============================================================================

BEGIN;

-- 1. 向量表：LangChain4j 的 PgVectorEmbeddingStore 读写。
--    CASCADE 会连带删掉依赖它的索引（idx_knowledge_embedding_hnsw 等）。
DROP TABLE IF EXISTS "public"."knowledge_embedding" CASCADE;

-- 2. 知识库-文件关联表（入库进度 / fail_reason 记在这里）。
DROP TABLE IF EXISTS "public"."knowledge_base_file" CASCADE;

-- 3. 知识库主表。
--    必须放在最后：前两张表对它有外键引用。
DROP TABLE IF EXISTS "public"."knowledge_base" CASCADE;

COMMIT;

-- ---------------------------------------------------------------------------
-- 执行后自检：下面三条都应该返回 0 行
--   SELECT count(*) FROM pg_tables
--    WHERE schemaname='public'
--      AND tablename IN ('knowledge_base','knowledge_base_file','knowledge_embedding');
--
--   -- 确认会话附件链路没被牵连（应当仍有 sys_file 表）
--   SELECT count(*) FROM pg_tables
--    WHERE schemaname='public' AND tablename='sys_file';
--
--   -- 确认长期记忆的 pg_trgm 索引还在（user_memory 模糊检索依赖它）
--   SELECT count(*) FROM pg_indexes
--    WHERE schemaname='public' AND indexname LIKE '%user_memory%';
-- ---------------------------------------------------------------------------
