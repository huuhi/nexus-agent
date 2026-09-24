# nexus-agent

基于 **Spring Boot 3.5 + LangChain4j** 的 AI Agent 平台后端：多模型对话（SSE 流式）、
MCP 工具接入、E2B 云沙盒执行代码、RAG 知识库、用户长期记忆。

> **开发前请先读 [AGENTS.md](./AGENTS.md)** —— 那里有依赖版本、文件地图、接口一览、
> 已知技术债清单和开发规范。当前正按 [重构计划.md](./重构计划.md) 分阶段重构。

---

## 5 分钟跑起来

### 0. 前置依赖

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | **21** | 项目用 Java 21 + 虚拟线程 |
| Maven | 3.9+ | 也可用项目自带的 `./mvnw`（首次会自行下载） |
| PostgreSQL | **16** + **pgvector 扩展** | 向量检索必需，`docs/sql/001_baseline.sql` 会 `CREATE EXTENSION vector` |
| Redis | 任意版本 | 缓存用户配置、验证码、会话归属 |
| 外部服务 | E2B API Key、阿里云 OSS、LLM API Key | 见下方配置 |

### 1. 建库并初始化表结构

```bash
# 建库（pgvector 扩展由基线脚本创建，但要确保服务端已安装该扩展）
createdb -h <PG_HOST> -U postgres nexus_agent

# 1) 执行基线（12 张表 + 2 枚举 + 4 外键 + 9 索引）
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/001_baseline.sql

# 2) 按序号执行后续增量（顺序不能颠倒）
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/002_drop_skill_mcp_information.sql
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/003_add_user_token_quota.sql
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/004_add_sys_file_session_id.sql
```

> 也可以用 Navicat：右键库 → 运行 SQL 文件，**按 001 → 002 → 003 → 004 的顺序**各跑一次。
> ⚠️ 基线脚本会先 DROP 再重建，**只能在空库或允许清空的环境执行**；
> 增量脚本都是幂等的（可重复执行）。
> ⚠️ **增量脚本必须执行**：实体已经包含新增的列，库里缺列会导致登录/查用户直接报
> `column "token_quota" does not exist`。
> 表结构与设计理由见 [docs/sql/README.md](./docs/sql/README.md)。

### 2. 生成配置文件

仓库里**没有** `application-dev.yml`（被 `.gitignore` 忽略了，因为要放密钥），
而 `application.yml` 里 `spring.profiles.active=dev`，所以**必须先自己创建**：

```bash
cp nexus-agent-web/src/main/resources/application-dev.yml.example \
   nexus-agent-web/src/main/resources/application-dev.yml
# 然后编辑它，把所有 <<...>> 占位符换成真实值
```

沙盒服务同理：

```bash
cp nexus_agent_box/.env.example nexus_agent_box/.env
# 填入 E2B_API_KEY 等
```

### 3. 还需要 3 个环境变量（不在 yml 里）

| 变量 | 是否必需 | 说明 |
|---|---|---|
| `JWT_SECRET` | 建议设置 | Base64 的 HMAC-SHA256 密钥。**不设置会随机生成 → 每次重启后所有 token 失效**。生成：`openssl rand -base64 32` |
| `API_KEY_SECRET` | **必需** | 用户 API Key 的加密主密钥。不设置则首次保存配置时报错；**设置后不要更改**，否则已加密的密钥无法解密 |
| `BASE_URL` | 可选 | 沙盒服务地址，默认 `http://localhost:8000` |

### 4. 启动沙盒服务（独立进程，8000 端口）

```bash
cd nexus_agent_box
uv run main.py          # 开发模式
# 或
docker compose up -d    # 容器模式
```

### 5. 启动应用（8080 端口）

```bash
./mvnw spring-boot:run -pl nexus-agent-web
# 或
mvn clean package -DskipTests && java -jar nexus-agent-web/target/nexus-agent-web-*.jar
```

---

## 冒烟测试

鉴权用**自定义请求头 `token`**（不是 `Authorization: Bearer`）。

```bash
# 1) 发送邮箱验证码（会真的发邮件，需 mail 配置正确）
curl -X POST "http://localhost:8080/api/common/email?email=you@example.com"

# 2) 用验证码登录（未注册会自动注册），返回 JWT
curl -X POST http://localhost:8080/api/user/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","code":"邮件里的验证码","type":"CODE"}'

# 3) 带上 token 发起流式对话
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'token: <上一步返回的JWT>' \
  -d '{"messages":[{"type":"TEXT","content":"你好"}],"enableRag":false}'
```

第 3 步返回 SSE 流，事件类型：`message`(THINK / CONTENT) / `tool_execution` /
`tool_execution_result` / `session_id` / `finish`，契约见 [AGENTS.md §6.2](./AGENTS.md)。

浏览器里还有一份极简调试页 `static/showHistory.html`，
启动后访问 `http://localhost:8080/showHistory.html`。

---

## 常见启动失败

> 💡 应用启动时会做一次**配置自检**（`StartupConfigValidator`）：把必需配置（数据库连接串、
> 对话模型 Key、`API_KEY_SECRET`）与建议配置（`JWT_SECRET`、`AI_KEY`、Redis、SMTP）**一次性**检查完，
> 缺什么、各自导致哪项能力不可用，都会在启动日志里列成清单 —— 不用再"改一个、起一次"来回试。
> 必需配置缺失会**阻止启动**；只想临时带病启动可加 `nexus.agent.config.fail-fast=false`。

