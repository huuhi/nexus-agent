-- ============================================================================
-- 011_add_run_id.sql
-- ============================================================================
-- 目的     ：给「AI 产物」补上归属信息 —— 让前端能确定「哪个文件是哪一轮对话产出的」。
--            诉求文档：产物归属-后端诉求.md（方案 B）。
-- 影响表   ：sys_file（加 1 列）、chat_memory（加 1 列）。
-- 幂等性   ：可重放（ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS）。
-- 破坏性   ：无，只加**可空**列，不改写既有数据。
-- 回滚     ：
--   ALTER TABLE "public"."sys_file"    DROP COLUMN IF EXISTS "run_id";
--   ALTER TABLE "public"."chat_memory" DROP COLUMN IF EXISTS "run_id";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U <DB_USERNAME> -d nexus_agent -f docs/sql/011_add_run_id.sql
--
-- ---------------------------------------------------------------------------
-- 背景：为什么必须加这两列
-- ---------------------------------------------------------------------------
-- GET /api/artifact?sessionId= 只说「这个会话产出了哪些文件」，
-- 不说「哪个文件是哪一轮产出的」，而 GET /api/history/{sessionId} 里又通常没有
-- ARTIFACT 行。于是刷新页面后，前端在**数据上**无法把产物归到某一轮，
-- 只能全部堆到右侧「成果文件」面板，对话里看不到。
--
-- runId = SSE 信封里那个 runId（一次 Run 的 trace_id），在 ChatServiceImpl 里
-- 由 UUID 生成（16 位十六进制）。同一个 runId 同时写入：
--   · sys_file.run_id     —— 这一轮产出的每个文件
--   · chat_memory.run_id  —— 这一轮写入的每一条历史消息
-- 前端用「字符串相等」匹配：artifact.runId === message.runId → 内联到那条消息末尾。
--
-- ⚠️ 关键语义：runId 是「**产出该文件的那次运行**」，不是当前请求的运行。
--    所以它必须**持久化**（跟着文件记录与历史消息落库），不能是进程内临时 id，
--    否则重启之后就归不上了。
--
-- ---------------------------------------------------------------------------
-- 为什么选方案 B（加 run_id）而不是方案 A（历史里补 ARTIFACT 行）
-- ---------------------------------------------------------------------------
-- chat_memory 这张表**同时是 LangChain4j 的 ChatMemoryStore**：
-- PgChatMemoryStore.getMessages() 会对查出来的**每一行**执行
-- ChatMessageDeserializer.messageFromJson(...)。如果往里插 type='ARTIFACT' 的
-- 非消息行，模型上下文会被污染（反序列化直接失败），
-- 增量写入的「锚点去重」也会被这些行打乱。
-- 方案 B 只加列、不新增行，完全不碰记忆语义 —— 这是选它的唯一但决定性的理由。
--
-- ---------------------------------------------------------------------------
-- 向后兼容
-- ---------------------------------------------------------------------------
-- 两列都可为 NULL：本脚本执行**之前**落库的历史数据与产物没有 runId，
-- 接口照常返回 null，前端按「归属不明」处理（只进面板、不进对话），不会报错。

-- 1) sys_file：产物记录归属到某次 Run
ALTER TABLE "public"."sys_file"
    ADD COLUMN IF NOT EXISTS "run_id" varchar(64);

COMMENT ON COLUMN "public"."sys_file"."run_id" IS
    '产出该文件那次运行的 runId（SSE 信封里的 trace_id）。仅 biz_type=ARTIFACT 有值；'
    '同一轮产出的多个文件共用同一个 runId。历史数据为 NULL = 归属不明。';

-- 2) chat_memory：历史消息归属到某次 Run
ALTER TABLE "public"."chat_memory"
    ADD COLUMN IF NOT EXISTS "run_id" varchar(64);

COMMENT ON COLUMN "public"."chat_memory"."run_id" IS
    '写入该消息那次运行的 runId。与 sys_file.run_id 配套：前端用 runId 相等把产物'
    '内联到对应那一轮。本脚本执行前的历史行为 NULL，前端会跳过（不冒充某一轮产出的）。';

-- 3) 索引：**刻意不加**（按 docs/sql/README.md 的约定「无真实查询就不加索引」）。
--    runId 的匹配是**前端**做的（拿到两份列表后在浏览器里比对字符串），
--    服务端没有任何 `WHERE run_id = ?` 的查询：
--      · 产物列表仍是 `WHERE user_id=? AND session_id=? AND biz_type=?`
--        （已有 idx_sys_file_session_user 覆盖）；
--      · 历史仍是 `WHERE session_id=? AND user_id=?`。
--    因此加 (session_id, run_id) 索引只是给写入增加成本，没有收益。
--    将来若出现「按 runId 查产物/查消息」的服务端接口，再补不迟。

-- 4) 自检（可选，执行后手动确认）：
--    -- 每一轮的产物与历史是否对得上
--    SELECT run_id, count(*) FROM sys_file WHERE biz_type = 'ARTIFACT' GROUP BY run_id;
--    SELECT run_id, count(*) FROM chat_memory GROUP BY run_id;
