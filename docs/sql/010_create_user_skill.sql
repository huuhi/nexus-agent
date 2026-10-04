-- ============================================================================
-- 010_create_user_skill.sql
-- ============================================================================
-- 目的     ：新增「用户技能」表，让用户上传（.zip / .md）或用模型生成自己的 Skill。
--            对应 minmax / Claude Code 的「技能库」形态：官方 / 社区（用户贡献）/ 私有。
--            Skill 本体是「一个文件夹 + 一个 SKILL.md」，**不是可执行代码**。
-- 影响表   ：新建 user_skill（不改动任何既有表）。
-- 幂等性   ：可重放（CREATE TABLE IF NOT EXISTS）。
-- 破坏性   ：无，纯新增表。
-- 回滚     ：DROP TABLE IF EXISTS "public"."user_skill";
-- 执行方式 ：
--   psql -h <SERVICE_IP> -U <DB_USERNAME> -d nexus_agent -f docs/sql/010_create_user_skill.sql
--
-- 为什么存数据库而不是解压到 skills/ 目录（决策）：
--   skills/README.md 明确写了「**不要把用户可写的上传目录配成 root-dir**」。
--   上传 zip 解压落盘要额外解决路径穿越、zip 炸弹、清理时机、多用户隔离四件事，
--   而 langchain4j 的 Skills.from(...) 接受任意 Skill 实现 —— 正文与资源都可以
--   放内存里直接装配。于是落盘这一步被整个消掉，只把「文本」存进 DB。
--
-- 字段说明：
--   name          技能名，全局唯一（模型 activate_skill 按名匹配，必须唯一）。
--                 约束为小写字母/数字/连字符，与 Claude Code 的命名一致。
--   description   给**模型**看的触发条件，必须写「何时用」。
--   content       SKILL.md 里 frontmatter 之后的正文（Markdown）。
--   frontmatter   解析出的原始 YAML 头（jsonb）。保留是为了将来支持
--                 allowed-tools / license / version 等官方规范字段。
--   resources     附属资源，jsonb 数组：[{"path":"notes.md","content":"..."}]。
--                 对应 read_resource(skillName, relativePath) 能读到的那些文件。
--                 ⚠️ scripts/ 下的内容**不入库**（langchain4j 刻意读不到它），
--                   约定是「脚本供沙盒执行」，由技能正文指示模型自行创建。
--   visibility    PRIVATE=只有作者可见；PUBLIC=所有登录用户可见（社区共享）。
--   source        UPLOAD=上传来的；AI_GENERATED=模型生成的；BUILTIN=官方内置
--                 （官方技能不进本表，它们来自部署目录，所以只是列表展示用的枚举值）。
--   enabled       软删除/下架。false 时不进入对话上下文，但记录留着。
--   use_count     被「使用」的次数，列表页的热门排序用它。
--
-- 安全说明：
--   * content / resources 都是**纯文本**，模型只把它当提示词读，不会执行。
--   * 唯一需要防的是「解压炸弹」与「路径穿越」，两者都在应用层
--     （SkillPackageParser）拦掉，根本没有落盘这一步。
-- ============================================================================

CREATE TABLE IF NOT EXISTS "public"."user_skill" (
  "id"           bigserial PRIMARY KEY,
  "user_id"      bigint       NOT NULL,
  "name"         varchar(64)  NOT NULL,
  "description"  varchar(512) NOT NULL,
  "content"      text         NOT NULL,
  "frontmatter"  jsonb        NOT NULL DEFAULT '{}'::jsonb,
  "resources"    jsonb        NOT NULL DEFAULT '[]'::jsonb,
  "visibility"   varchar(16)  NOT NULL DEFAULT 'PRIVATE',
  "source"       varchar(16)  NOT NULL DEFAULT 'UPLOAD',
  "enabled"      boolean      NOT NULL DEFAULT true,
  "use_count"    integer      NOT NULL DEFAULT 0,
  "created_at"   timestamp    NOT NULL DEFAULT now(),
  "updated_at"   timestamp    NOT NULL DEFAULT now(),
  -- 枚举值收敛在 DB 层：写错的值直接被拒，而不是混进数据里
  CONSTRAINT "ck_user_skill_visibility" CHECK ("visibility" IN ('PRIVATE', 'PUBLIC')),
  CONSTRAINT "ck_user_skill_source"     CHECK ("source"     IN ('UPLOAD', 'AI_GENERATED', 'BUILTIN'))
);

COMMENT ON TABLE  "public"."user_skill" IS '用户上传或用模型生成的技能（社区共享）';
COMMENT ON COLUMN "public"."user_skill"."user_id"     IS '作者的用户 id';
COMMENT ON COLUMN "public"."user_skill"."name"        IS '技能名，全局唯一，小写字母/数字/连字符';
COMMENT ON COLUMN "public"."user_skill"."description" IS '给模型看的触发条件（何时用）';
COMMENT ON COLUMN "public"."user_skill"."content"     IS 'SKILL.md 的正文（不含 frontmatter）';
COMMENT ON COLUMN "public"."user_skill"."frontmatter" IS '原始 YAML frontmatter（jsonb 文本）';
COMMENT ON COLUMN "public"."user_skill"."resources"   IS '附属资源数组 [{"path":...,"content":...}]（jsonb 文本）';
COMMENT ON COLUMN "public"."user_skill"."visibility"  IS 'PRIVATE=仅作者可见；PUBLIC=所有登录用户可见';
COMMENT ON COLUMN "public"."user_skill"."source"      IS 'UPLOAD / AI_GENERATED / BUILTIN（仅列表展示用）';
COMMENT ON COLUMN "public"."user_skill"."enabled"     IS 'false 表示下架：不进入对话上下文，但记录保留';
COMMENT ON COLUMN "public"."user_skill"."use_count"   IS '被使用的次数，列表页热门排序依据';

-- 技能名全局唯一：模型 activate_skill 只传名字，重名会让路由变得不确定
CREATE UNIQUE INDEX IF NOT EXISTS "idx_user_skill_name"
  ON "public"."user_skill" ("name");

-- 「我的技能」列表：按作者查，常带上按更新时间倒序
CREATE INDEX IF NOT EXISTS "idx_user_skill_user"
  ON "public"."user_skill" ("user_id", "updated_at" DESC);

-- 社区列表：过滤 visibility='PUBLIC' AND enabled，按热度排序
CREATE INDEX IF NOT EXISTS "idx_user_skill_public"
  ON "public"."user_skill" ("use_count" DESC, "updated_at" DESC)
  WHERE "visibility" = 'PUBLIC' AND "enabled" = true;

-- 关键词模糊检索。
-- ⚠️ pg_trgm 扩展在 006 里创建。如果 006 还没执行，直接 CREATE INDEX ... gin_trgm_ops
-- 会报「operator class gin_trgm_ops does not exist」而中断整个脚本 —— 用户于是以为
-- 表没建成功（其实前面的 CREATE TABLE 已经提交了）。所以这里先探测扩展再决定建不建索引：
-- 扩展不存在就跳过索引，LIKE 查询照样能工作，只是数据量大时慢一点。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
    CREATE INDEX IF NOT EXISTS "idx_user_skill_name_trgm"
      ON "public"."user_skill" USING gin ("name" gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS "idx_user_skill_desc_trgm"
      ON "public"."user_skill" USING gin ("description" gin_trgm_ops);
  ELSE
    RAISE NOTICE 'pg_trgm 未安装（先执行 006），跳过 user_skill 的模糊检索索引';
  END IF;
END
$$;