| 现象 | 原因 |
|---|---|
| 启动日志出现 `配置自检未通过：缺少 N 项必需配置` | 按日志里列出的清单逐项补齐（每项都写了「配置项 / 后果」） |
| 启动日志出现 `建议配置缺失` 横幅 | 只是某项能力不可用（RAG / 标题生成 / 邮箱验证码等），应用仍可启动，按需补 |
| 占位符解析失败 / datasource 报错 | 没建 `application-dev.yml`，或占位符没替换完 |
| `relation "sys_file" does not exist` | 没执行基线 SQL，或执行的是旧的、不完整的结构 |
| `type "vector" does not exist` | PostgreSQL 没装 pgvector 扩展 |
| `缺少环境变量 API_KEY_SECRET` | 见上方第 3 步 |
| 启动后 token 立刻失效 | 没设 `JWT_SECRET`，每次重启都换成了随机密钥 |
| 沙盒工具报 `Connection refused` | FastAPI 沙盒服务没启动，或 `BASE_URL` 指向不对 |
| 邮箱验证码收不到 | QQ 邮箱要用 **SMTP 授权码**，不是邮箱登录密码 |
| `column "file_name" does not exist` | 数据库是重构前的旧结构，重跑一次基线 SQL |
| 技能「配了但没反应」 | `nexus.agent.skill.root-dir` 是相对**应用工作目录**解析的；看启动日志里的 `Skill 目录不存在，Skill 能力为空：<绝对路径>`，必要时改成绝对路径 |

---

## 运行时可调参数（`nexus.agent.*`）

**全部有代码默认值，不写也能启动**；改完重启生效。完整清单见 [AGENTS.md §15](./AGENTS.md)。

| 参数 | 默认 | 什么时候需要改它 |
|---|---|---|
| `nexus.agent.sse.timeout` | `120s` | 复杂任务被提前掐断时调大（要大于最慢一次模型调用） |
| `nexus.agent.memory.max-tokens` | `100000` | 上下文太长想省 token 时调小。⚠️ **别小于 600**，否则模型会「失忆」只回寒暄 |
| `nexus.agent.sandbox.idle-timeout` | `8m` | 沙盒空闲回收时间。**必须小于沙盒服务的 600s**，否则还没轮到我们回收就被 E2B 收走 |
| `nexus.agent.tools.http-timeout` | `100s` | 沙盒里跑长任务（装依赖、跑大脚本）超时时调大；**别超过 `sse.timeout`** |
| `nexus.agent.tools.duplicate-threshold` | `2` | 模型反复用**完全相同的参数**刷同一工具会被拦截（第 3 次起）。误伤时调大，设 `0` 关闭 |
| `nexus.agent.observability.enabled` | `true` | 每次对话结束会输出一行 `RUN runId=... tokens=... fee=... tools=...` 汇总日志；不想看到就设 `false` |
| `nexus.agent.observability.model-prices` | 无 | **想让汇总里显示花了多少钱就配它**（每 100 万 token 单价）；不配显示 `fee=unpriced` |
| `nexus.agent.model.providers` | 内置 2 条 | 中转/自建网关的「支持哪些额外参数」需自行声明，否则一律不下发（避免 400）。见 [§6.3](./AGENTS.md) |
| `nexus.agent.skill.root-dir` | `skills` | 技能目录位置。**相对应用工作目录解析**，换启动方式后技能「消失」时改成绝对路径 |
| `nexus.agent.security.enabled` | `true` | 本地调试不想带 token 时设为 `false`（关闭时启动会打 WARN）。**生产必须为 true** |
| `nexus.agent.startup.fail-fast` | `true` | 启动自检发现必需配置缺失时是否阻止启动；只想临时带病启动再设 `false` |
| `nexus.agent.quota.enabled` / `.default-quota` | `true` / `0` | token 配额校验；`default-quota` 是新用户默认额度（`<=0` 不限）。**给某人限额改库**：`UPDATE users SET token_quota = N WHERE id = ?`（需先执行 `docs/sql/003`） |

---

## 目录结构

```
nexus-agent (parent pom)
├── nexus-agent-common    常量、枚举、异常、JWT、ThreadLocal 上下文
├── nexus-agent-domain    实体 / DTO / VO
├── nexus-agent-mapper    MyBatis-Plus Mapper 接口 + XML
├── nexus-agent-service   LangChain4j 集成、工具、业务 Service、Config
├── nexus-agent-web       Controller + 启动类
├── nexus_agent_box/      Python FastAPI 沙盒服务（E2B），uv 管理
└── docs/sql/             数据库基线与变更管理
```

依赖链：`web → service → mapper → domain → common`

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [AGENTS.md](./AGENTS.md) | **开发入口**：依赖版本、文件地图、接口一览、核心机制、技术栈、技术债清单、开发规范 |
| [重构计划.md](./重构计划.md) | 分阶段重构计划、任务表、验收标准、里程碑 |
| [skills/README.md](./skills/README.md) | 技能（Skill）目录约定、SKILL.md 写法、`scripts/` 与文档资源的区别 |
| [docs/sql/README.md](./docs/sql/README.md) | 数据库基线说明、设计理由、索引清单、变更约定 |
| [开发日志.md](./开发日志.md) | 项目演进历史记录 |
