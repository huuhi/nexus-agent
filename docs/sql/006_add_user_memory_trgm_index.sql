-- ============================================================================
-- 006_add_user_memory_trgm_index.sql
-- ----------------------------------------------------------------------------
-- 目的：P2-7 —— 长期记忆检索从「SQL LIKE 全表扫描」升级为 pg_trgm 模糊检索
--      （决策 D4 选定：先 pg_trgm，pgvector 语义检索留到记忆量上来之后）。
-- 影响表：user_memory（**只加索引、不加列、不动数据**）
-- 破坏性：无
-- 幂等性：可重放（IF NOT EXISTS）
-- 依赖：服务端必须**已安装 pg_trgm 扩展**（contrib 模块）。
--       001_baseline.sql 已验证 `CREATE EXTENSION vector` 成功，
--       同源的 postgresql-contrib 里一般就有 pg_trgm；若报
--       `could not open extension control file` 说明服务端没装，需先装 contrib。
-- ============================================================================

-- 1) 扩展：三元文法（trigram）。中文没有空格分隔，PG 内置 tsvector 分词对中文
--    效果极差；pg_trgm 按**字符**切三元组，对中文子串/错字/部分重叠都有效。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2) 索引：加速 content ILIKE '%关键词%'
--    （GIN + gin_trgm_ops 支持 LIKE / ILIKE / % / ~ 等操作符）
CREATE INDEX IF NOT EXISTS idx_user_memory_content_trgm
    ON "public"."user_memory" USING gin ("content" gin_trgm_ops);

COMMENT ON INDEX "public"."idx_user_memory_content_trgm"
    IS '长期记忆 content 的三元文法索引（P2-7 / D4=pg_trgm）：加速 ILIKE ''%kw%'' 模糊检索';


-- ============================================================================
-- 回滚
-- ============================================================================
-- DROP INDEX IF EXISTS "public"."idx_user_memory_content_trgm";
-- （扩展一般不必 DROP；确需清理：DROP EXTENSION IF EXISTS pg_trgm;）
--
-- 回滚后 Java 侧行为：getMemory 的字面匹配（ILIKE）照常可用（退化成全表扫描），
-- 相似检索兜底会失败并被捕获降级 —— 不会报错，只是检索质量回退。


-- ============================================================================
-- 执行后验证
-- ============================================================================
-- 1) 扩展已装
-- select extname from pg_extension where extname = 'pg_trgm';      -- 应返回 1 行
--
-- 2) 索引已建
-- select indexname, indexdef from pg_indexes
-- where schemaname='public' and tablename='user_memory';
--
-- 3) 相似检索可用（把 '喜欢看科幻电影' 换成你库里真实存在的记忆）
-- select content, similarity(content, '喜欢看科幻电影') AS score
-- from user_memory order by score desc limit 5;
--
-- 4) 确认 ILIKE 走索引（小表可能仍选全表扫描，属正常）
-- explain analyze select id from user_memory where content ilike '%科幻%';
