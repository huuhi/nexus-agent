# CLAUDE.md

**本文档已停止维护。开发入口请阅读 [AGENTS.md](./AGENTS.md)。**

---

## 为什么改了

原 `CLAUDE.md` 长期未更新，与代码实际状态出现多处冲突，会**主动误导**人和 AI。
2026-09-23 已逐项核对代码，确认以下说法是错的，记录在此以免之后被再次引用：

| 原文档说法 | 实际情况 |
|---|---|
| 默认 profile 是 `prod` | 实际是 `dev`（见 `application.yml` 的 `spring.profiles.active`） |
| 接口路径 `POST /chat/stream` | 实际是 **`POST /api/chat/stream`**，所有 Controller 都带 `/api` 前缀 |
| 沙盒是 Docker + docker-compose | 实际是 **E2B 云沙盒**（Python 依赖 `e2b-code-interpreter`），`docker-compose.yml` 只是把 FastAPI 本身容器化 |
| Mapper 有 12 个 | 实际 **11 个** |
| `MemoryTool.ragSearch` 按条件注册 | 实际注册的是整个 `MemoryTool` 实例；`RagTool` 曾是未注册的死代码 |
| 长期记忆用 pgvector 向量检索 | 实际已降级为 **SQL LIKE 模糊搜索**，向量代码被注释 |
| 未提及认证方式 | 实际用自定义请求头 **`token`**（不是 `Authorization: Bearer`） |

## 现在去哪看

| 内容 | 文件 |
|---|---|
| 开发规范、依赖版本、模块地图、文件地图、接口一览、技术债清单、决策记录 | **[AGENTS.md](./AGENTS.md)** |
| 分阶段重构计划、任务表、验收标准、里程碑 | [重构计划.md](./重构计划.md) |
| 项目演进过程中的随手记录（历史流水） | [开发日志.md](./开发日志.md) |
| 数据库变更管理约定 | [docs/sql/README.md](./docs/sql/README.md) |

> 如果你（AI Agent）是被自动加载进来的：**请改读 `AGENTS.md`**，本文件无需再更新。
