# docs/sql — 数据库变更管理

## 为什么有这个目录

项目**没有引入 Flyway / Liquibase**，schema 完全靠手工执行 SQL。
结果就是：新环境搭不起来、各环境表结构悄悄漂移、改表没有记录可查。

本目录是**过渡方案**：在正式引入迁移工具（见 `重构计划.md` P3）之前，
所有表结构变更必须以可重放的 SQL 文件形式落在这里，并按序号命名。

## 命名约定

```
docs/sql/
├── README.md              ← 本文件
├── 001_baseline.sql       ← 基线：全量建表（待导出，见下）
├── 002_xxx.sql            ← 每次变更一个文件，序号递增，只增不改
└── ...
```

规则：

1. **只增不改。** 已经执行过的文件不允许再修改内容，需要调整就新建下一个序号的文件。
2. **必须可重放。** 推荐写成幂等形式：`create table if not exists ...`、
   `alter table ... add column if not exists ...`、`create index if not exists ...`。
3. **每个文件顶部写注释**：变更目的、影响表、是否可回滚。
4. **配套回滚**：破坏性变更（删列/删表/改类型）必须在同文件内附上回滚 SQL 的注释块。
5. 应用代码里的实体（`nexus-agent-domain`）与这里的 SQL **必须同步修改**，不能只改一边。

## ⚠️ 基线 DDL 尚未导出（待办）

`001_baseline.sql` **目前不存在**，需要从现有开发库导出。我（AI）不会凭实体类反推 DDL——
PostgreSQL 的枚举类型、`jsonb`、默认值、索引、约束这些细节靠猜一定会错，
而一份错误的基线 schema 比没有更危险。

请在**已有数据的开发库**上执行下面这条命令导出，然后保存为 `docs/sql/001_baseline.sql`：

```bash
# 只导出结构（不含数据），并包含 enum 类型定义
pg_dump --schema-only --no-owner --no-privileges \
        -h <SERVICE_IP> -U postgres -d nexus_agent \
        > docs/sql/001_baseline.sql
```

导出后请人工检查这几点：
- 文件里必须包含自定义枚举类型 `message_type`（见下方"已核实事实"）
- 必须包含 `pgvector` 扩展的创建语句（`create extension if not exists vector;`）
- `knowledge_embedding` 表**不要**写进基线 —— 它由应用自动创建（见下）

## 表清单（11 张业务表 + 1 张自动表）

| 表 | 对应实体 | 说明 |
|---|---|---|
| `users` | `User` | 账号、配额、头像 |
| `user_config` | `UserConfig` | `llm_api_token`(jsonb, 加密)、`mcp_token`(加密)、`salt` |
| `chat_memory` | `ChatHistory` | 消息 JSON，按 `session_id` 分组 |
| `chat_history_list` | `ChatHistoryList` | 会话列表与标题 |
| `user_memory` | `UserMemory` | 长期记忆 |
| `sys_file` | `SysFile` | 上传文件元数据 + OSS URL |
| `knowledge_base` | `KnowledgeBase` | 知识库定义 |
| `knowledge_base_file` | `KnowledgeBaseFile` | 知识库↔文件关联 + 向量化状态 |
| `mcp_information` | `McpInformation` | MCP 配置（`header` jsonb） |
| `skill_mcp_information` | `SkillMcpInformation` | Skill / MCP 共用表（`is_mcp` 区分） |
| `system_log` | `SystemLog` | AI 主动记录的日志 |
| `knowledge_embedding` | — | **由 LangChain4j `PgVectorEmbeddingStore` 自动创建**
（`PgVectorEmbeddingFactory` 中 `createTable(true)`），不要手工建 |
| `chat_memory.stream_id` 等 | — | 见下 |

## 已核实事实（来自 mapper XML 与代码，可直接采信）

这些是从实际 SQL 里读出来的，不是推测：

```sql
-- 1) 存在自定义枚举类型 message_type，chat_memory.type 使用它
--    来源：ChatMemoryMapper.xml → #{item.type}::message_type
create type message_type as enum (...);   -- 具体枚举值需从库中导出确认

-- 2) chat_memory.session_id 是 uuid，不是 varchar
--    来源：ChatMemoryMapper.xml → #{item.sessionId}::uuid
--    因此接口传入的 sessionId 必须是合法 UUID 字符串

-- 3) chat_memory.content 是 jsonb（存 LangChain4j 序列化后的消息）
--    来源：ChatMemoryMapper.xml → #{item.content}::jsonb
--    注意：JDBC 连接串需要 stringtype=unspecified，否则 jsonb 插入会报类型错误。
--    ⚠️ application-prod.yml 现有 URL 写作
--       ...?reWriteBatchedInserts=true?stringtype=unspecified
--    两个参数之间用了 '?' 而不是 '&'，第二个参数实际上不生效，需要修（见重构计划）。

-- 4) mcp_information.header 是 jsonb
--    来源：McpInformationMapper.xml → #{item.header}::jsonb

-- 5) 向量维度固定 1024（text-embedding-v4）
--    来源：application-prod.yml dimensions: 1024 + PgVectorEmbeddingFactory
--    换向量模型必须同步重建 knowledge_embedding，否则维度不匹配

-- 6) 以下表刻意不绑外键：chat_memory、chat_history_list、mcp_information
--    原因见 开发日志.md（新会话先插列表行、标题异步更新，绑外键会让插入顺序变复杂）
```

## 环境准备（新机器）

```sql
create extension if not exists vector;   -- pgvector，必须
```

`users` / `user_config` 等表请用基线 SQL 建；`knowledge_embedding` 交给应用自动创建。
