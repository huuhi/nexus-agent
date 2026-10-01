# AGENTS.md — nexus-agent 开发指南（面向 AI Agent / 协作者）

> 本文件是 **唯一权威的开发入口文档**。`CLAUDE.md` 已过期（多处与代码不符），请以本文件为准；
> 若两者冲突，以本文件 + 实际代码为准，并顺手修正另一份。
>
> 最后核对时间：2026-09-23 ｜ 核对基准 commit：`86f3a07`（本地快照 9.15）

---

## 0. 铁律（Read Before Coding）

1. **只在 `ds` 分支开发。** 禁止在 `master` / `main` 上直接提交。`ds` 是本项目唯一允许推进的分支。
2. **改动前先跑通编译。** 见 [§3 构建](#3-构建--运行--测试)。编译不过的提交等于没做。
3. **依赖版本只允许在父 `pom.xml` 的 `<dependencyManagement>` / `<properties>` 里改。** 子模块不要写裸 `version`（现状有违反，见 [§12](#12-已知技术债与陷阱务必先读)）。
4. **不要把密钥写进代码。** 一律走环境变量。当前代码里已经存在一处硬编码密钥泄漏，见 [§12.1](#121-p0--安全类)。
5. **改完一个模块要 `mvn -pl <module> -am compile` 验证依赖链**，不要只看 IDEA 不报红。
6. **数据库 schema 变更必须同时提供 SQL**（目前无迁移工具，见 [§9](#9-数据库)）。
7. 新增工具（Tool）时，**必须同时考虑：注册位置、失败降级、超时、是否需要沙盒生命周期**。见 [§6.4](#64-如何新增一个-tool)。
8. **注释用中文**，与现有代码风格保持一致。

---

## 1. 项目是什么

`nexus-agent` = 一个基于 **Spring Boot 3.5 + LangChain4j** 的 Agent 平台后端，能力包括：

| 能力 | 状态 | 实现位置 |
|---|---|---|
| 多模型对话（OpenAI 兼容） + SSE 流式 | ✅ 可用 | `ChatServiceImpl` / `ChatContextFactory` |
| 用户自带 API Key（加密存储 + 按模型选择） | ✅ 可用 | `UserConfigServiceImpl` / `EncryptorFactory` |
| 工具调用（Tool Calling）+ 流式回显 | ✅ 可用 | `tools/` + `SseResponseConverter` |
| MCP 接入（仅 streamable_http） | ✅ 可用（有资源泄漏） | `McpInformationServiceImpl` |
| 沙盒执行代码（**E2B 云沙盒**，非本地 Docker） | ✅ 可用（有路由 bug） | `BoxTool` + `nexus_agent_box/` |
| RAG 知识库（pgvector） | ✅ 可用（P2-13：入库/检索同模型 + 检索带 `user_id` 隔离） | `KnowledgeBaseFileServiceImpl`（入库）/ `RagTool`（检索） |
| 长期记忆 | ✅ 可用（`pg_trgm` 模糊检索 + 字面匹配兜底，P2-7） | `UserMemoryServiceImpl` + `utils/MemoryQueryParser` / `MemoryTool`（见 §6.15） |
| Skill 系统（`langchain4j-skills`） | ✅ 可用（本地目录扫描，`ChatDTO.skills` 生效） | `skills/SkillLoader` + `skills/` 目录 |
| JWT 登录 / 邮件验证码 / WS 推送 / OSS 上传 | ✅ 可用 | `LoginCheckInterceptor` 等 |
| 前端 | ❌ 无（仅 `static/showHistory.html` 调试页） | — |

**一句话诊断**：后端骨架是完整的，但**"半成品能力 + 未收口的边界条件 + 没有前端"** 三者叠加，导致它"能演示、不能自用"。重构重点不是重写，而是 **收口 + 接通 + 补齐**。

---

## 2. 技术栈与依赖版本（精确，勿凭记忆改）

### 2.1 Java 侧

| 组件 | 版本 | 声明位置 |
|---|---|---|
| JDK | **21**（源/目标均为 21） | 父 `pom.xml` `<java.version>` |
| Spring Boot | **3.5.13** | 父 `pom.xml` `<parent>` |
| LangChain4j BOM | **1.12.1** | 父 `pom.xml` `<langchain4j.version>` |
| MyBatis-Plus | **3.5.6**（`mybatis-plus-spring-boot3-starter`） | 父 `pom.xml` `<mybatis.version>` |
| jjwt | **0.12.5** | `nexus-agent-common/pom.xml` |
| Hutool | **5.8.27**（`hutool-all`） | `nexus-agent-service/pom.xml` |
| aliyun-sdk-oss | **3.17.4** | `nexus-agent-service/pom.xml` |
| Lombok | 由 Boot 管理，`provided` | 父 `pom.xml` |

**LangChain4j 模块（版本全部由 BOM 锁定，子模块不写 version）**：
`langchain4j`、`langchain4j-open-ai-spring-boot-starter`、`langchain4j-mcp`、`langchain4j-skills`、`langchain4j-pgvector`、
`langchain4j-document-parser-apache-pdfbox`、`langchain4j-document-parser-apache-poi`、`langchain4j-document-parser-apache-tika`

**Spring Boot starters**：`web`、`webflux`（沙盒 HTTP 调用）、`websocket`、`mail`、`data-redis`、`validation`、`actuator`、`test`、`spring-security-crypto`（加密）

> ⚠️ **注意**：`langchain4j-skills` 已接入业务流程（见 §6.9），且版本为 `1.12.1-beta21` ——
> beta API 属破坏性变更高风险点，升级前必读 changelog 并回归 Skill 链路。

### 2.2 Python 沙盒侧（`nexus_agent_box/`）

| 组件 | 约束 | 说明 |
|---|---|---|
| Python | **>= 3.12** | `.python-version` + `pyproject.toml`，Dockerfile 用 `python:3.12-slim` |
| e2b-code-interpreter | **>= 2.6.2** | **核心依赖**：所有沙盒都是 E2B 云沙盒 |
| fastapi | **>= 0.136.1** | |
| uvicorn | **>= 0.46.0** | |
| oss2 | **>= 2.19.1** | 沙盒文件下载后回传 OSS |
| python-dotenv | **>= 1.2.2** | 读取 `.env` |
| 包管理 | **uv**（`uv.lock` 已提交） | `uv run main.py` / `uv sync --frozen` |

### 2.3 运行时实际路径（本机已核实）

```
JDK     : D:\environment\jdk21              (21.0.12, Oracle)
Maven   : D:\dev_utils\maven                (3.9.4)  ← 未加入 PATH，也无 mvnw
本地仓库 : C:\Users\huhuhuzhijian\.m2\repository  ← 无 settings.xml，走默认中央仓库 + 需联网
```

> **Maven 不在 PATH 且项目没有 `mvnw` 包装器。** 所有命令必须用全路径 `/d/dev_utils/maven/bin/mvn`，
> 或者建议在 P0 阶段补一个 Maven Wrapper（见 `重构计划.md`）。给用户执行的命令请用全路径，不要假设 `mvn` 存在。

### 2.4 ✅ 编译基线（已实测）

`2026-09-23` 在 `ds` @ `86f3a07` 上执行 `/d/dev_utils/maven/bin/mvn -DskipTests compile`：

```
nexus-agent           0.0.1-SNAPSHOT  SUCCESS
nexus-agent-common    0.0.1-SNAPSHOT  SUCCESS
nexus-agent-domain    0.0.1-SNAPSHOT  SUCCESS
nexus-agent-mapper    0.0.1-SNAPSHOT  SUCCESS
nexus-agent-service   0.2.0           SUCCESS
nexus-agent-web       0.1.0           SUCCESS
BUILD SUCCESS（首次需联网拉依赖，约 4 分钟）
```

**结论**：项目**当前是可编译的**。所以 §12 里列的问题都是**运行期/逻辑**问题，不是编译问题。
改动后如果编译失败，一定是这次改动引入的，先查自己的 diff。

> 注：`~/.m2/settings.xml` **不存在**。阿里云镜像 `alimaven` 来自 `D:\dev_utils\maven\conf\settings.xml`。
> 首次或有依赖变更时需要联网。

### 2.5 ⚠️ 两套 Maven 的镜像差异（会踩坑）

本机有两个 Maven，**依赖仓库配置不同**，混用会导致莫名其妙的离线解析失败：

| 入口 | 版本 | 依赖镜像 | 离线可用性 |
|---|---|---|---|
| `D:\dev_utils\maven\bin\mvn` | 3.9.4 | ✅ 有 `alimaven`（阿里云） | 好（构件 `_remote.repositories` 标记为 alimaven，匹配） |
| `./mvnw` | 3.9.4 | ❌ 无镜像，走默认 central | **差**：已有构件标记为 alimaven，离线模式下会报 `artifact has not been downloaded from it before` |

两者共用同一个本地仓库 `C:\Users\huhuhuzhijian\.m2\repository`。

**实践建议**：

- 日常开发 / 需要离线 / 追求速度 → 用 **`D:\dev_utils\maven\bin\mvn`**
- 换机器或给别人用 → 用 `./mvnw`（更可移植），但**联网执行**

**要让 `./mvnw` 也走阿里云镜像**（推荐，一次配好两边都一致），需要用户自己创建全局配置——
这属于改动全局环境，请**由用户执行**，AI 不要代劳：

```bash
# 把下面内容写入 ~/.m2/settings.xml（文件不存在则新建）
cat > ~/.m2/settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>alimaven</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF
```

> `mvnw` 脚本本身支持 `MVNW_REPOURL` 环境变量来覆盖 Maven 发行包的下载地址（已在
> `.mvn/wrapper/maven-wrapper.properties` 里指向阿里云），但它**管不到依赖仓库**，两者是两件事。

---

## 3. 构建 / 运行 / 测试

```bash
# ⚠️ mvn 不在 PATH，用全路径（Git Bash 环境）
MVN=/d/dev_utils/maven/bin/mvn

$MVN -DskipTests compile                  # 全量编译（首次需联网拉依赖）
$MVN -DskipTests clean package            # 打包
$MVN -pl nexus-agent-service -am -DskipTests compile   # 只编某模块 + 其依赖链（推荐日常用）
$MVN test                                 # 跑测试（⚠️ 见下方说明）
$MVN spring-boot:run -pl nexus-agent-web  # 启动 Web（端口 8080）
java -jar nexus-agent-web/target/nexus-agent-web-*.jar
```

**测试现状（重要）**：`nexus-agent-web/src/test/` 下的 `BoxToolTest`、`WebClientTest` 等属于
**人工集成调试脚本**，会真实访问 E2B / OSS / LLM 外部服务，**不适合 CI，也容易失败**。
`BoxToolTest.testCode` 还硬编码了一个沙盒 ID。重构计划中要求把它们改成**默认跳过 + 单测隔离**。

**沙盒服务（独立进程，默认 8000 端口）**：
```bash
cd nexus_agent_box
uv run main.py            # 开发模式
# 或
docker compose up -d      # 容器模式（Dockerfile 用 uv sync --frozen）
```

**启动依赖**：PostgreSQL（需 pgvector 扩展）、Redis、E2B API Key、OSS 凭证缺一不可。

---

## 4. 配置与 Profile（这里有坑）

- `application.yml` → **`spring.profiles.active: dev`**（❗`CLAUDE.md` 写的是 `prod`，是错的）
- `application-dev.yml` → **被 `.gitignore` 忽略，仓库里没有！** 第一次跑项目必须自己建：
  ```yaml
  # nexus-agent-web/src/main/resources/application-dev.yml （不要提交）
  # 内容参考 application-prod.yml，填上真实连接信息
  ```
- `application-prod.yml` → 已提交，但**全部值都是环境变量占位符**（`${DEEPSEEK}` 等）
- `spring.threads.virtual.enabled: true` → 虚拟线程已开启，**不要写阻塞式 ThreadLocal 传递逻辑**（见 §12.4）

### 4.1 环境变量清单

| 变量 | 用途 | 消费方 |
|---|---|---|
| `DEEPSEEK` | 默认流式对话模型 Key | `application-prod.yml` |
| `MOONSHOT` | 默认同步模型 Key（**标题生成**用，见 §6.6） | `application-prod.yml` |
| `AI_KEY` | DashScope 向量模型 Key | `application-prod.yml` |
| `SERVICE_IP` | PostgreSQL / Redis 主机（**注意：不是 `DOCKER_IP`**，历史上文档与 prod.yml 里写错过，以 `application-dev.yml` 为准） | `application-prod.yml` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | QQ SMTP 验证码 | `application-prod.yml` |
| `JWT_SECRET` | Base64 编码的 HMAC-SHA256 密钥。**不设置会随机生成 → 重启后所有 token 失效** | `JwtUtil` |
| `API_KEY_SECRET` | 用户 API Key 加密主密钥。**不设置会 NPE** | `EncryptorFactory` |
| `BASE_URL` | 沙盒服务地址，默认 `http://localhost:8000` | `WebClientConfig` |
| `E2B_API_KEY` | E2B 云沙盒鉴权 | `nexus_agent_box`（`.env`） |
| `ALIBABA_CLOUD_ACCESS_KEY_ID` / `..._SECRET` | 沙盒回传 OSS 用（`EnvironmentVariableCredentialsProvider`） | `app/utils/oss_utils.py` |

---

## 5. 模块结构

```
nexus-agent (parent, packaging=pom, v0.0.1-SNAPSHOT)
├── nexus-agent-common   v0.0.1-SNAPSHOT  JWT、枚举、异常、ThreadLocal 上下文、常量
├── nexus-agent-domain   v0.0.1-SNAPSHOT  Entity / DTO / VO / Result 信封
├── nexus-agent-mapper   v0.0.1-SNAPSHOT  11 个 Mapper 接口 + 11 个 XML
├── nexus-agent-service  v0.2.0 ⚠️        LangChain4j 集成、工具、业务 Service、Config
└── nexus-agent-web      v0.1.0 ⚠️        Controller + 启动类 + 配置 + 测试
```

**依赖链**：`web → service → mapper → domain → common`

### 5.1 ❗两个必须知道的"怪癖"

**(1) 所有模块的 Java 包名都叫 `com.huzhijian.nexusagentweb`**
即使是非 web 模块也一样。新增类时**沿用该包名 + 对应分层子包**（`factory` / `tools` / `utils` / `service` …），
不要按模块名另起包，否则两个包名并存会让 Spring 扫描和类加载变得不可预测。

**(2) 版本号不统一（会误导依赖解析）**
`service` 声明了 `0.2.0`、`web` 声明了 `0.1.0`，而 `web → service:0.2.0`、`service → mapper:0.0.1-SNAPSHOT`。
**改依赖时不要想当然写父版本号**，先 `grep <version>` 确认。P0 阶段要统一（见重构计划）。

### 5.2 关键文件地图（按"我要改 X 去看 Y"组织）

| 我想改… | 去看 |
|---|---|
| 对话主流程 / SSE 事件 | `nexus-agent-service/.../service/impl/ChatServiceImpl.java` |
| 模型选择 / 工具注册 / 记忆窗口 | `nexus-agent-service/.../factory/ChatContextFactory.java` |
| SSE 输出格式 | `nexus-agent-service/.../converter/SseResponseConverter.java` + `common/.../em/SseEventType.java`（事件名）+ `domain/.../vo/SseEvent.java`（信封）。**权威契约见 `docs/sse-contract.md`** |
| token 配额（拦截 / 记账 / 周期重置 / 用量查询） | `nexus-agent-service/.../service/impl/QuotaServiceImpl.java` + `common/.../em/QuotaPeriod.java`（周期）+ `domain/.../vo/QuotaVO.java`（返回体）。见 §6.13 |
| API 文档（Swagger / OpenAPI） | `nexus-agent-web/.../config/OpenApiConfig.java`（只写元信息）+ 各 Controller 的 `@Tag`/`@Operation`。SSE 的契约另见 `docs/sse-contract.md`。见 §6.17 |
| 用户消息 → LangChain4j Content | `nexus-agent-service/.../converter/ChatMessageConverter.java` |
| 系统提示词 | `nexus-agent-common/.../content/ModelSystemContent.java` |
| 工具注册 / 开关 / 新增工具 | `nexus-agent-service/.../tools/registry/`（`ToolRegistry`、`AgentToolSet`、`ToolSelection`），用法见 §6.4 |
| 沙盒工具 | `nexus-agent-service/.../tools/BoxTool.java` |
| 记忆 / RAG 工具 | `tools/MemoryTool.java`（长期记忆，常驻） + `tools/RagTool.java`（知识库检索，`enableRag=true` 时启用；**检索带 `user_id` 隔离**，见 §6.16） |
| 沙盒服务端 | `nexus_agent_box/app/routers/{box,file,execute,mcp}.py` |
| 聊天历史落库 | `nexus-agent-service/.../config/PgChatMemoryStore.java` |
| 鉴权 | `nexus-agent-service/.../interceptor/LoginCheckInterceptor.java` |
| MCP | `nexus-agent-service/.../service/impl/McpInformationServiceImpl.java` |
| 知识库**入库**（切分 + 向量化） | `nexus-agent-service/.../service/impl/KnowledgeBaseFileServiceImpl.java` |
| 会话列表 / 重命名 / 搜索 | `nexus-agent-service/.../service/impl/ChatHistoryListServiceImpl.java`（合并与排序）+ `nexus-agent-mapper/.../ChatHistoryListMapper.java`（改标题）+ `ChatMemoryMapper.xml#searchHits`（按正文搜）。见 §6.19 |
| 向量库 Bean | `nexus-agent-service/.../factory/PgVectorEmbeddingFactory.java`（**维度取自 `embeddingModel.dimension()`**，改模型要同步 `vector(N)` 与 HNSW 索引） |
| 向量库 Bean | `nexus-agent-service/.../factory/PgVectorEmbeddingFactory.java` |

---

## 6. 核心机制说明

### 6.1 对话全链路（SSE）

```
POST /api/chat/stream   body=ChatDTO{messages[], sessionId, skills[], MCPs[], model, enableRag}
  └─ ChatController.chatStream
      └─ ChatServiceImpl.chat
          ├─ UserContextHolder.getUserId()          ← 来自 LoginCheckInterceptor
          ├─ new SseEmitter(120000L)                ← 超时写死 120s
          ├─ ChatContextFactory.create(chatDTO,userId)
          │    ├─ createModel()        → 用户配置模型 or defaultModel
          │    ├─ AiServices.builder(ChatAssistant.class)
          │    │     .streamingChatModel(model)
          │    │     .tools(boxTool, logTool)                   ← 永远注册
          │    │     .chatMemoryProvider(TokenWindowChatMemory, maxTokens=100000)
          │    │     + ragTool     (enableRag=true 时)          ← 实际是 MemoryTool 实例
          │    │     + toolProvider(mcp) (MCPs 非空时)
          │    └─ sessionId = 入参 or UUID（isNewSession）
          ├─ redisUtils.set("session:"+sessionId, userId, 5min) ← ⚠️ 仅 5 分钟
          ├─ ChatMessageConverter.toContents(messages)          ← 文件/图片 → TextContent
          └─ ChatAssistant.chat(contents, sessionId) → TokenStream
               ├─ onPartialThinking        → SSE event "message" type=THINK
               ├─ onPartialResponse        → SSE event "message" type=CONTENT
               ├─ onPartialToolCallWithContext → SSE event "tool_execution"
               ├─ onToolExecuted           → SSE event "tool_execution_result"
               ├─ onCompleteResponse       → finish()
               └─ onError                  → completeWithError()
```

**新会话额外行为**：`finish()` 时先发 `session_id` 事件，再**异步**生成标题（见 §6.6），最后发 `finish` 事件。

### 6.2 SSE 事件契约（**v2**，前端按此对接）

> 📌 **权威契约在 `docs/sse-contract.md`，本节只留索引**（两处冲突以契约文档为准）。
>
> **P2-5 已落地（2026-09-30）**：事件名统一小写、所有 data 套同一个信封
> `{seq, runId, event, data}`、新增 `run` 首帧。由 `SseContractTest`（10 个单测）固定。

| event name | data（信封里的 `data` 字段） | 说明 |
|---|---|---|
| `run` | `{sessionId, isNewSession}` | **第一帧**（P2-5 新增）。把 runId/sessionId 提前交给前端；v1 的 `session_id` 事件已并入此帧 |
| `message` | `MessageVO{type: THINK\|CONTENT, thinking?, content?}` | 思考 / 正文增量。**批量增量**（P2-12）：攒够 200 字符或 60ms 才推一帧，append 语义不变 |
| `tool_execution` | `MessageVO{type: TOOL_EXECUTION, toolRequestList:[{id,toolName,arguments}]}` | 工具调用请求；`arguments` 是**流式片段**，同一调用会拆成多帧，需按 `id` 累积 |
| `tool_execution_result` | `MessageVO{type: TOOL_EXECUTION_RESULT, toolResultVO:{id,toolName,result,isError}}` | 工具结果，用 `id` 与上面对配对 |
| `artifact` | `MessageVO{type: ARTIFACT, artifact:{id,name,url,size,extension,sourcePath}}` | AI 产出的交付物（P2-10），渲染成下载卡片 |
| `finish` | `{status: DONE}` | 正常结束（v1 是裸字符串 `"DONE"`） |
| `error` | `{type: ERROR, message, hint}` | 运行失败；`runId` 在**信封层**，可 grep `RUN runId=<值>` 定位本次运行 |

**信封字段**：`seq`（本次 Run 内从 1 递增，可判断丢帧）、`runId`（trace_id）、
`event`（与 SSE `event:` 同名）、`data`（上表载荷）。
`seq` 同时写进 SSE 原生 `id:`，浏览器 `EventSource` 重连时会作为 `Last-Event-ID` 回传。

> ⚠️ **v1 → v2 是破坏性变更**（事件名改小写、data 多一层、`session_id` 取消）。
> 之所以现在一次改干净：当前没有存量前端，前端由我们在 P3-1 自己写。迁移对照表见契约文档 §6。
> **服务端回放未实现**（需事件持久化），断线后请按 `sessionId` 重拉历史 —— 契约文档 §5 有说明。

### 6.3 模型选择逻辑

1. 读 `user_config.llm_api_token`（JSON 数组，加密后存库，Redis 缓存 3 天，key=`config:{userId}`）
2. 按 `ModelDTO.id()` 匹配 `APIConfig`；`id` 为空则取 `isDefault=true` 的配置
3. 校验 `APIConfig.model` 中存在 `type=CHAT && name=modelName`，否则回退默认模型（**现在会打日志说明原因**）
4. `thinking` 开关 → 按**服务商能力**组装 `customParameters`（P2-3，见下）
5. 加解密：`EncryptorFactory.text(userConfig.salt).encrypt/decrypt`

**额外参数按服务商能力下发（P2-3）**：

判定依据是 **`APIConfig.baseUrl`** 而不是模型名 —— 同一型号经不同服务商转发时支持的参数并不相同
（qwen 在百炼上认 `enable_search`，经某些中转站转发则不认）。

| 服务商（baseUrl 含） | thinking 参数 | search 参数 |
|---|---|---|
| `dashscope.aliyuncs.com`（阿里云百炼） | ✅ `enable_thinking` + `thinking.type` | ✅ `enable_search` |
| `deepseek.com`（DeepSeek 官方） | ❌（思考由具体型号决定，如 deepseek-reasoner） | ❌（无此参数） |
| **未命中的服务商** | ❌ | ❌ |

**默认策略是「未知即不下发」**，这是刻意取舍：不认某字段的服务商可能直接 **400**（整次对话失败），
而少一个联网搜索/思考开关只是**功能降级**，两者代价不对等。
命中不了内置表时日志会说明并提示如何声明，不会静默吞掉：

```yaml
nexus:
  agent:
    model:
      providers:            # key = baseUrl 中包含的片段；同名覆盖内置，新片段即扩展
        my-gateway.example.com:
          thinking: true
          search: true
```

匹配规则：忽略大小写，多个命中取**最长**片段。

> ⚠️ **系统默认模型不经过这张表** —— 它由 langchain4j starter 直接构建，
> 额外参数写在 yml 的 `langchain4j.open-ai.streaming-chat-model.custom-parameters` 里。
> 换默认模型时记得同步改那段（不认的字段同样会 400），注释里有说明。

### 6.4 如何新增一个 Tool（声明式，**不用改工厂**）

工具由各工具类**自我声明**，注册表统一收集。新增一个工具只需两步：

**第 1 步：写一个类，实现 `AgentToolSet` 并加 `@Component`**

```java
@Component
@Slf4j
public class XxxTool implements AgentToolSet {

    @Override
    public String key() { return "xxx"; }                    // 唯一标识，用于日志与后续能力清单
    @Override
    public String description() { return "一句话说明这个工具集能做什么"; }
    // 可选：按需启用，默认恒启用
    // @Override public boolean enabled(ToolSelection s) { return s.ragEnabled(); }

    private final SafeExecuteToolHandler safeExecuteToolHandler;   // 构造注入
    private final ToolCallGuard toolCallGuard;                     // 构造注入（P2-4）

    @Tool(name = "tool_name", value = "给模型看的中文说明，说明越清楚模型调用越准")
    public Map<String,Object> doSomething(@ToolMemoryId Object memoryId,   // 不暴露给模型，用来做会话隔离
                                          @P("参数说明") String arg) {
        // ① 先治理重复调用（P2-4）：同会话 + 同工具 + 同参数刷屏 → 拦截并回灌提示
        Map<String,Object> blocked = toolCallGuard.intercept(memoryId, "tool_name",
                ToolCallGuard.fingerprint(arg));                        // 返回 String 的工具用 interceptText
        if (blocked != null) { return blocked; }
        // ② 再用 SafeExecute 包住外部调用：失败转结构化结果，模型可自纠
        return safeExecuteToolHandler.mapTool("tool_name", () -> httpUtils.get("/xxx").block());
    }
}
```

> **新增工具检查清单**（缺一项都算没做完）：
> 1. 实现 `AgentToolSet` + `@Component`（不要改工厂）；
> 2. 用 `@ToolMemoryId` 拿会话 ID（该参数**不会**出现在给模型的签名里）；
> 3. 方法第一行接 `ToolCallGuard`，指纹只传「能区分是不是同一次调用」的关键参数；
> 4. 所有外部调用包 `SafeExecuteToolHandler`，`toolName` 与 `@Tool(name=)` 保持一致；
> 5. 失败要让模型能理解与自纠（结构化 `errorCode` + `hint`，见 §6.5）；
> 6. 高成本/有副作用的工具**必须**考虑超时（HTTP 层统一超时见 §15 `tools.http-timeout`）。

**第 2 步：没有了。** 不需要改 `ChatContextFactory`、不需要改任何配置
（Spring 会把所有 `AgentToolSet` 实现注入 `ToolRegistry`）。

**机制说明**：
- `ToolRegistry.resolve(ToolSelection)` 遍历所有工具集，调用各自的 `enabled()` 过滤，返回工具对象列表
- `ToolSelection`（当前含 `ragEnabled`，由 `ChatDTO` 推导）是唯一加开关的地方 ——
  要加「按用户/场景启停工具」只需给 `ToolSelection` 加字段，**各工具类无需改动**
- `ToolRegistry.keys()` / `describeAll()` 可用于排查「某工具为什么没生效」，也是未来「能力清单」接口的数据源
- 启动时 `ToolRegistry` 的 debug 日志会逐个打印 `工具集 [key] 启用/跳过`，排查很方便

**注意：MCP 不走这套机制** —— 它是外部 `toolProvider`（见 §6.7），仍在 `ChatContextFactory` 里单独处理。

### 6.5 工具失败的返回契约（渲染给模型看）

所有走外部服务的工具都必须用 `SafeExecuteToolHandler` 包装，失败时返回**扁平结构**：

```json
{ "success": false, "errorCode": "SERVICE_UNREACHABLE", "message": "简洁原因", "hint": "给模型的自纠建议" }
```

错误码共 7 类：`SERVICE_UNREACHABLE`（服务不可达）/ `TIMEOUT` / `BAD_REQUEST`（4xx）/
`UPSTREAM_ERROR`（5xx）/ `BAD_PARAMETER` / `EMPTY_RESPONSE` / `UNKNOWN`。

**为什么要这样设计**：模型看到 `{"error":"..."}` 只能盲试；带上错误码与 hint 之后它能自纠。
实测有效：沙盒被 E2B 回收后再执行命令，工具返回 hint 提示重新建沙盒，
模型随即自主调用 `create_box` 重建并完成了原任务。

**实现注意事项**（改动时别踩）：
- ❗不要用 `Map.of(...)` 构造错误结果 —— 它**不接受 null 值**，而异常可能没有 message，
  会导致「错误处理自身抛 NPE」，把工具失败升级成请求失败（这是原始缺陷，已有回归测试）
- 错误码由**异常类型**判定，并**沿 cause 链查找根因**（WebClient 会把 `ConnectException`
  包一层，只看最外层会误判成 `UNKNOWN`）
- 异常文案要截断（当前 300 字），避免长堆栈塞进模型上下文
- 失败必须打 WARN 日志，否则运维侧完全看不到工具失败

**新增 Tool 的检查清单**：① 返回类型必须是可序列化的（`Map`/`String`/`List<Map>`）；② 必须用 `SafeExecuteToolHandler` 兜底；
③ 提示词里如果涉及"失败禁止重试"要在 `ModelSystemContent.CHAT_PROMPT` 补充；④ 考虑外部调用超时；
⑤ **工具名（`@Tool(name=...)`）一旦上线不要改**，模型侧提示词与前端都可能依赖它。

### 6.6 标题生成与 WebSocket

`ChatHistoryListServiceImpl.createTitle()` 标注 `@Async`（启动类已 `@EnableAsync`）：
- 用 **Moonshot** (`OpenAiChatModel` 同步模型) 生成标题
- 失败**降级**为"用户问题前 255 字符"
- 生成后通过 `webSocketService.sendToClient(userId, {type:"title", data:title})` 推送
- ⚠️ `@Async` + 内部读 `UserContextHolder` 会在**新线程**执行 → ThreadLocal 取不到值，靠显式传参规避

### 6.7 MCP

- 只支持 `streamable_http`（`StreamableHttpMcpTransport`）
- MCP 列表来源于 **ModelScope openapi**，由 FastAPI `/mcp` 转发（`app/routers/mcp.py`）
- Java 端 `McpInformationService`：`/api/mcp/service`（拉取）→ `/api/mcp`（落库）
- 连不通时会把记录的 `available` 置 false（避免每次对话都白白尝试）
- ✅ **客户端生命周期已收口（P1-6）**：`mcp/McpClientRegistry` 按 mcpId 缓存复用、
  创建失败立即 `close`、配置变更 `evict`、`@PreDestroy` 统一关闭。
  （原实现每次对话新建 `DefaultMcpClient` 且成功后从不关闭 → 每轮泄漏一批连接。）
- ✅ **不可用会明确告知模型（P2-9）**：`getMcp` 返回 `McpResolution{provider, unavailableNames}`，
  「选了但连不上」的服务名随 `ChatContext.mcpUnavailable` 注入系统提示词的
  `{{runtimeCapabilities}}`。在此之前是**静默丢弃**：模型只会说"我没有这个能力"，
  用户分不清是"没配"还是"配了但连不上"。
  提示词里同时要求模型「不要尝试调用、也不要反复重试，如实说明不可用」。

### 6.8 沙盒（E2B）

`nexus_agent_box/` FastAPI 路由：

| 路由 | 方法 | 说明 |
|---|---|---|
| `/box` | GET | `Sandbox.create()`，返回 `box_id`（**超时 600s**） |
| `/box/{box_id}` | DELETE | `Sandbox.connect(box_id).kill()` |
| `/file` | POST | 从 URL 拉文件写入沙盒 |
| `/file` | GET | 读沙盒文件 → 传 OSS → 返回 url |
| `/file/list` | GET | 列目录 |
| `/file/exists` | GET | 判断存在 |
| `/file/create` | POST | 写文件 |
| `/execute/code` | POST | 执行代码，body=`{code, box_id}` |
| `/execute/cmd` | POST | 执行命令，body=**`{cmd, box_id}`** |
| `/mcp` | GET | 代理 ModelScope MCP 列表 |

> ✅ **P0-2 已修**：`BoxTool.executeCmd` 曾错误地打到 `/execute/code` 并传 `code` 字段
> （shell 命令被当 Python 代码执行）。现经 `sandbox/SandboxClient` 走 `POST /execute/cmd` + `{cmd, box_id}`，
> 已于 2026-09-23 端到端实测通过（见 §12.0）。

### 6.9 Skill 系统（本地目录扫描，已接通）

**方案**：决策 D3 —— Skill 是服务端本地目录，扫描 `SKILL.md` 注册，**不入库、不支持上传**
（避开 B/S 下解压落盘与路径穿越风险）。旧的 DB 注册表方案（实体/Mapper/Service/表）已整体删除。

**目录约定**（由 `langchain4j-skills` 的 `FileSystemSkillLoader` 定义，完整说明见 `skills/README.md`）：

```
skills/                      ← 根目录，由 nexus.agent.skill.root-dir 指定（默认 "skills"）
└── my-skill/                ← 一个子目录 = 一个技能；无 SKILL.md 的子目录被静默跳过
    ├── SKILL.md             ← 必需；YAML frontmatter 提供 name / description
    ├── notes.txt            ← 文档资源：被索引 → 模型可 read_resource（加载时读入内存）
    └── scripts/xxx.py       ← ⚠️ 库**刻意排除** scripts/ 目录，read_resource 读不到
```

> ⚠️ **`scripts/` 与文档资源的分工是实测出来的**（`SkillLoaderTest` 有回归测试）：
> 库把 `scripts/` 视为「供执行的脚本」而非「供阅读的资源」，两者不能混放。
> 文档资源会**全量读入内存**并随缓存刷新，单个文件不宜过大。

**运行链路**：

```
启动/缓存过期 → SkillLoader.scan()          （默认 60s TTL，refreshInterval 可配）
每次对话      → SkillLoader.formatForPrompt(chatDTO.skills())
                → 填充系统提示词 {{runtimeCapabilities}}
                → ChatContextFactory 注册 Skills.toolProvider()（activate_skill / read_resource）
模型按需      → activate_skill(name) 取技能正文 → 严格按步骤执行
```

**请求侧语义（`ChatDTO.skills`）**：不传或空 → 启用**全部**；传名称列表 → 只启用指定的；
名称不存在时**只 WARN 不报错**（技能可能刚被删，不该让整次对话失败）。

**实现要点与陷阱**：

| 事项 | 说明 |
|---|---|
| 能力说明必须走 Mustache 变量 | `@SystemMessage` 是静态文本，而「有哪些技能 / 哪些 MCP 连不上」都是运行期才知道的 → `ChatAssistant.chat(..., @V("runtimeCapabilities") String)` 显式传入。不注入的话：模型不知道能调 `activate_skill`，也会把「配了但连不上」说成「我没有这个能力」。（该变量 P2-9 前叫 `availableSkills`，因同时承载 MCP 状态而改名） |
| **Skill 与 MCP 必须合并注册** | 两者都是 `ToolProvider`，连续调 `builder.toolProvider(a)` / `toolProvider(b)` 会**互相覆盖**，只剩最后一个生效。必须收集为 `List` 后一次 `toolProviders(list)`（`ChatContextFactory` 已按此实现） |
| 扫描失败不阻塞启动 | 目录不存在/读失败都降级为「无技能」并打日志，不让应用起不来 |
| 缓存的代价 | 新增技能最多延迟 `refreshInterval` 生效；`SkillLoader.reload()` 可手动刷新；设 `0s` 则每次请求重扫 |
| 路径穿越 | 库按**预索引资源清单**匹配名称，不做运行时拼路径 → 传 `../../etc/passwd` 只会匹配失败。详见 `skills/README.md` 的安全说明 |

**配置**：`nexus.agent.skill.enabled` / `root-dir` / `refresh-interval`，见 §15。
**冒烟**：仓库内置 `skills/verify-skill`（问「今天的暗号是什么」应回答含 `BANANA-7731`），
用于快速验证「扫描 → 提示词注入 → activate_skill → 按步骤作答」整条链路。
**将来支持用户自定义**：在 `SkillLoader.scan()` 里追加扫描 `<root>/users/<userId>` 即可，其余逻辑无需改动。

### 6.10 启动配置自检（P1-10）

`config/StartupConfigValidator`（`@PostConstruct`）在启动时**一次性**校验关键配置，
把结果分成两级打日志：

| 级别 | 缺失时行为 | 包含 |
|---|---|---|
| 必需 | **默认 fail-fast 阻止启动**（`nexus.agent.startup.fail-fast=false` 可降级为 WARN） | `spring.datasource.url`、对话模型 api-key、`API_KEY_SECRET` |
| 建议 | 只 WARN，**明确写出哪项能力会不可用** | `JWT_SECRET`、`AI_KEY`、`MOONSHOT`、Redis 主机、SMTP 账号/密码 |

**它解决什么**：根因 R2/R3 —— 原来缺配置时是「一次只报一个占位符错误」或**完全静默**
（`JWT_SECRET` 缺失会随机生成密钥，重启后 token 全失效却没有任何提示）。
现在启动日志里是一张清单：缺什么、后果是什么、怎么配。

**实现要点**：

- 取值统一走 `environment.getProperty(key)`：Spring 自带 `systemEnvironment` 属性源，
  **环境变量名可直接当属性键用**，因此不需要维护「属性 / 环境变量」两套来源。
  这条假设有回归测试守着（`StartupConfigValidatorTest`）。
- 未解析的占位符（值为 `${DEEPSEEK}` 或 getProperty 抛异常）**按缺失处理**并纳入汇总，
  不让它变成启动期的单条异常。
- 校验项清单在类的 `REQUIREMENTS` 常量里，**新增配置项时顺手加一条**（含"缺失后果"文案）。

**顺带修掉**：`ChatContextFactory.createModel` 的三处「静默回退系统默认模型」现在都会打日志
（说明是"用户没配"还是"模型名不在配置里"）——原来用户会误以为在用自己填的 Key。

### 6.11 工具治理：重复调用拦截与超时（P2-4）

**问题**：模型陷入循环时会反复调用同一个工具、传**完全一样的参数**（典型：`execute_cmd`
同一条命令连跑、`create_box` 反复建）。提示词里那句「工具调用失败尝试最多两次」是**软约束**，
模型不一定听；而每次调用都是真实的时间、token，E2B 沙盒还按量计费。

**两层治理**：

| 层 | 位置 | 说明 |
|---|---|---|
| 重复调用拦截 | `tools/ToolCallGuard`（工具方法第一行调用） | 以 `(会话ID, 工具名, 参数指纹)` 为键，在 `tools.duplicate-window`（默认 60s）内计数，超过 `tools.duplicate-threshold`（默认 2，即第 3 次起）就拦截，返回 `{success:false, errorCode:"DUPLICATE_CALL", message, hint}` + 自纠提示 |
| 调用超时 | `WebClientConfig` 的 `responseTimeout`（= `tools.http-timeout`，默认 100s） | 原先**没有响应超时**，沙盒挂起/网络黑洞时工具无限等待、整条 SSE 卡死（用户只看到「一直不出字」）。刻意设得比 `sse.timeout`(120s) 小，以便先返回结构化 `TIMEOUT` 而不是掐断整条流 |

**设计要点**：

- **滑动窗口计数**而不是「只记上一次」：模型连续刷同一条命令时，窗口内会持续处于被拦状态，
  不会因为间隔刚好跨过判定而漏拦。
- **只拦完全相同的调用**：参数有一处不同就放行 —— 避免误伤「换参数重试」这种正当行为
  （`rag_search` 的提示语就是让模型「修改关键词再次尝试」，改了就放行）。
- 键带**会话 ID**，不同会话互不影响；`@ToolMemoryId` 为 null 时落到匿名桶（不 NPE、不放开校验）。
- 参数指纹用 **SHA-256 前 16 字节**而不是 `String.hashCode()`：后者 32 位，在高频调用下碰撞会**误拦**。
- 被拦时**不登记新时间戳**，窗口内保持拦截；`@Scheduled` 定期 `sweep()` 清理过期键防内存增长。
- 拦截**不抛异常**：工具方法拿到非 null 就直接 return，与 `SafeExecuteToolHandler`
  的失败结果**形状一致**，模型不需要学两套。

**配置**：`nexus.agent.tools.*`（见 §15）。设 `duplicate-threshold: 0` 即关闭该治理。

### 6.12 可观测性：每次 Run 的 token / 费用 / 工具序列（P2-6）

**解决什么**（根因 R6「不可观测」）：原来一次对话花了多少 token、调了哪些工具、耗时多久、
用了哪个模型，全都查不到 —— 用户只能凭感觉猜成本，出问题也没有 trace_id 可追。

**产出**：每次 Run 结束输出一行结构化日志（`RunMetricsReporter`）：

```
RUN runId=9f2c8a1b3d4e5f60 session=8b1e... user=1 model=deepseek-v4-flash cost=7.6s \
    tokens=1234/567/1801(in/out/total) fee=¥0.0071 tools=3 seq=[create_box,execute_cmd,delete_box] result=OK
```

失败 Run 用 **WARN** 输出（`result=ERROR err=XXX`），便于在日志里一眼筛出来。

**数据来源**（全部在 `ChatServiceImpl` 的同一次流式订阅里，不需要跨层传递）：

| 字段 | 来源 |
|---|---|
| `model` / `tokens` | `onCompleteResponse(ChatResponse)` → `modelName()` / `tokenUsage()` |
| `seq` / `tools` | `onToolExecuted(ToolExecution)` → 工具名 + `hasFailed()` |
| `cost` | `RunMetrics` 自己记的开始时间 |
| `result` / `err` | `onError` |
| `runId` | `ChatServiceImpl` 每次 Run 生成 16 位十六进制串（= trace_id） |

**设计要点**：

- **用英文键值对**（`RUN runId=... tokens=...`）而不是中文：日志要被 grep / awk / 采集器解析，
  中文键做分隔符很别扭。字段顺序固定，便于 `cut`/正则提取。
- **费用只在配了单价时显示**：各厂商价格经常变动，写死必然过时并给出**错误金额**，
  所以价格表由配置提供（`nexus.agent.observability.model-prices`，支持最长前缀匹配）；
  没配就显示 `fee=unpriced` —— 明确表示"不知道"，而不是显示 ¥0。
- **token 缺失显示 `unavailable`**：不是所有厂商都返回用量，缺了就说缺了，不编造 0
  （内部用 -1 表示缺失，避免与真实的 0 混淆）。
- **工具序列有上限**（30 步，超出显示 `...(+N)`）：长 Run 几十次工具调用会把日志撑爆。
- **不改主流程语义**：只在既有回调里累加、在结尾输出一行；`observability.enabled=false` 即完全关闭。
- 顺带补齐 **SSE `error` 事件带 trace_id**（P1-9 的遗留项）：见 §6.2。

**为什么先落日志而不是落库**：日志已满足"查得到、能 grep、能统计"，且不引入表结构变更。
`RunMetricsReporter` 已把「算」（`estimateFee` 纯函数）与「写」分开，
将来要接统计面板时加一个落库实现即可，不必改动本类。

### 6.13 token 配额（P2-8）

**解决什么**：模型/工具一旦跑飞（死循环、超长上下文），一次对话就能烧掉可观费用。
`ChatContextFactory` 里一直挂着 `TODO 判断余额是否足够`，本轮把它做掉。
与 §6.12 配套：那边负责**算出**这次花了多少 token，这边负责**记账 + 拦住超支**。

**两段式（粗粒度）**：

| 时机 | 动作 | 为什么在这里 |
|---|---|---|
| 对话**开始前** | `QuotaService.assertWithinQuota(userId)` → 超限抛 `QuotaExceededException` | 早失败：省掉一次完整的模型调用，也不必白建沙盒 |
| 对话**结束后** | `QuotaService.recordUsage(userId, totalTokens)` → 原子累加 `users.token_used` | token 用量只有 `onCompleteResponse` 才拿得到 |

> ⚠️ **允许最后一次小幅超额**：检查时并不知道本次会用多少，所以是"用完 → 下次才被拒"。
> 要精确卡住必须在调用前估算 token，而估算本身不可靠（上下文长度随工具调用次数变化），故不做。
> 这是刻意的取舍，不是遗漏。

**数据与实现要点**：

- 列在 `users.token_quota`（bigint，NULL/`<=0` = **不限制**）与 `users.token_used`（bigint，单调递增），
  见 `docs/sql/003_add_user_token_quota.sql`。**存量用户默认不限制**，行为不变。
- 累加用**一条原子 UPDATE**（`token_used = COALESCE(token_used,0) + ?`，见 `UserMapper.xml`），
  而不是「查出来 → 加 → 写回」—— 后者在并发对话下会丢更新。
- **记账失败被吞掉**（只打 WARN）：它是统计口径问题，不该让一次已经成功的对话变成失败。
- 用量为 `null` / `<=0` 时**跳过记账**，不写 0 —— 免得把"厂商没返回用量"记成"没消耗"。
- `total` 缺失时用 `in + out` 兜底（部分厂商只返回这两项）。
- 配额判定抽成**纯函数** `QuotaServiceImpl.evaluate(quota, used)`，不依赖数据库，便于单测边界。
- `users.api_quota`（历史字段，smallint 默认 100）**至今没有任何代码读写**，本次不动它：
  删列属破坏性变更，保留可避免与既有数据/导出脚本产生差异。

**已知局限**（要做得更细再看这里）：

1. ~~**不是周期配额**~~ ✅ **已补（P2-8 遗留）**：见下方「周期重置」。
2. **没有管理接口**：调整某人配额目前要直接改库（`UPDATE users SET token_quota = ... WHERE id = ...`）。
3. 只统计 **token**，不按金额（金额随厂商价格变动，见 §6.12 的 `model-prices`）。

**周期重置（P2-8 遗留，`docs/sql/007`）**：

`token_used` 原本只增不减，配了额度的用户用完就永久被拒。现在支持 `NONE` / `DAILY` / `MONTHLY`：

| 项 | 说明 |
|---|---|
| 周期来源 | 先读 `users.token_period`（单个用户可覆盖），为空则用 `nexus.agent.quota.period`（默认 `NONE`） |
| 列 | `users.token_period`（varchar，默认 `'NONE'`）+ `users.token_period_start`（timestamp），见 `007` |
| 重置方式 | **惰性**：不跑定时任务，在 `assertWithinQuota` / `getQuota` 里顺手判断 |
| 重置语句 | 一条带 WHERE 的原子 UPDATE（`UserMapper.xml#resetQuotaPeriod`），并发只命中一个，天然幂等 |
| 失败降级 | 缺列（没跑 `007`）时**按累计用量判定**（保守，不放行）；周期值非法时按 `NONE` |
| 时区 | 服务端默认时区（DAILY = 当天 00:00，MONTHLY = 当月 1 号 00:00） |

> **为什么不跑定时任务**：`@Scheduled` 挂了（或实例没起来）会导致「所有人配额都不刷新」，
> 且多实例部署还要抢锁。惰性重置把这个故障模式整个消掉了 ——
> 代价只是长期不说话的用户不会被清零（而他也没在消耗额度）。

**用量查询**：`GET /api/user/quota` → `QuotaVO`（`quota`/`used`/`remaining`/`unlimited`/`period`/`periodStart`/`degraded`）。
用户身份取自 `UserContextHolder`，**不接受入参**（否则就是越权看别人用量）。
查询失败返回 `degraded=true` 而不是抛异常 —— 配额是附加信息，不该让没跑迁移的环境连设置页都打不开。

### 6.14 流式增量合并：让输出不"卡"（P2-12）

**问题**：模型是逐 token（更准确说逐 chunk，几个字符）返回的，原实现**每来一块就 `emitter.send()` 一次**。
一次千字回复 = **上千个 SSE 帧**，前后端都被高频小包拖慢：

- 后端：每帧都要序列化 + 写 socket，Tomcat 侧伴随大量小 flush
- 前端：EventSource 每帧触发一次回调 → 每次都可能触发一次状态更新与重渲染

用户感受就是"**字一个一个蹦、还卡卡的**"。

**方案**：`converter/SseChunkBuffer` 把增量合并成批次，满足任一条件才推一帧（先到先发）：

| 条件 | 配置 | 作用 |
|---|---|---|
| 缓冲 ≥ 200 字符 | `nexus.agent.sse.flush-max-chars` | 大段内容及时吐出 |
| 距上次推送 ≥ 60ms | `nexus.agent.sse.flush-interval` | 兜住低速内容（一次只吐一两个字时不被憋住） |

帧数降低一个数量级。**60ms ≈ 屏幕刷新间隔**，人眼已看不出拼接痕迹。

> ⚠️ **关键时序：这些时机必须强制冲刷（`flushPending`）**，否则前端会看到顺序错乱或丢内容：
> 推送工具事件前、推送产物事件前、`finish()` 前、报错前。
> 判断逻辑都写在 `SseChunkBuffer` 里并单测覆盖（该 flush 时不 flush 是最容易犯的错）。

**设计要点**：

- **类型切换（思考 ↔ 正文）必须先冲刷**：两者是交错到达的，不冲刷就会把思考内容插到正文后面
- **完整正文仍逐块累积**：`answer` 继续拼完整内容（历史落库、标题生成要用），只把"发送"改成批量
- **把判断逻辑抽成独立的 `SseChunkBuffer`**：`SseResponseConverter` 依赖 `SseEmitter` 很难单测，
  抽出来后"何时该发"可以用纯单测覆盖，发送本身只剩一行 `emitter.send`
- 缓冲操作 `synchronized`（流式回调可能来自不同线程）；配置成 0/负数会兜到安全值（否则等于没优化）
- **不改 SSE 契约的语义**：仍是增量、append，只是**帧数变少、单帧变长**。
  所以前端唯一要注意的是**保持 append 语义**（详见 `docs/sse-contract.md §7`）

**前端配套**：后端只减少了帧数，前端若"每个事件都重渲染整棵消息列表"照样卡 ——
渲染侧的做法（按 `requestAnimationFrame` 批量提交、思考区/正文分开累积、Markdown 延后整体渲染、
自动滚动节流）已写进 **`docs/sse-contract.md §7`**，可直接交给前端同学。

> 📌 `docs/frontend-guide.md` 曾在 P2-12 产出，后被用户删除（当时前端暂缓）。
> **2026-10-01 已重新产出，且范围扩大为完整对接文档：[`docs/前端开发指南.md`](./docs/前端开发指南.md)。**
> 用户拍板：**前端由用户自己从零写，助手不写前端代码**，本仓库只负责提供文档 ——
> 所以那一份就是前端的唯一权威入口（环境/CORS、鉴权、全接口结构、SSE 前端视角、
> 页面清单与三轮迭代范围、极简黑白色板与组件规范、联调顺序、上线 Checklist、后端未做清单）。
> SSE 帧结构的权威说明仍在 `docs/sse-contract.md`，本文 §7（API 一览）与 §6.2 是简表索引。

---

### 6.15 长期记忆检索（P2-7，决策 D4 = pg_trgm）

**原实现的问题**：`UserMemoryServiceImpl.getMemory` 只有一句
`query().like(key != null, "content", key)`，四个硬伤：

| # | 问题 | 后果 |
|---|---|---|
| 1 | 整串当一个关键词 | 模型丢一句"用户喜欢吃什么口味的菜"进来 → `LIKE '%...%'` 必然零命中 |
| 2 | `key == null` 时不加条件 | **返回该用户全部记忆**，一次性塞进系统提示词 |
| 3 | 无条数上限 / 无排序 / 无去重 | 有多少返回多少，顺序随数据库；重复记忆重复注入 |
| 4 | 不转义 LIKE 通配符 | 关键词里带 `%` 会退化成"匹配全部" |

**现在的检索链路**（`UserMemoryServiceImpl.getMemory`）：

1. `MemoryQueryParser.split(key)` 切关键词：按非中英文数字的标点/空白切分，
   丢掉长度 < 2 的碎片，去重保序，最多 `memory.max-keywords` 个；
   **切不出东西但原串非空时整串当一个词**（保证不退化成"查全部"）。
2. 有关键词 → `searchByKeywords`（多关键词 `ILIKE '%kw%' ESCAPE '\'` **OR**）→
   Java 侧按「命中关键词个数 ↓、id ↓」排序 → 按内容键去重 → 截断到 `memory.max-results`。
   数据库粗筛时多取 4 倍（`OVER_FETCH`），否则排序只在"前 N 条"内进行，等于没排。
3. 无关键词 → `latestByUser`（按 id 倒序取 N 条）= "浏览全部"，**不再是返回全部**。
4. 字面匹配**零命中**且启用了 `fuzzy` → `searchBySimilarity` 走 pg_trgm 的
   `similarity(content, ?) >= 阈值` 兜底。

**能力边界（很重要，别对 pg_trgm 抱错期望）**：

- ✅ 能救回**字面部分重叠**：`喜欢看科幻电影` ↔ `喜欢看科幻片`
- ❌ 救不了**语义相似**：`喜欢吃什么` ↔ `不吃辣` —— 那必须靠向量（pgvector），
  决策 D4 已把它推迟（见下）。

**降级设计**：pg_trgm 是**可选扩展**。没装时 `similarity()` 会直接报错，
首次异常被捕获后 `fuzzyBroken` 置位 → **永久降级为纯字面匹配**，只 WARN 一次，绝不拖垮对话。
字面匹配（ILIKE）不依赖扩展，装不装都能用 —— `006` 只影响性能与兜底能力。

**写入侧**（`saveMemory`，`@Async`）：归一化（去首尾 + 压缩空白）→ 超长截断 →
**两级去重**（① SQL `countByNormalizedContent`：归一化后完全一致；② `countSimilar`：trigram 相似度 ≥ 阈值）。
模型很容易反复保存同一条偏好，不去重会把库撑爆、把检索结果污染。

> ⚠️ **去重键必须两侧一致**：Java 用 `MemoryQueryParser.contentKey`（去掉**全部**空白 + 转小写），
> SQL 用 `lower(regexp_replace(content, '\s+', '', 'g'))`。
> 早期版本 Java 侧只是"压缩空白"，与 SQL 的"去掉空白"不一致，出现过同内容判不重复的问题 —— 改任一侧都要同步另一侧。

**向量路径已删除**：`UserMemoryMapper.search` 引用了表里不存在的 `embedding`/`category` 列，
是坏代码且从未被调用（`searchMemory` 无调用方）。D4 选定 pg_trgm 后整条删除
（`SearchMemoryRequest` / `MemorySearchResult` / `UserMemoryService.searchMemory` 一并移除）。
将来要上 pgvector：`006` 注释里有说明，SQL 可从 git 历史找回。

**工具输出**：`MemoryTool.searchUserMemory` 以前是无分隔符硬拼接（`不吃辣喜欢科幻片`），
模型很难切分；现改为 `「- xxx」` 逐行拼接 + 去重，空结果返回明确文案而不是空串。
`@P` 描述也改成"只用 1~2 个核心词"——原描述只写"关键字"，模型经常传整句话。

---

### 6.16 知识库 RAG：入库 / 检索的向量口径与隔离（P2-13）

> 一句话：**入库和检索必须用同一个向量模型**，且**检索必须带 `user_id` 过滤**。
> 这两条各对应一个"不报错但结果是错的"级别的坑。

#### ① 向量模型口径：固定用系统模型

| 侧 | 用什么模型 | 位置 |
|---|---|---|
| 入库（切分 → 向量化 → 写 `knowledge_embedding`） | 注入的 `EmbeddingModel` Bean | `KnowledgeBaseFileServiceImpl.embedding()` |
| 检索（query → 向量 → 相似度） | 同一个 `EmbeddingModel` Bean | `RagTool.ragSearch()` |

旧实现入库走 `getEmbeddingModel(configId, model)`，从**用户 API 配置**里翻 EMBEDDING 模型，
翻不到就抛异常。两个后果，第二个致命：

1. **没配过 API Key 的用户建知识库必然失败** —— 尽管系统早就配好了向量模型；
2. 就算配上，也是**跨模型检索**：不同向量模型的向量空间不可比（维度都是 1024 也不行），
   相似度分数毫无意义 → 表现是"检索结果完全不相关"，而且**没有任何报错**。

所以「用户自选向量模型」是个**伪能力**（自选即串味），已移除：
`KnowledgeBaseFileService.embedding(list, userId, knowledgeId)` 不再收 `configId`/`model`；
`KnowledgeFileDTO` 的两个字段标 `@Deprecated` 且**不再 `@NotNull`**（旧版本强制必填，
导致没配 Key 的用户连参数校验都过不去）。

> 维度来自 `embeddingModel.dimension()`（`PgVectorEmbeddingFactory`）。
> **换向量模型 → 必须同步改 `knowledge_embedding.embedding` 的 `vector(N)` 与 HNSW 索引，
> 并且已有向量全部作废、需要重新入库。**

#### ② 检索隔离：必须带 `user_id` 过滤

`RagTool.ragSearch` 旧实现构造 `EmbeddingSearchRequest` 时**没有 filter** ——
等于在整张 `knowledge_embedding` 上做全局检索，**用户 A 能检索到用户 B 的知识库内容**。
现加 `Filter`：`metadataKey("user_id").isEqualTo(String.valueOf(userId))`。

- 入库时已把 `user_id` 写进 metadata（`KnowledgeBaseFileServiceImpl`，与 `file_id`/`file_name`/`knowledge` 一起）。
- 用 **String 比较**（langchain4j 会生成 `(metadata->>'user_id')::text = '42'`）而不是 Long（会生成 `::bigint`），
  这样历史数据里 `user_id` 存成 JSON **数字还是字符串都能命中**。
- **拿不到用户上下文时拒绝检索**，绝不能退化成"不过滤"（那就是全表泄露）。
- 单测 `RagToolTest` 把这条约束钉死了，别把它"优化"掉。

**已知取舍**：检索范围目前只限本人，`KnowledgeBase.isPublic` 在 RAG 侧**不生效**
（该字段此前也从未被任何查询使用）。将来要支持"公开知识库参与检索"，
需要在入库时把 `is_public` 写进 metadata，检索时用 `Or(user_id = 我, is_public = true)`。

#### ③ 顺带修的：失败原因跨文件污染

`embedding()` 里 `failReason` 原来定义在 `for` 循环**外** ——
一个文件失败后，后面**所有**文件都会被记成同一个失败原因。已移进循环内。

### 6.17 API 文档（P3-4）

**两半，故意分开**：

| 部分 | 谁维护 | 为什么 |
|---|---|---|
| 普通 REST 接口 | **SpringDoc 自动生成**（扫 `@RestController`） | 手写登记接口清单必然随代码漂移；让事实只有一个来源 |
| `/api/chat/stream`（SSE） | **手写**：`docs/sse-contract.md` | OpenAPI 描述不了"一条连接里按序到达的多种事件"，只能登记入口参数 |

**落在哪**：`nexus-agent-web/.../config/OpenApiConfig.java`（只写文档元信息：标题、鉴权方式、外部文档链接）。
依赖 `springdoc-openapi-starter-webmvc-ui`，**版本在父 `pom.xml` 的 `<dependencyManagement>` 里统一管理**（`springdoc.version`）。

**鉴权怎么试**：本项目登录态在**请求头 `token`**（不是 `Authorization: Bearer`），
所以安全方案声明成 `apiKey / in: header / name: token`；Swagger UI 右上角 Authorize 填的就是登录返回的 JWT。
免鉴权接口（login / register / password / email）在方法上用 `@SecurityRequirements`（空）覆盖掉全局要求 ——
否则 Swagger 给它们也加锁，试接口的人会以为必须先登录。

**⚠️ 安全取舍（重要）**：

- Swagger 相关路径（`/swagger-ui.html`、`/swagger-ui/**`、`/v3/api-docs**`）被加进了
  `LoginCheckInterceptor` 的白名单 —— 不给豁免的话 Swagger UI 自己不带 token，页面根本打不开。
- 于是**是否对外暴露只由 `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` 决定**：
  `application.yml` 默认开（本地联调方便），**`application-prod.yml` 里默认关**。
- 打开前先想清楚：接口清单 = 后端攻击面的地图。

**怎么加接口文档**：在 Controller 上写 `@Tag`、在方法上写 `@Operation`。
**不要**在 `OpenApiConfig` 里手工登记 URL（那是把"事实"抄成第二份）。

### 6.18 部署形态（P3-3）

**产物**（都在仓库根目录，除了沙盒服务自己的那份）：

| 文件 | 作用 |
|---|---|
| `Dockerfile` | Java 应用镜像：maven 多阶段构建 → `eclipse-temurin:21-jre`，非 root、`MaxRAMPercentage=75`、`HEALTHCHECK` |
| `.dockerignore` | 排除 `.git`、`target/`、本地 `application-dev.yml`、`docs/` |
| `docker-compose.yml` | 一键起全套：pgvector / redis / box / app（含 depends_on 健康检查） |
| `.env.example` | **应用**的环境变量模板（与 `nexus_agent_box/.env.example` 是两份，别混） |
| `nexus_agent_box/docker-compose.yml` | 沙盒服务，**以镜像为准** |
| `nexus_agent_box/docker-compose.override.yml` | 开发用（自动合并）：把源码挂回去热重载 |

**修掉的坑：build + volumes 混用**
原 `nexus_agent_box/docker-compose.yml` 同时写了 `build: .` 和把源码目录挂进容器，
结果是「镜像里有代码，运行时又被宿主机目录盖掉」——
同一个镜像在不同机器上跑出不同行为，且会冒出"我明明改了却没生效"这类最难排查的问题。
现在拆成两份：主文件以镜像为准，override 只服务本地开发。

**优雅停机**（`application.yml`）：

- `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`。
  `docker stop` / k8s 滚动更新发的都是 SIGTERM，不配就会把"对话进行到一半"的 SSE 连接直接掐断。
- Dockerfile 用 `ENTRYPOINT ["sh","-c","exec java ..."]`：**必须让 java 是 PID 1**，
  否则 SIGTERM 送给 shell 而不会转发给 java，上面那两项配置等于白写。

**健康检查**：只暴露 `/actuator/health` 与 `/actuator/info`，且 `show-details: never`。

- ⚠️ 绝不要加 `env` / `heapdump` / `threaddump` —— `/actuator/env` 会把数据库密码、
  各家 API Key **原样**吐出来。
- `/actuator/health` 与 `/actuator/info` 在 `LoginCheckInterceptor` 白名单里
  （容器探针不会带 token），所以对外只能看到 `{"status":"UP"}`，看不到组件细节 ——
  这正是 `show-details: never` 的原因。

**容器里的坑**：

- `application.yml` 默认 profile 是 `dev`，而 `application-dev.yml` 被 gitignore
  （镜像里没有）→ **容器必须跑 prod**：`SPRING_PROFILES_ACTIVE=prod`。
- 容器网络内互访用 **compose 服务名**，不是 localhost：`SERVICE_IP=postgres`、`BASE_URL=http://box:8000`。
- `application-prod.yml` 写死了 redis 密码 `redis`，改 compose 时要两边一起改。
- PG 用 `pgvector/pgvector:pg16`：官方 postgres 镜像装不了 `vector` 扩展
  （`docs/sql/001` 有 `CREATE EXTENSION vector`）。数据卷首次创建时会自动按序执行 `001…007`。

---

### 6.19 会话重命名与搜索（P3-1 补）

**重命名** `PUT /api/history/{sessionId}/title`，body `{title}`：

- 更新 SQL **必须带 `user_id`**（`ChatHistoryListMapper#updateTitleBySessionAndUserId`）。
  只按 `session_id` 更新 = 改个参数就能改别人会话的标题（越权写）。
- `sessionId` 在 Java 侧先用 `UUID.fromString` 验一遍：它会被拼进 `::uuid`，
  非法值会让 PostgreSQL 抛异常变成 500，提前拦掉才有可读的提示。
- 顺带 `update_time=now()`：列表按 `update_time` 倒序，不更新用户会以为改名没生效。

**搜索** `GET /api/history/search?keyword=`：

- 两段命中：**① 会话标题**（内存里过滤，`title` 可能为 null）+ **② 消息正文**（SQL）。
  合并时标题命中优先，且**同一会话只出现一次**（`matchType=TITLE` / `CONTENT`）。
- 正文匹配走 `ChatMemoryMapper.xml#searchHits`。`content` 是 **jsonb**
  （langchain4j 一条 `ChatMessage` 的序列化结果），不同消息类型正文位置不同：
  `AiMessage`/`SystemMessage`/`ToolExecutionResultMessage` 在顶层 `text`，
  `UserMessage` 在 `contents[].text`。
  **不能直接写 `content::text ilike`** —— 那样会把 JSON 的键名
  （`type`、`text`、`USER`…）当成正文，用户搜 `type` 会命中全部会话。
- **`escape '\'` 与转义必须成对**：关键词里的 `%` `_` `\` 由
  `ChatHistoryListServiceImpl#toLikePattern` 转义，SQL 里带 `escape '\'`。
  少了任何一方，用户搜一个 `%` 就退化成「匹配全部」。
- 排除 `type='SYSTEM'` 的消息（系统提示词不是用户看得见的对话内容）。
- **索引**：匹配表达式含 `jsonb_array_elements`（set-returning），
  PostgreSQL 要求索引表达式 immutable，**建不了表达式索引** → 目前是全表扫描。
  加速路径与代价记在 `docs/sql/README.md` 的「尚未处理」里。

---

## 7. API 一览（真实前缀是 `/api`）

| 方法 | 路径 | Controller | 说明 |
|---|---|---|---|
| POST | `/api/chat/stream` | `ChatController` | SSE 流式对话 |
| GET | `/api/chat/model` | `ChatController` | ⚠️`baseUrl`/`token` 走 **query 参数**，密钥会进日志 |
| GET | `/api/history` | `ChatHistoryController` | 会话列表 |
| GET | `/api/history/{sessionId}` | `ChatHistoryController` | 会话消息 |
| DELETE | `/api/history?sessionId=` | `ChatHistoryController` | 删除会话 |
| PUT | `/api/history/{sessionId}/title` | `ChatHistoryController` | 会话重命名（P3-1 补）。只能改自己的；不是本人 → `NotFoundException` |
| GET | `/api/history/search?keyword=` | `ChatHistoryController` | 会话搜索（P3-1 补）：标题 + 消息正文，合并去重。见 §6.19 |
| POST | `/api/user/login` | `UserController` | 登录（免鉴权） |
| POST | `/api/user/register` | `UserController` | 注册（免鉴权） |
| PUT | `/api/user/password` | `UserController` | 设置/重置密码（免鉴权） |
| POST | `/api/common/email` | `CommonController` | 邮件验证码（免鉴权） |
| POST/GET | `/api/user/api-config` | `UserController` | 增改 / 查 用户 LLM 配置 |
| POST/GET | `/api/user/mcp-config` | `UserController` | 增改 / 查 MCP Token |
| GET/DELETE | `/api/user/user-memory[/{id}]` | `UserController` | 长期记忆 查 / 删 |
| GET | `/api/user/quota` | `UserController` | 当前用户 token 配额与用量（P2-8 遗留，见 §6.13）。失败返回 `degraded=true`，不抛异常 |
| POST | `/api/file` | `FileController` | 上传文件（`files[]`+`bizType`） |
| GET | `/api/artifact?sessionId=` | `ArtifactController` | 列出某会话里 AI 交付的产物（P2-10，供前端"本会话文件"面板） |
| DELETE | `/api/artifact/{id}` | `ArtifactController` | 删除产物：**先删记录、再尽力删 OSS 对象**（P2-10） |
| POST | `/api/file/image` | `FileController` | 上传图片 |
| GET | `/api/file` | `FileController` | 当前用户文件列表 |
| POST | `/api/knowledge` | `KnowledgeController` | 建知识库 |
| POST | `/api/knowledge/file` | `KnowledgeController` | 文件入知识库（触发向量化）。⚠️ P2-13：`configId`/`model` 已废弃且**不再必填**，向量模型固定用系统模型 |
| GET | `/api/knowledge/list`、`/{id}` | `KnowledgeController` | 知识库列表 / 详情 |
| GET | `/api/mcp/service` | `McpController` | 从服务端拉 MCP 列表 |
| GET/POST/PUT | `/api/mcp` | `McpController` | 查 / 存 / 改 |
| GET/DELETE | `/api/mcp/{id}` | `McpController` | 详情 / 删除 |
| WS | `/ws/{userId}` | `WebSocketService` | 标题等实时推送 |

**鉴权约定**：请求头 `token: <JWT>`（❗不是 `Authorization: Bearer`）。
`LoginCheckInterceptor` 拦截 `/**`，白名单：`/api/user/login|register|password`、`/api/common/email`、
Swagger 相关路径（`/swagger-ui.html`、`/swagger-ui/**`、`/v3/api-docs**`）。
⚠️ 文档路径免鉴权，所以**是否暴露由 `springdoc.*.enabled` 决定**（prod 默认关，见 §6.17）。

> 📄 生成式文档见 §6.17：启动后访问 `/swagger-ui.html`；SSE 接口的手写契约在 `docs/sse-contract.md`。

---

## 8. 代码规范

- **分层**：Controller（薄，只做参数校验和转发）→ Service/ServiceImpl → Mapper。业务逻辑不要写在 Controller。
- **注入**：构造器注入为主。Lombok `@RequiredArgsConstructor` 或手写构造函数。现存少量 `@Resource` 字段注入（`ChatMemoryServiceImpl`、`PgVectorEmbeddingFactory`），新代码不要效仿。
- **DTO**：优先 Java `record`（`ChatDTO`、`ChatUserMessage`、`ModelDTO`）。
- **返回信封**：统一 `Result{code,msg,data,total}`，`code=0` 成功、`1` 失败。SSE 接口例外（直接返回流）。
- **异常**：抛自定义异常（`ValidationException` / `UnauthorizedException` / `NotFoundException` / `NotSupportException` / `ParserFileException` / `PermissionDeniedException`），由 `GlobalExceptionHandler` 统一转 `Result`。
- **日志**：`@Slf4j`。**禁止 `System.out.println`**（现存 2 处违规：`KnowledgeBaseFileServiceImpl`、`NexusAgentWebApplication` 打印 BASE_URL）。
- **注释**：中文，类头保留 `@author 胡志坚 / @version / 创造日期 / 说明` 模板。
- **MyBatis-Plus**：简单 CRUD 用 `ServiceImpl`/`lambdaQuery()`；复杂 SQL 写在 `nexus-agent-mapper/src/main/resources/mapper/*.xml`。
- **❗手写 SQL 必须显式列出列名，禁止 `select *`。** 原因：MyBatis 会按**位置**把结果集列套到 resultMap 上，
  一旦表物理列顺序与 resultMap 声明顺序不一致（重建 schema、加列、调序都可能造成），
  就会出现「用 Timestamp 处理器读 uuid 列」这类难查的错误。曾因此让对话接口直接 500。
- **❗鉴权/解析类工具方法要区分「抛异常」与「返回 null」。** `JwtUtil` 系列失败时返回 null 而非抛异常，
  调用方只 catch 异常会漏判，导致无效凭证被放行。
- **格式化**：保持 google-java-format 风格，改动后尽量只提交相关行，避免整文件重排造成 diff 噪音。

---

## 9. 数据库

**基线已重新设计**：`docs/sql/001_baseline.sql`（v2.0，12 表 + 2 枚举 + 4 外键 + 9 索引）。
用法、设计理由、索引清单、遗留问题见 `docs/sql/README.md`。

- PostgreSQL 必须可安装 pgvector（基线已含 `CREATE EXTENSION IF NOT EXISTS vector`）
- **没有 Flyway / Liquibase。** 表结构变更一律新 `docs/sql/NNN_*.sql`，并在本文件 §11 登记
- 向量维度 **1024**（`text-embedding-v4`）；基线已把列定为 `vector(1024)` 并建 HNSW 索引
- `knowledge_embedding` 应用也会用 `createTable(true)` 建它，但表已存在时不会覆盖，
  所以**基线里的定义才是权威**
- ✅ **基线已于 2026-09-23 在目标库实际执行验证：0 错误、12 张表**。脚本不吞异常，
  因此主键/外键/索引/唯一约束/`CREATE EXTENSION vector` 均已确认生效。复核查询见 `docs/sql/README.md`

### 9.1 ⚠️ 历史背景：库被清空过，schema 曾严重不完整

开发库曾被自动程序清空，之后重建出的结构缺失严重（无任何主键/索引/约束、`sys_file` 丢失、
`knowledge_base_file` 缺列）。旧数据已确认可舍弃，因此 2026-09-23 按「**代码需要什么**」
重新设计了基线，而不是继续沿用。

**推导方法**（后续改表请沿用）：以 `nexus-agent-domain` 实体 + `nexus-agent-mapper` 的 XML
+ Service 层真实 SQL 三方交叉推导，不凭经验猜。

### 9.2 新基线的关键设计（改动时不要破坏）

- **所有 id 用 `GENERATED BY DEFAULT AS IDENTITY`**（不是 `ALWAYS`）：因为实体的 `@TableId`
  多为默认 `ASSIGN_ID`（应用传雪花 ID），而 `ChatMemoryMapper.insertBatch` 又不带 id、依赖数据库生成。
  改成 `ALWAYS` 会让应用传 ID 时报错。
- **`users.email` 有 UNIQUE 约束**：登录/注册都走 `query().eq("email",...).one()`，
  MP 的 `.one()` 命中多行会抛异常 → 唯一约束是硬需求。
- **`user_memory.source` 是 `varchar(64)`**：要装得下 36 字符的 UUID 会话 ID（原 `char(32)` 会溢出）。
- **`sys_file.fail_reason` ≥ 450**：`FileServiceImpl` 会写入 `substring(msg, 0, 450)`。
- **`knowledge_embedding.embedding` 必须带维度**：pgvector 无法在无维度列上建索引，
  无维度会导致 RAG 全表扫描，且换错模型不会被拦住。
- **只给 4 个关系加外键**（`user_config`、`knowledge_base_file`×2、`skill_mcp_information`）。
  `chat_memory` / `chat_history_list` / `mcp_information` **刻意不加** —— 沿用原作者决定
  （`开发日志.md` 4.20：存在「先插子行、父信息异步补」的写入顺序）。
- **`user_memory` 没有 `embedding` 列（仍然如此）**：D4 已定为 pg_trgm，**不恢复向量检索**；
  语义相似检索（pgvector）推迟到记忆量上来之后。详见 §6.15 与 `docs/sql/006_*.sql`。

### 9.3 字段层面的坑

- `user_config.llm_api_token` 是 **`jsonb`**；`mcp_token` 是 `varchar(512)`；`salt` 是 `char(16)`
- `knowledge_base_file` 的主键是 **(knowledge_base_id, file_id) 复合主键**，实体没有 id 字段
- `chat_memory.session_id` 是 `uuid`；`content` 是 `jsonb`（依赖 JDBC `stringtype=unspecified`）
- `knowledge_base.id` 是 `int4` 而其他表用 `bigint` —— 因为实体 `KnowledgeBase.id` 是 `Integer`，
  改类型需要同时改代码，当前保持不动
## 10. 分支与提交规范

- **开发分支：只有 `ds`。** 从 `ds` 拉短命分支（`feat/xxx`、`fix/xxx`）做完 squash 回 `ds`，或直接在 `ds` 上小步提交。
- ❌ 禁止提交：`application-dev.yml`、`.env`、任何真实密钥。
- ✅ 提交信息建议：`<type>(<scope>): <中文描述>`，如 `fix(box): execute_cmd 打到错误路由`。
- **提交前自检**：
  1. `$MVN -DskipTests compile` 通过
  2. `git diff` 里没有密钥 / 调试输出
  3. 若改了 schema → `docs/sql/` 里有对应 SQL
  4. 若改了对外接口/工具/事件 → 同步更新本文件 §7 / §6

---

## 11. 变更记录（本文件维护的更新日志）

> 规则：每次**结构性**变更（新增模块/接口/表/工具、修改事件契约、升级依赖）追加一行。
> 只改实现细节不必写。倒序排列。

| 日期 | 变更 | 影响文件 | 备注 |
|---|---|---|---|
| 2026-09-23 | 新建 `AGENTS.md`，替代已过期的 `CLAUDE.md` 作为开发入口 | `AGENTS.md` | 核对基准 `86f3a07` |
| 2026-09-23 | 新建 `重构计划.md`（P0–P3 分阶段计划） | `重构计划.md` | 见文件内优先级 |
| 2026-09-23 | 实测编译基线：5 模块全部 `BUILD SUCCESS` | — | 首次联网拉依赖约 4 分钟；见 §2.4 |
| 2026-09-23 | 决策落定：D1 保持 E2B 沙盒、D3 Skill 用本地目录扫描 | §14 | D2/D4 仍待定 |
| 2026-09-23 | **执行 P0 批次（15 项）**：删泄漏类、修 `execute_cmd` 路由、修向量写入错位、修越权、密钥出 URL、异常兜底、版本统一、加 Maven Wrapper、建 `docs/sql` | 见 §12.0 明细表 | 编译与测试编译均 BUILD SUCCESS |
| 2026-09-23 | `CLAUDE.md` 停维护，改为指向本文件 + 列出原有错误说法 | `CLAUDE.md` | 避免两份文档互相打架 |
| 2026-09-23 | 新增 §16 文件与产物能力设计；新增决策 D5 | §16、§14 | 回答产物交付与本地文件空间可行性 |
| 2026-09-23 | **接口破坏性变更**：`GET /api/chat/model` → `POST /api/chat/model` | `ChatController`、`ModelListDTO` | 安全修复（密钥不再进 URL），前端需同步 |
| 2026-09-23 | **数据库基线重新设计**（v2.0）：12 表补齐主键/4 外键/9 索引、`users.email` 唯一、`vector(1024)` + HNSW、重建 `sys_file`、删 2 张死表 | `docs/sql/001_baseline.sql`、`docs/sql/README.md` | 库曾被清空，旧数据确认可弃。详见 §9 |
| 2026-09-23 | 修 `KnowledgeBaseFileMapper.xml` 的 `fail_name` 笔误 → `file_name` | `KnowledgeBaseFileMapper.xml` | 知识库入库/详情查询原本必报错 |
| 2026-09-23 | `User` 实体补 `@TableId(type = IdType.AUTO)` | `domain/User.java` | 原先 `getById`/`updateById` 不可用、`save()` 后取不到 id |
| 2026-09-23 | §9 全面重写（数据库章节），并同步 §16 相关说明 | `AGENTS.md` | — |
| 2026-09-23 | ✅ 基线在目标库执行验证通过（0 错误 / 12 表，含主键·外键·索引全部生效） | `docs/sql/README.md` | 复核查询已入库 |
| 2026-09-23 | **P1-12 完成**：补根 `README.md` 快速开始 + 配置模板 `application-dev.yml.example` / `nexus_agent_box/.env.example`，并填充空白的 `nexus_agent_box/README.md` | `README.md`、`nexus_agent_box/README.md`、两个 `.example` | 修复根因 R2「没有开箱路径」：dev 配置被 gitignore 导致新环境必然起不来 |
| 2026-09-23 | **端到端实测通过**并修 2 个运行期 bug（`select *` 位置错配、无效 token 被放行） | `ChatMemoryMapper.xml`、`LoginCheckInterceptor.java` | 应用真实启动 + 对话 + 会话读写 + 401 鉴权全部验证；详见 §12.0 与递归计划 M1 |
| 2026-09-23 | **P1-1 + P1-2 完成**：新增 `RunContext` 取代跨线程 ThreadLocal，解除聊天记录的 Redis 依赖，顺带修掉对话读记忆的越权 | `RunContext.java`(新)、`PgChatMemoryStore`、`ChatMessageConverter`、`ChatContextFactory`、`ChatServiceImpl`、`ChatMemoryService`；删除 `MessageMetadataContext` | 3 轮对话实测：附件元数据完整保留、未串轮、全程不依赖 Redis session key |
| 2026-09-23 | **沙盒链路实测通过**（M1#3 验收完成）；并修正 §6.2 的 SSE 事件名（文档原来写错） | `AGENTS.md` | AI 成功调用 `create_box` → `execute_cmd` → 拿到真实 stdout；工具事件名实为全大写 |
| 2026-09-23 | **P1-4 完成**：引入 `ToolRegistry`，工具注册改为声明式；`ChatContextFactory` 不再认识任何具体工具 | 新增 `tools/registry/`3 个类；4 个工具类实现 `AgentToolSet`；`AGENTS.md §6.4` 重写 | 实测：`enableRag` 开关由 `RagTool.enabled()` 决定，日志逐项打印启用/跳过 |
| 2026-09-23 | **P1 批次完成**：P1-6/7/8/9/11/13 六项一次性做完 | 新增 `sandbox/`、`mcp/`、`properties/AgentProperties`、3 个单测类；重写 `SafeExecuteToolHandler`、`PgChatMemoryStore`、`BoxTool`；6 个旧测试改为人工测试 | 详见 §6.5（工具错误契约）与 §15（运行时配置）；实测 4 组端到端验证通过 |
| 2026-09-24 | **P2-1 + P2-2 完成**：Skill 系统落地（本地目录扫描），`ChatDTO.skills` 真实生效；旧 DB 注册表方案整体删除 | 新增 `skills/SkillLoader.java`、`skills/README.md`、`SkillLoaderTest.java`（11 个单测）；删除 `SkillMcpInformation` 实体/Mapper/XML/Service/Impl；改造 `ChatContextFactory`（与 MCP 合并 `toolProviders`）、`ChatAssistant`（注入 `{{runtimeCapabilities}}`）、`ChatServiceImpl`、`ModelSystemContent`；新增 `docs/sql/002_drop_skill_mcp_information.sql` | 新增 §6.9；实测发现库**刻意排除 `scripts/`** 目录，已写入文档与回归测试；顺带把鉴权改为 `nexus.agent.security.enabled` 开关 + 启动 WARN 提示 |
| 2026-09-24 | `AGENTS.md` 结构修复：消除两组重号章节（两个 §6.5、两个 §15） | `AGENTS.md` | Skill 系统改为 §6.9；「文件与产物能力」改为 §16（原与「运行时配置」重号）；同步全部交叉引用 |
| 2026-09-24 | **P1-10 完成**：启动配置自检（一次性列出缺失项而非"一次报一个"）+ 消除模型静默回退 | 新增 `config/StartupConfigValidator.java`、`StartupConfigValidatorTest.java`（8 个单测）；`ChatContextFactory` 三处回退加日志；`AgentProperties` 加 `Startup.failFast`；两个 yml 补 `startup` 段 | 新增 §6.10；必需项（datasource/对话模型 Key/API_KEY_SECRET）默认 fail-fast，建议项只 WARN 并写明"哪项能力不可用"；README 排查表同步 |
| 2026-09-24 | **P2-4 完成**：工具治理（重复调用拦截 + HTTP 响应超时） | 新增 `tools/ToolCallGuard.java`、`ToolCallGuardTest.java`（11 个单测）；13 个 `@Tool` 方法接入治理（其中 4 个补了 `@ToolMemoryId` 参数）；`WebClientConfig` 加 `responseTimeout`；`AgentProperties` 加 `Tools`；两个 yml 补 `tools` 段；`§6.4` 修正过时示例并加「新增工具检查清单」 | 新增 §6.11；`tools.http-timeout` 默认 100s（< SSE 120s）；`duplicate-threshold` 默认 2（第 3 次起拦） |
| 2026-09-24 | **P2-6 完成**：可观测性（每次 Run 一行结构化验算日志）+ **SSE 新增 `error` 事件带 trace_id** | 新增 `observability/RunMetrics.java`、`RunMetricsReporter.java`、`RunMetricsTest.java`（11 个单测）；`ChatServiceImpl` 生成 runId 并挂 `onToolExecuted`/`onCompleteResponse`/`onError`；`SseResponseConverter` 加 runId 与 error 事件；`MessageType` 加 `ERROR`；`AgentProperties` 加 `Observability`；两个 yml 补 `observability` 段 | 新增 §6.12；§6.2 契约表加 `error` 行（补齐 P1-9 遗留的「SSE 错误事件带 trace_id」）；费用只在配了单价时显示 |
| 2026-09-24 | **P2-3 完成**：模型能力矩阵 —— 额外参数改为**按服务商下发** | 新增 `model/ModelCapabilityResolver.java`、`ModelCapabilityResolverTest.java`（8 个单测）；`ChatContextFactory` 抽出 `buildExtraBody` 并按能力过滤；`AgentProperties` 加 `Model`/`ProviderCapability`；两个 yml 补 `model.providers` 段 | §6.3 重写（含能力表与「未知即不下发」的取舍说明）；⚠️ **行为变更**：未命中服务商的额外参数不再下发（此前无条件全塞）；默认模型的参数仍在 yml 的 `custom-parameters`（已去掉 DeepSeek 不认的 `enable_search`） |
| 2026-09-24 | **P2-8 完成**：token 配额（事前拦截 + 事后原子记账） | 新增 `docs/sql/003_add_user_token_quota.sql`、`service/QuotaService` + `QuotaServiceImpl`、`exception/QuotaExceededException`、`QuotaServiceTest`（14 个单测）；`User` 加 `tokenQuota`/`tokenUsed`；`UserMapper.java`/`.xml` 加原子累加语句；`ChatServiceImpl` 接入；`UserServiceImpl.register` 写默认配额；`GlobalExceptionHandler` 加映射；两个 yml 补 `quota` 段 | 新增 §6.13；⚠️ **需先执行 `003` 才能启动**（实体已含新列，`Base_Column_List` 已引用）；存量用户 `token_quota` 为 NULL = 不限制，行为不变 |
| 2026-09-24 | **P2-9 完成**：MCP 不可用时明确告知模型（不再静默丢弃） | `McpInformationService.getMcp` 返回 `McpResolution{provider, unavailableNames}`；`ChatContext` 加 `mcpUnavailable`；`ChatContextFactory`/`ChatServiceImpl` 适配；新增 `ChatServiceImpl.composeCapabilities`（+ `RuntimeCapabilitiesTest` 5 个单测）；提示词变量 `{{availableSkills}}` → **`{{runtimeCapabilities}}`**（同时承载技能清单与 MCP 状态），`ModelSystemContent` 加「不可用则如实告知、不要重试」的指引 | §6.7 重写（顺带修正「每次新建客户端且不关闭」这条已过时的描述，P1-6 已修）；§6.9 同步变量名 |
| 2026-09-24 | **P2-10 部分完成**：产物交付链路打通（**决策 D5 已定为 (c) 虚拟工作区**） | 新增 `tools/BoxTool.publishArtifact`（工具）+ `service/ArtifactService`/`Impl` + `docs/sql/004_add_sys_file_session_id.sql`；`MessageType.ARTIFACT` + `MessageVO.artifact` + `SseResponseConverter.writeArtifact`；`ChatServiceImpl` 在 `onToolExecuted` 里识别并落库/推事件（+ `ArtifactExtractionTest` 5 个单测）；Python 侧 `oss_utils.object_prefix()` 与 `/file` 路由带 `user_id`；提示词加「产出文件必须用 publish_artifact 交付」 | §16.2 补实施结果表；§6.2 契约表加 `artifact` 行；⚠️ **需先执行 `004`**（实体已加 `sessionId`）。⬜ E2B 模板预装 Office 库需用户在 E2B 侧执行 |
| 2026-09-24 | **P2-10 收尾**：虚拟工作区的会话文件列表/删除接口 | 新增 `controller/ArtifactController` + `ArtifactService.listBySession/delete` + `AliOssUtil.deleteObject/objectNameOf`（+ `OssObjectNameTest` 6 个单测）+ `docs/sql/005_add_sys_file_session_index.sql` | ⚠️ **需执行 `005`**（列表查询的索引）；所有查询/删除都带 `user_id` 过滤（越权防护）；删除顺序为先删记录再尽力删对象；§7 API 一览已登记；§16.2 实施结果表加 ⑤ |
| 2026-09-24 | **P2-12 完成**：SSE 增量合并（修"输出卡顿"）+ **新增前端对接文档** | 新增 `converter/SseChunkBuffer.java`（+ `SseChunkBufferTest` 9 个单测）；`SseResponseConverter` 改为批量推送并在工具/产物/结束/报错前强制冲刷；`AgentProperties.Sse` 加 `flushMaxChars`/`flushInterval`；两个 yml 补 `sse` 配置；**新增 `docs/frontend-guide.md`** | 新增 §6.14；§6.2 加"批量增量"提示；§15 补 2 行配置。根因：原来每个 token 推一帧（千字回复=上千帧）→ 前后端被高频小包拖慢；现攒 200 字符/60ms 推一帧 |
| 2026-09-30 | **P2-7 完成**：长期记忆检索重写（**决策 D4 拍板 = pg_trgm**） | 新增 `utils/MemoryQueryParser`（+ `MemoryQueryParserTest` 12 个）、`UserMemoryServiceImplTest`（18 个）；`UserMemoryServiceImpl` 重写检索与写入（多关键词 OR + Java 排序/去重 + pg_trgm 兜底 + 写入两级去重）；`UserMemoryMapper`/XML 换成 5 条专用语句；**删除坏死的向量路径**（`search`/`SearchMemoryRequest`/`MemorySearchResult`/`searchMemory`，同步删 `ModelTest.testEmbeddingSearch`）；`MemoryTool` 输出改逐行 `- xxx`；`AgentProperties.Memory` 加 6 项；新增 `docs/sql/006_add_user_memory_trgm_index.sql` | 新增 §6.15；⚠️ **需执行 `006`**（`pg_trgm` 扩展 + GIN 索引）才有模糊兜底；**不执行也能正常跑**（自动降级为纯字面匹配，只 WARN 一次）。修掉的四个硬伤见 §6.15 表格 |
| 2026-09-30 | **P2-5 完成**：SSE 契约 v2（事件名统一小写 + `seq`/`runId` 信封 + `run` 首帧） | 新增 `em/SseEventType`（事件名枚举，杜绝字面量漂移）、`vo/SseEvent`（统一信封）、**`docs/sse-contract.md`（权威契约文档）**、`SseContractTest`（10 个单测）；`SseResponseConverter` 所有事件改走唯一的 `dispatch(SseEvent)` 出口并写入 SSE 原生 `id:`；`ChatServiceImpl` 建好 writer 后调 `writer.start()` | ⚠️ **破坏性变更**：① 事件名 `TOOL_EXECUTION`/`TOOL_EXECUTION_RESULT` → 小写；② 所有 data 多一层信封（原载荷移到 `data`）；③ `session_id` 事件取消（并入首帧 `run`）；④ `finish` 的 data 由 `"DONE"` 改 `{"status":"DONE"}`；⑤ `error` 的 `runId` 提到信封层。当前无存量前端，前端由我们在 P3-1 写，故一次改干净；迁移对照表见契约文档 §6。⬜ **服务端回放未做**（需事件持久化），断线后按 `sessionId` 重拉历史，已写进契约文档 §5。README 文档索引同步（原指向已删除的 `frontend-guide.md`） |
| 2026-09-30 | **P2-13 完成**：知识库向量口径统一 + **修掉 RAG 跨用户越权** | `KnowledgeBaseFileServiceImpl`（注入系统 `EmbeddingModel`、**删除** `getEmbeddingModel()`，顺带修 `failReason` 跨文件污染）；`KnowledgeBaseFileService.embedding` 去掉 `configId`/`model` 参数；`KnowledgeFileDTO` 两字段标 `@Deprecated` 且**不再必填**；`KnowledgeBaseServiceImpl` 调用同步；`RagTool.ragSearch` 检索加 `user_id` 过滤（无用户上下文时拒绝检索）；新增 `RagToolTest`（4 个）+ `KnowledgeBaseFileServiceImplTest`（4 个） | ⚠️ **行为变更**：① 向量模型固定为系统模型，用户自选向量模型能力移除（自选会让入库/检索向量空间不一致 → 检索结果完全不相关且不报错）；② `POST` 上传知识库不再要求 `configId`/`model`；③ RAG 只检索**本人**知识库（此前是全表检索，用户 A 能命中用户 B 的内容）。过滤用 `::text` 比较，历史数据里 `user_id` 存成 JSON 数字或字符串都能命中。测试 158（146 通过 + 12 人工跳过），0 失败 |
| 2026-09-30 | **P2-8 遗留完成**：配额周期重置 + 用量查询接口 | 新增 `em/QuotaPeriod`（周期枚举，容错解析）、`vo/QuotaVO`、`docs/sql/007_add_user_token_quota_period.sql`（`users` 加 `token_period`/`token_period_start`）；`User` 加两字段；`UserMapper.java`/`.xml` 加 `resetQuotaPeriod`（带 WHERE 的原子 UPDATE）；`AgentProperties.Quota` 加 `period`；`QuotaServiceImpl` 抽出 `resetPeriodIfDue`/`periodOf`；`UserController` 加 `GET /api/user/quota`；新增 `QuotaPeriodTest`（7 个）+ `QuotaServiceTest` 扩到 29 个 | ✅ **惰性重置**：不跑 `@Scheduled`（挂了会导致全员配额不刷新、多实例还要抢锁），改为校验/查询时顺手判断；并发只命中一个，天然幂等。✅ `007` 未执行时**降级为按累计用量判定**（保守，不放行），周期值非法按 `NONE` —— 行为与 `003` 完全一致。✅ 查询接口失败返回 `degraded=true` 而不抛异常；身份取自 `UserContextHolder`，**不接受入参**（防越权）。⚠️ **需执行 `007`** 才能用到周期重置；两个 yml 补 `period: NONE`。测试 190（178 通过 + 12 人工跳过），0 失败。§6.13 局限 ① 已划掉 |
| 2026-09-30 | **P3-4 完成**：API 文档（SpringDoc 自动生成 + 手写 SSE 契约） | 父 `pom.xml` 加 `springdoc.version=2.8.13`（dependencyManagement）+ web 模块引 `springdoc-openapi-starter-webmvc-ui`；新增 `nexus-agent-web/.../config/OpenApiConfig`（元信息 + `token` 请求头安全方案 + 指向 `docs/sse-contract.md`）、`OpenApiConfigTest`（4 个）；8 个 Controller 全部补 `@Tag`，关键接口补 `@Operation`，4 个免鉴权接口加 `@SecurityRequirements`；`WebInterceptorConfig` 白名单加 swagger 路径；`application.yml` 默认开、`application-prod.yml` 默认关 | ⚠️ **安全取舍**：Swagger 路径免鉴权（否则页面自身不带 token 打不开），因此**是否暴露只由 `springdoc.*.enabled` 决定**，prod 默认 `false`。⚠️ SSE 接口**不在** OpenAPI 里描述帧结构（OpenAPI 表达不了同一连接内的事件序列），只在 `@Operation` 里把人引到手写契约。⬜ **待用户验证**：重启后 `/v3/api-docs` 应返回 200、`/swagger-ui.html` 可打开；若 SpringDoc 与 Boot 3.5 有兼容问题导致启动失败，把两个 `enabled` 设为 `false` 即可降级。新增 §6.17；README 补「API 文档」章节；测试 194（182 通过 + 12 人工跳过），0 失败 |
| 2026-09-30 | **P3-3 完成**：部署形态固化（修 build+volumes 混用、应用镜像、健康检查、优雅停机） | 新增根目录 `Dockerfile`（maven 多阶段 → `temurin:21-jre`，非 root + `HEALTHCHECK` + `exec java` 保 PID 1）、`.dockerignore`、`docker-compose.yml`（pgvector/redis/box/app 一键起，depends_on 走健康检查）、`.env.example`（**应用**的环境变量模板）；`nexus_agent_box/docker-compose.yml` **去掉源码挂载并加 healthcheck**，热重载移到新建的 `docker-compose.override.yml`（Compose 自动合并）；`nexus_agent_box/Dockerfile` 装 curl 供健康检查；`application.yml` 加 `server.shutdown=graceful` + `timeout-per-shutdown-phase=30s` + actuator（只暴露 health/info、`show-details: never`）；`WebInterceptorConfig` 白名单加 `/actuator/health`、`/actuator/info`；`.gitignore` 忽略 `/.env` 与 `/nexus_agent_box/.env` | ✅ **修掉的坑**：`build: .` + 挂载源码混用 → 跑的代码 ≠ 镜像里的代码（同一镜像不同行为、"改了没生效"最难排查），现拆成「主文件以镜像为准 + override 只服务开发」。⚠️ **安全**：actuator 只开 health/info（`env` 会把库密码与 API Key 原样吐出），health 免鉴权故 `show-details: never`。⚠️ **容器里的坑**：镜像里没有 `application-dev.yml` → 必须 `SPRING_PROFILES_ACTIVE=prod`；互访用 compose 服务名而非 localhost（写 localhost 会连到自己）。⬜ **未在真机验证**：本机无 Docker，`docker build` / `compose up` 未实跑，首次使用请先 `docker compose config` 与 `docker build` 各跑一遍。新增 §6.18；README 补「部署」章节；测试仍为 194，0 失败 |
| 2026-10-01 | **P3-1 转向**：前端由用户自己写，本仓库产出 `docs/前端开发指南.md` | 新增 `docs/前端开发指南.md`（12 节）：环境准备（⚠️ 后端未配 CORS → 必须 dev proxy / 同源）、鉴权 `token` 头、统一响应 `Result`、全部 37 个接口的请求/响应结构、SSE 前端视角（6 个必踩坑 + 状态机）、页面与路由清单 + 三轮迭代范围、**极简黑白色板与组件规范**、联调顺序、上线 Checklist、后端未做清单；`docs/sse-contract.md` §4 修正（401 实际返回 JSON `Result` 而非纯文本 `NOT_LOGIN`，并补 CORS 提醒）；README 文档索引登记；`重构计划.md` P3-1 状态改为进行中 | ⚠️ **用户拍板**：不写前端代码，只出文档。⚠️ **教训（已更正）**：这里原写「全仓库搜不到任何 CORS 配置」是**错的**——只 grep 了 `addCorsMappings|@CrossOrigin|allowedOrigin`，漏掉了走 `CorsFilter` 的 `CorsConfig`。实际**一直有**跨域配置，且是 `allowedOriginPatterns("*")` + `allowCredentials(true)`（对任意网站开放）。排查跨域请直接 grep `cors`（不区分大小写）。该配置已于同日修复为配置驱动 + prod 默认关。⬜ **待用户确认 3 件事**：是否加 CORS 配置类、skills 无列表接口首版是否不做、是否补「会话重命名 / 搜索」接口。纯文档改动，未跑测试 |
| 2026-10-01 | **后端三处修复**：① YAML 重复键阻断启动 ② 跨域对任意网站开放 ③ 登录拦截器不清 ThreadLocal | ① `application.yml` 合并重复的 `spring:` 顶层键（原写法让 Boot 抛 `found duplicate key spring`，**应用起不来**，且 `spring.profiles.active=dev` 与 `spring.threads.virtual.enabled=true` 会被静默丢弃）；② `CorsConfig` 由 `allowedOriginPatterns("*")` + `allowCredentials(true)` 改为**配置驱动**：`nexus.agent.cors.enabled`（prod 默认 false、dev 默认 true）+ `allowed-origins`（dev 默认 `localhost:5173`），放行 `token`/`Content-Type` 头、不开 credentials、为空则不注册任何规则（**不**退化成放行所有）、配 `*` 打 WARN；③ `LoginCheckInterceptor` 补 `afterCompletion` → `UserContextHolder.removeUserId()` | ⚠️ ①②是**安全/可用性**问题：①会让应用完全起不来（P3-3 引入，未重启过所以没暴露）；②等于把接口对任意网站开放。③在开着虚拟线程时不会立刻炸，属**消除隐患**：一旦线程被复用，下一个请求会读到上一个用户的 userId（越权且极难复现）。新增 `ApplicationYmlTest`（4）、`CorsConfigTest`（5）、`LoginCheckInterceptorTest`（3）。测试 **206**（194 通过 + 12 人工跳过），0 失败。§15 补两项 cors 配置；`docs/前端开发指南.md` §0/§1.2/§11/§12 与 `docs/sse-contract.md` §4 一并更正 CORS 描述 |
| 2026-10-01 | **补两个会话接口（P3-1）**：重命名 + 搜索 | `PUT /api/history/{sessionId}/title`（body `{title}`，最长 100）与 `GET /api/history/search?keyword=`；新增 `RenameSessionDTO`、`ChatSessionSearchVO`、`ChatMemorySearchHit`；`ChatHistoryListMapper#updateTitleBySessionAndUserId`（**带 user_id**，顺带刷 update_time）；`ChatMemoryMapper.xml#searchHits`（jsonb 抽正文 + ILIKE）；`ChatHistoryListServiceImpl#rename/search`（合并标题与正文命中、去重、排序）；`AgentProperties` 新增 `history.*` 三个参数 | ⚠️ **三个不这么做就出事的点**：① 改标题 SQL 不带 `user_id` = 越权写别人会话；② 搜索不能直接 `content::text ilike` —— `content` 是 jsonb，会把 `type`/`text`/`USER` 这些**JSON 键名**当成正文，用户搜 `type` 会命中全部会话；③ `escape '\'` 必须与 `toLikePattern` 的转义成对出现，少任何一方，用户搜一个 `%` 就退化成匹配全部。⚠️ **索引**：匹配表达式含 `jsonb_array_elements`（set-returning），PG 要求索引表达式 immutable，**建不了表达式索引** → 目前全表扫描，加速路径记在 `docs/sql/README.md`「尚未处理」（**未新增 008**）。新增 `ChatHistoryListServiceImplTest`（20）。新增 §6.19；§5.2 文件地图、§7 API 一览、§15 配置表同步；`docs/前端开发指南.md` §4/§5.2/§7/§9/§11/§12 同步（原「待拍板」第 2 条关闭） |

**已核实与 `CLAUDE.md` 的冲突（这些是 CLAUDE.md 的错，不是代码的错）**：

| 项目 | `CLAUDE.md` 说法 | 实际 |
|---|---|---|
| 默认 profile | `prod` | **`dev`**（`application.yml`） |
| 接口前缀 | `/chat/stream` | **`/api/chat/stream`** |
| 沙盒实现 | Docker + docker-compose | **E2B 云沙盒**（`e2b-code-interpreter`），docker-compose 只是容器化 FastAPI |
| Mapper 数量 | 12 | **11** |
| 工具注册 | `MemoryTool.ragSearch` 条件注册 | 实际注册的是整个 `MemoryTool` 实例；`RagTool` 是未注册死代码 |
| 长期记忆 | pgvector 向量检索 | **`pg_trgm` 模糊检索**（P2-7，见 §6.15）：`ILIKE '%kw%'` 走 GIN 索引，零命中时 `similarity()` 兜底。向量路径已删除 |
| 默认流式模型 | `deepseek-v4-flash` | 与 yml 一致 ✅ |
| 认证头 | 未提及 | 自定义头 **`token`** |

---

## 12. 已知技术债与陷阱（务必先读）

> 这些是**已从代码中核实**的问题，不是猜测。重构计划按此清单排期。

### 12.0 修复进度（2026-09-23 起）

> 修复后**不要删除条目**，只在此表标注 ✅ 并写明改法与验证方式，保留可追溯性。

| 条目 | 状态 | 改法 |
|---|---|---|
| §12.1-1 硬编码密钥泄漏 | ✅ 已修 | `UserConfigContextHolder` 两份实现均零引用，已整体删除（同时消除 FQCN 冲突）。**⚠️ 泄漏的凭据仍需人工轮换** |
| §12.1-2 同名类 FQCN 冲突 | ✅ 已修 | 同上，同一次删除解决 |
| §12.1-3b 对话读记忆未按 user_id 过滤 | ✅ 已修 | **P1-1 顺带发现并修复**：`PgChatMemoryStore.getMessages` 原来只按 session_id 查，等于「知道别人的 sessionId 就能把别人的聊天记录读进自己的上下文」。已改用 `getByMemoryIdAndUserId`，非本人会话返回空 |
| §12.1-3 越权 IDOR | ✅ 已修 | `McpInformationServiceImpl` 的 `removeMCP/getDetailById/updateMCPById`、`UserMemoryServiceImpl.deleteById`、`ChatMemoryServiceImpl.getHistoryBySessionId` 全部加 `user_id` 条件（新增 `ChatMemoryMapper.getAllByMemoryIdAndUserId`、`McpInformationMapper.updateMCP` 改带 `user_id` 并返回受影响行数） |
| §12.1-4 密钥进 URL | ✅ 已修 | `GET /api/chat/model?baseUrl=&token=` → `POST /api/chat/model` + `ModelListDTO` 请求体。**对外接口破坏性变更** |
| §12.1-5 打印用户密钥 | ✅ 已修 | 删掉 `System.out.println(apiKey)`，改为打印 configId/modelName |
| §12.2-6 `execute_cmd` 路由错误 | ✅ 已修 | 改打 `POST /execute/cmd` + `{cmd, box_id}`，并把误用的 `log.error` 降为 `log.debug` |
| §12.2-7 向量写入错位 | ✅ 已修 | `addAll(content, batch)`（原来是 `textSegments`）。**已有向量数据是错的，需要重建知识库** |
| §12.2-8 `EncryptorFactory` 缺键 | ✅ 已修 | 改为启动期解析 + 明确报错，salt 为空也给出可读原因。**注意：仍是首次调用时才触发**；完整启动校验已由 **P1-10** `StartupConfigValidator` 补上（见 §6.10） |
| §12.2-10 历史读取强转 | ✅ 已修 | `ChatMemoryServiceImpl` 改用 `instanceof TextContent` 模式匹配，并把多段文本拼接而非互相覆盖 |
| §12.4-20 跨线程 ThreadLocal（附件元数据丢失） | ✅ 已修 | **P1-1**：新增 `RunContext`，在请求线程装好跨线程数据后显式传递；`MessageMetadataContext` 已删除。实测 3 轮对话附件元数据完整保留 |
| §12.2-12 MCP client 泄漏 | ✅ 已修 | **P1-6**：新增 `mcp/McpClientRegistry`，按 mcpId 缓存复用、创建失败立即 close、配置变更 evict、`@PreDestroy` 统一关闭。⚠️ 未做运行时验证（库里无 MCP 配置） |
| §12.2-9 Redis `session:` 5 分钟 | ✅ 已修 | **P1-1/P1-2** 引入 `RunContext`，userId 显式传递；`SESSION_KEY` 已删除，Redis 依赖解除 |
| §12.2-11 token 估算写死 gpt-4o | ✅ 已修 | **P1-8**：`nexus.agent.memory.token-estimator-model` 可配（默认仍是 gpt-4o，但换主力模型时可同步改，见 §15） |
| §12.3-16 异常无兜底 | ✅ 已修 | `GlobalExceptionHandler` 增加 `Exception` 兜底：Spring 标准 HTTP 异常保留状态码，其余返回 500 + 通用提示（不再外泄内部信息） |
| §12.3-13 模块版本不统一 | ✅ 已修 | 全部继承父版本 `0.0.1-SNAPSHOT`，内部依赖统一 `${project.version}` |
| §12.3-14 无 Maven Wrapper | ✅ 已修 | 已生成 `mvnw`/`mvnw.cmd`/`.mvn/`，`distributionUrl` 指向阿里云。详见 §2.3 的镜像注意事项 |
| §12.3-15 无数据库迁移 | ✅ 已修 | 新建 `docs/sql/`：约定 + `001_baseline.sql`（**v2.0 重新设计版**，非旧库照抄：12 表补齐主键/4 外键/9 索引、重建设 `sys_file`、`vector(1024)`+HNSW）。已在目标库执行验证 0 错误 |
| §12.5-22 Skill 全链路未接入 | ✅ 已修 | **P2-1**：改为本地目录扫描（`skills/SkillLoader`），`ChatDTO.skills` 已真实生效；旧 DB 注册表方案整体删除。见 §6.9 |
| §12.5-23 `RagTool` 死代码/重复 | ✅ 已修 | 职责拆开：`MemoryTool` 只管长期记忆，RAG 归 `RagTool`（显式声明工具名 `rag_search` 保持契约，并补了异常兜底）；`ChatContextFactory` 中 `ragTool` 字段名不再张冠李戴 |
| §12.5-24 `showHistory.html` | ✅ 保留（更正） | **它不是遗留垃圾**，实为「极简 AI 对话助手」调试页，是当前唯一可用的 UI，不要删 |
| **YAML 重复顶层键 → 应用起不来**（P3-3 引入） | ✅ 已修 | `application.yml` 里写了两次 `spring:`（先 application/profiles/threads，后 lifecycle）。Spring Boot 的 YAML 加载器对重复键**直接抛** `found duplicate key spring` —— 应用根本起不来，且报错只在启动瞬间出现一次、指向 YAML 而非业务代码。已合并成**一个** `spring:` 块，并在文件里写明「不允许出现两个同名顶层键」。新增 `ApplicationYmlTest` 把它挪进 CI |
| — 新增发现：JDBC URL 参数分隔符 | ✅ 已修 | `application-prod.yml` 里 `...=true?stringtype=unspecified` 第二个 `?` 应为 `&`，否则 `reWriteBatchedInserts` 与 `stringtype` 双双失效（后者是 jsonb 写入前提） |

**新增发现（未在最初清单中）**

| 条目 | 状态 | 说明 |
|---|---|---|
| `app/routers/test2.py` 孤儿脚本 | ✅ 已删 | 未被 `main.py` 引用，且**在模块顶层执行 `Sandbox.create()`** —— 一旦被 import 就会创建真实沙盒并计费 |
| `ChatContextFactory` 把记忆工具绑在 `enableRag` 上 | ✅ 已修 | 长期记忆与知识库无关；`MemoryTool` 改为常驻注册，`enableRag` 只控制 `RagTool`。**行为变更**：不勾选知识库时也会多出 2 个记忆工具 |
| **全库无主键/唯一约束/外键/索引** | ✅ 已修 | 库被清空过、灾后 schema 缺约束。旧数据已确认可舍弃，故按代码需求**重新设计**基线：12 表全部补主键、4 个外键、9 个索引、`users.email` 唯一约束。详见 §9 |
| **`sys_file` 表在库中不存在** | ✅ 已修 | 该表曾丢失，导致文件上传必报 `relation does not exist`。依据 `SysFile` 实体 + `FileMapper.xml` 的 resultMap 推导出 DDL 并建表 |
| **`knowledge_base_file` 缺 `file_name` 列** | ✅ 已修 | `insertKnowledge` 会写入它、`resultMap` 也映射它，缺列导致**知识库入库与详情查询双双报错**。基线已含该列 |
| **`KnowledgeBaseFileMapper.xml` 把 `fileName` 映射到 `fail_name`** | ✅ 已修 | 笔误，改为 `file_name` |
| ~~**SSE 事件名大小写不统一**~~ | ✅ 已修（P2-5） | v1 里 `message`/`session_id`/`finish` 是小写字面量，`TOOL_EXECUTION`/`TOOL_EXECUTION_RESULT` 是枚举值（全大写），按 `event: tool_execution` 监听收不到。v2 已统一为小写，并顺带做了 `seq` + `runId` 信封与 `run` 首帧。权威契约见 **`docs/sse-contract.md`**（§6.2 只留索引） |
| ~~**知识库入库强制要求用户自带 embedding 配置**~~ | ✅ 已修（P2-13） | 原 `KnowledgeBaseFileServiceImpl.getEmbeddingModel()` 只从**用户 API 配置**里找 EMBEDDING 模型，找不到就抛异常；而 `RagTool` 检索用的是**系统默认** EmbeddingModel → 没配 API Key 的用户建库必失败，且即便配上也是**跨模型检索**（向量空间不可比、结果不相关且不报错）。现已统一为「入库与检索共用注入的系统模型」，`getEmbeddingModel()` 整体删除（连带删掉里面的 `System.out.println(apiKey)`），`configId`/`model` 从方法签名移除 |
| ~~**RAG 检索无任何用户隔离（跨用户越权）**~~ | ✅ 已修（P2-13） | `RagTool.ragSearch` 构造 `EmbeddingSearchRequest` 时**没有 filter**，等于在整张 `knowledge_embedding` 上做全局检索 → 用户 A 能检索到用户 B 的知识库内容。现已加 `user_id` 过滤（`::text` 比较，兼容历史数据里数字/字符串两种存法）；拿不到用户上下文时**拒绝检索**而不是退化成全表检索。⚠️ 已知取舍：检索范围只限本人，`KnowledgeBase.isPublic` 目前在 RAG 侧不生效（该字段本来也从未被任何查询使用） |
| **`User` 实体缺 `@TableId`** | ✅ 已修 | 补 `@TableId(type = IdType.AUTO)`。原先 `getById`/`updateById` 会失败，且 `save()` 后取不到 id（`register` 要用它签 JWT） |
| **`skill_mcp_information` 表在库中不存在** | ✅ 已废弃（P2-1） | 原按实体补表使其路径不坏；D3 定为本地目录扫描后，实体/Mapper/Service **整体删除**，表由 `docs/sql/002_drop_skill_mcp_information.sql` 删除。Skill 改为 `skills/` 目录 + `SkillLoader`（§6.9） |
| **库中有表但代码无用**：`skill_information`、`user_skill` | ✅ 已删 | Skill 功能的历史设计残留（`开发日志.md` 4.20），代码中已无任何实体或 Mapper 使用 |
| `knowledge_embedding.embedding` 未限定维度 | ✅ 已修 | 原为无维度 `vector`，而 pgvector **无法在无维度列上建索引** → RAG 全表扫描。已改为 `vector(1024)` 并建 HNSW 索引 |
| **`user_memory.source` 是 `char(32)` 装不下 UUID** | ✅ 已修 | `saveLongMemory` 会写入 36 字符的会话 ID，原 `char(32)` 插入即报 `value too long`。已改 `varchar(64)` |
| **`user_config.mcp_token` 是 `char(128)` 偏窄** | ✅ 已修 | `Encryptors.text` 输出长度 = (16字节IV + 明文)×2，token 超 48 字符即溢出。已改 `varchar(512)` |
| `user_config` 字段与实体不一致 | ✅ 已修 | `llm_api_token` 由 `json` 改 `jsonb`；删除实体中不存在的 `user_default` 死列 |
| ~~`UserMemoryMapper.search` 引用了不存在的 `category`/`embedding` 列~~ | ✅ 已消除（P2-7） | 死代码（`searchMemory` 从未被调用），调用即失败。D4 定 pg_trgm 后整条向量路径删除（含 `SearchMemoryRequest` / `MemorySearchResult` / `searchMemory`） |

**实测阶段新发现（2026-09-23 把项目真跑起来后暴露，静态审查无法发现）**

| 条目 | 状态 | 说明 |
|---|---|---|
| `ChatMemoryMapper.xml` 用 `select *` → 按位置错配 resultMap | ✅ 已修 | 对话直接 500，报 `Bad value for type timestamp/date/time`。根因：resultMap 列序为 `create_at` 在前，而重设计后的表物理序为 `session_id` 在前；`select *` 返回物理序，MyBatis 按位置套 resultMap，于是用 Timestamp 处理器读 uuid 列。已改为显式列名并与 resultMap 同序 |
| `LoginCheckInterceptor` 未校验解析结果 → **无效 token 被放行** | ✅ 已修 | 重启后旧 token 失效却不返回 401，而是在业务层报"用户未登录!"；更严重的是不校验 userId 的接口会把它当已登录。根因：`JwtUtil.getIdFromToken` 解析失败时返回 **null 而不抛异常**，拦截器只 catch 异常。已改为 null 即 401 |

**验证方式**：`/d/dev_utils/maven/bin/mvn -DskipTests test-compile` → `BUILD SUCCESS`（5 模块 + 测试源码）。
另有**真实运行验证**：应用启动成功 + 注册登录 + 流式对话 + 会话读写 + 401 鉴权，全部通过（见 §11）。

### 12.1 P0 — 安全类

1. **硬编码密钥泄漏**：`nexus-agent-service/.../context/UserConfigContextHolder.java` 内联了一段
   疑似真实的加密 API Key、salt、MCP Token，且该类是"模拟数据"残留。**必须删除或改为从上下文取值**，
   并**轮换这些凭据**。
2. **同名类双份（FQCN 冲突）**：`com.huzhijian.nexusagentweb.context.UserConfigContextHolder`
   在 `nexus-agent-common` 和 `nexus-agent-service` 中**各有一份**，类路径加载顺序决定用哪个 → 静默不可预测。
3. **越权（IDOR）**：
   - `McpInformationServiceImpl.removeMCP(id)` / `getDetailById(id)` / `updateMCPById` 无 `user_id` 校验
   - `ChatMemoryServiceImpl.getByMemoryId/insertBatch` 按 `sessionId` 查询**不带 `user_id` 过滤** →
     知道 sessionId 即可读他人聊天
   - `UserMemoryServiceImpl.deleteById(id)` 无归属校验
4. **密钥进 URL**：`GET /api/chat/model?baseUrl=&token=` 明文走 query。
5. ~~`KnowledgeBaseFileServiceImpl.getEmbeddingModel()` 里 `System.out.println(apiKey)` 打印用户密钥。~~
   → ✅ 已修（P2-13）：整个 `getEmbeddingModel()` 已删除，改为注入系统 `EmbeddingModel`，
   不再读取用户 API 配置，也就没有密钥可打印。

### 12.2 P0 — 功能性 Bug（影响正确性）

6. **`BoxTool.executeCmd` 路由错误**：打到 `/execute/code` 且字段名用了 `code`，
   应为 `POST /execute/cmd` + `{cmd, box_id}`。→ 命令执行功能实际上是坏的。
7. **向量写入错位**：`KnowledgeBaseFileServiceImpl.embedding()` 中
   `pgVectorEmbeddingStore.addAll(content, textSegments)` 传入了**完整** `textSegments`，
   应为 `batch`。→ embedding 与文本不匹配，RAG 检索结果会串味。
8. `EncryptorFactory.SECRET_KEY = System.getenv("API_KEY_SECRET")`，未设置则为 `null` →
   `Encryptors.text(null, salt)` 抛异常，且全局单密钥 + 每用户 salt 混用，MCP Token 复用 LLM 的 salt。
9. `PgChatMemoryStore.updateMessages()` 里 `Long.valueOf(redisUtils.get("session:"+sessionId))`：
   Redis key 只活 **5 分钟**，过期后 → `NumberFormatException` / NPE，聊天记录写不进去。
10. `ChatMemoryServiceImpl.getHistoryBySessionId()` 对 content 做 `(TextContent)` **强转** →
    非文本内容（多模态历史）会 `ClassCastException`。
11. `ChatContextFactory` 的 token 估算写死 `OpenAiTokenCountEstimator("gpt-4o")`，
    与实际模型无关 → `maxTokens=100000` 的裁剪不准确。
12. MCP `DefaultMcpClient` 每次请求新建、成功后**不 close** → 连接/线程泄漏。

### 12.3 P1 — 一致性与工程化

13. **模块版本号不统一**（`0.0.1-SNAPSHOT` / `0.2.0` / `0.1.0`）。
14. **无 Maven Wrapper**，`mvn` 不在 PATH。
15. **无数据库迁移工具**，schema 靠手工。
16. `GlobalExceptionHandler` 没有兜底 `Exception` handler → 未捕获异常返回 Spring 默认错误页，与 `Result` 信封不一致。
17. 测试全部是"人工集成脚本"，会打真实外部服务，且含硬编码沙盒 ID → 无法 CI。
18. `docker-compose.yml` 的 `build: .` 与 `volumes` 混用（挂载源码 + 镜像依赖），部署语义模糊。
19. `nexus_agent_box/.gitignore` 为空文件；沙盒 `oss_utils.py` 里 `prefix='test/'` 硬编码；
    `README.md` 为空；`app/routers/test2.py` 疑似废弃文件。

### 12.4 P1 — 并发 / 上下文传递

20. ✅ **已修（P1-1）**（原问题，保留追溯）虚拟线程已开启，但 `MessageMetadataContext` / `UserContextHolder` 依赖 `ThreadLocal`：
    - `ChatMessageConverter` 在**请求线程** `set` 附件元数据
    - `PgChatMemoryStore.updateMessages` 在**流式回调线程**读取
    → 附件元数据可能丢失（表现：历史记录里文件信息不见了）。
21. `SseResponseConverter` 用 `AtomicBoolean` 做了幂等保护（这点是对的），但 `onCompletion` 与
    `onCompleteResponse` 双触发路径需要保持这个保护，改动时不要拆掉。

### 12.5 半成品 / 死代码

22. ~~`SkillMcpInformationService` + 实体 + Mapper + 表：全链路无人调用（`ChatDTO.skills` 被忽略）。~~
    ✅ **已消除（P2-1）**：旧方案整体删除，改本地目录扫描并接进对话链路，`ChatDTO.skills` 已真实生效。
23. `RagTool.java`：与 `MemoryTool.ragSearch` 重复，未注册。
24. `nexus-agent-web/src/main/resources/static/showHistory.html`：调试遗留页。
25. `application-prod.yml` 里 `enable_thinking: true` 全局开启思考，会显著增加延迟与成本。

---

## 13. 给下一个 Agent 的开工流程（建议照抄）

```bash
# 1. 确认分支（必须是 ds）
git rev-parse --abbrev-ref HEAD     # 期望: ds
git log --oneline -3

# 2. 确认能编译（全路径 mvn；首次或有依赖变更时不要加 -o）
/d/dev_utils/maven/bin/mvn -DskipTests compile

# 3. 确认本地 profile 存在
ls nexus-agent-web/src/main/resources/application-dev.yml   # 不存在就先建

# 4. 选一个 §12 的条目 → 在 重构计划.md 找到对应阶段 → 开干
```

**改完任何东西之后**：
- 更新本文件 §11 变更记录（结构性变更）
- 如果是 bug 修复，把 §12 对应条目标注 ✅ 已修复（不要直接删，保留可追溯性）
- 如果新增/修改了接口、表、工具、事件 → 同步更新 §6/§7/§9

---

## 14. 决策记录（已定 / 待定）

### 14.1 已拍板（2026-09-23）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| D1 | 沙盒方案 | ✅ **保持 E2B 云沙盒** | 改动最小。**代价：必须补 P1-7 `SandboxSession` 做自动回收**，否则沙盒泄漏会持续产生费用 |
| D3 | Skill 落地方案 | ✅ **本地目录扫描**（服务端内置 skill 目录，扫描 `SKILL.md` 注册） | 避开 B/S 下上传 zip 的解压落盘与路径穿越安全问题 |
| D5 | 文件空间产品形态 | ✅ **(c) 虚拟工作区**（2026-09-24 定） | 协议优先：「文件引用 + 产物回传」这套协议是四条路**共用**的，且代价最低（复用 OSS + `sys_file` + 沙盒）。**边界：只做"AI 产出文件 → 用户拿走"，不含"AI 直接改本机文件"**（后者需另走 (b)/(d)，见 §16.4） |
| D2 | 前端是否要做？ | ✅ **做**（Vue3 + Element Plus，与 `kimi_demo` 技术栈对齐），**但严格排在后端全部完成之后**（2026-09-30 定） | 用户拍板："把后端做完再做前端"。因此 `P2-5`（SSE 契约）**不再被 D2 阻塞** —— 前端由我们自己写，契约可以现在一次定死，不必迁就任何既有前端 |
| D4 | 长期记忆检索方式 | ✅ **pg_trgm**（2026-09-30 定，P2-7 已落地） | 零 API 成本解掉 LIKE 的硬伤；**不加 `embedding` 列**。边界：只解决字面部分重叠（"喜欢看科幻电影"↔"喜欢看科幻片"），**语义相似（"喜欢吃什么"↔"不吃辣"）仍需 pgvector**，留到记忆量上来之后再做 |

> ✅ **已兑现（P2-7，2026-09-30）**：`docs/sql/006_add_user_memory_trgm_index.sql`
> （`pg_trgm` 扩展 + `content` 的 GIN `gin_trgm_ops` 索引）+ `UserMemoryServiceImpl` 重写，
> 坏死的向量路径已删除。实现要点与降级设计见 **§6.15**。

> ✅ **已兑现（P2-1，2026-09-23）**：`ChatDTO.skills` 已真正接进 `ChatContextFactory`
> （Skill 与 MCP 合并为 `toolProviders` 注册，技能清单注入系统提示词 `{{runtimeCapabilities}}`）。
> 旧 DB 注册表方案与 `SkillMcpInformation*` 已一并删除，未留装饰。落地方案与实现要点见 **§6.9**。

### 14.2 仍待定

> 2026-09-30：**D4 已定（pg_trgm）并落地 P2-7；D2 也已拍板 = 做前端（排在后端之后）。**
> **至此 D1–D5 全部已定，本表不再有条目。**
> `P2-11` 在 D5=(c) 下不需要做。
> 后端剩余任务（P2-5 / P2-8 遗留 / P3-3 / P3-4）按 P3-1 之前的顺序做完，再做前端。
> 各决策的选项对比与代价仍保留在 `重构计划.md §七`，本表只留索引，避免两处维护。

> ❗**D1 的后果要在 D5 里一次性想清楚**：E2B 是云端沙盒，**AI 读不到你本机磁盘**。
> 若核心诉求是"让 AI 直接改我本机项目文件"，答案不是优化 E2B，而是 (b) 或 (d)。

---

## 15. 运行时配置（`nexus.agent.*`）

对应类 `properties/AgentProperties`。**所有字段都有代码默认值**，因此 yml 里不写也能启动
（本地 `application-dev.yml` 是 gitignore 的，不能指望它一定包含这些键）。

| 配置 | 默认 | 说明 |
|---|---|---|
| `nexus.agent.sse.timeout` | `120s` | SSE 连接超时。**必须大于最慢一次模型调用**，否则复杂任务被掐断 |
| `nexus.agent.sse.flush-max-chars` | `200` | 流式增量合并：攒够这么多字符就推一帧（P2-12，见 §6.14）。调大→帧更少更省但到达略慢 |
| `nexus.agent.sse.flush-interval` | `60ms` | 流式增量合并的兜底时间阈值（≈16 帧/秒，与屏幕刷新率相当） |
| `nexus.agent.memory.max-tokens` | `100000` | 对话记忆窗口。只影响送给模型的上下文，**不影响已入库的消息** |
| `nexus.agent.memory.token-estimator-model` | `gpt-4o` | token 估算器用的模型名。只做本地估算、不产生 API 调用；与实际模型不一致会导致窗口裁剪不准 |
| `nexus.agent.memory.max-results` | `20` | 长期记忆单次检索最多返回多少条（P2-7，见 §6.15）。这些条目会进提示词，太多既费 token 又稀释重点 |
| `nexus.agent.memory.max-keywords` | `6` | 一次检索最多拆几个关键词。模型可能丢整句话进来，拆太多会让 OR 条件膨胀 |
| `nexus.agent.memory.fuzzy` | `true` | 字面匹配零命中时是否用 pg_trgm `similarity()` 兜底。**需要 `006` 建的扩展**；没装会自动降级（只 WARN 一次） |
| `nexus.agent.memory.fuzzy-min-score` | `0.15` | 兜底相似度阈值。中文短句三元文法重叠率天然偏低，故低于 PG 默认的 0.3；结果太杂就调高 |
| `nexus.agent.memory.max-content-length` | `500` | 单条记忆最大字符数，写入时截断 |
| `nexus.agent.memory.dedup-threshold` | `0.85` | 写入去重阈值：与已有记忆相似度达到该值即丢弃（防模型反复保存同一条偏好） |
| `nexus.agent.sandbox.reuse-per-session` | `true` | 同一会话复用同一沙盒（E2B 按量计费，关闭会导致反复创建） |
| `nexus.agent.sandbox.idle-timeout` | `8m` | 空闲多久后主动销毁沙盒。**必须小于沙盒服务的 `set_timeout(600)`** |
| `nexus.agent.sandbox.sweep-interval` | `60000` | 回收任务间隔（毫秒或 ISO-8601） |
| `nexus.agent.mcp.health-timeout` | `5s` | MCP 客户端健康检查超时 |
| `nexus.agent.mcp.cache-clients` | `true` | 是否缓存复用 MCP 客户端。关闭会导致每轮对话新建连接（泄漏） |
| `nexus.agent.skill.enabled` | `true` | 是否启用 Skill 能力。关闭后忽略请求里的 `skills` 参数 |
| `nexus.agent.skill.root-dir` | `skills` | Skill 根目录（相对应用工作目录，支持 `~`）。目录约定见 §6.9 与 `skills/README.md` |
| `nexus.agent.skill.refresh-interval` | `60s` | 目录扫描缓存时长。新增技能最多延迟这段时间生效，**无需重启**；设 `0s` 每次请求重扫 |
| `nexus.agent.security.enabled` | `true` | **是否启用登录鉴权**（拦截器 + 配置类共用此开关）。关闭时启动打 WARN、所有接口免 token。**只能本地开发用，生产必须 true** |
| `nexus.agent.startup.fail-fast` | `true` | 启动配置自检发现必需配置缺失时是否阻止启动。`false` 只建议临时排查用（见 §6.10） |
| `nexus.agent.tools.http-timeout` | `100s` | 工具 HTTP 调用的响应超时。**刻意小于 `sse.timeout`**，以便先返回结构化 `TIMEOUT` 而不是掐断整条流（见 §6.11） |
| `nexus.agent.tools.duplicate-window` | `60s` | 重复调用判定窗口（见 §6.11） |
| `nexus.agent.tools.duplicate-threshold` | `2` | 窗口内允许的相同调用次数，超过即拦截并回灌提示；设 `0` 关闭治理 |
| `nexus.agent.cors.enabled` | `false`（prod）/ `true`（dev） | **是否开启跨域**（`CorsConfig`，走 `CorsFilter` 故预检不会撞登录拦截器）。prod 默认关；开启时必须同时给 `allowed-origins` |
| `nexus.agent.cors.allowed-origins` | 空 | 允许的**前端来源**，逗号分隔（支持 `http://localhost:*`）。⚠️ 空 = 不注册任何规则（等同关闭，**不会**退化成放行所有）；填 `*` 会打 WARN |
| `nexus.agent.observability.enabled` | `true` | 是否输出每次 Run 的汇总日志（见 §6.12） |
| `nexus.agent.observability.model-prices.<模型名或前缀>.input/.output` | 无 | 每 100 万 token 的单价（元）。**故意无默认值**：价格会变，写死必然过时；未配置的模型显示 `fee=unpriced` |
| `nexus.agent.model.providers.<baseUrl 片段>.thinking/.search` | 内置 2 条 | 服务商能力表（见 §6.3）。未命中者一律不下发额外参数；同名项覆盖内置 |
| `nexus.agent.quota.enabled` | `true` | 是否启用 token 配额校验（见 §6.13）。关闭后不再拦截，但**用量仍会累加** |
| `nexus.agent.quota.default-quota` | `0` | 新注册用户的默认 token 配额；`<=0` 表示不限制（存量用户不受影响，见 §6.13） |
| `nexus.agent.quota.period` | `NONE` | 配额重置周期：`NONE`（累计，默认）/ `DAILY` / `MONTHLY`。单个用户可在库里用 `users.token_period` 覆盖。**需先执行 `docs/sql/007`**（见 §6.13） |
| `nexus.agent.history.search-max-rows` | `300` | 会话搜索最多扫多少条**命中消息**（P3-1，见 §6.19）。单位是消息不是会话，防止热门关键词扫出几十万行
| `nexus.agent.history.search-max-sessions` | `30` | 搜索最终最多返回多少个**会话**
| `nexus.agent.history.snippet-radius` | `40` | 命中片段在关键词前后各保留的字符数
| `springdoc.api-docs.enabled` | `true`（prod `false`） | 是否暴露 `/v3/api-docs`（P3-4）。⚠️ 文档路径**免鉴权**，关掉它才是关掉暴露（见 §6.17） |
| `springdoc.swagger-ui.enabled` | `true`（prod `false`） | 是否启用 `/swagger-ui.html` |

> ⚠️ **不要把 `memory.max-tokens` 设得比系统提示词还小**（提示词约 200 token）。
> `TokenWindowChatMemory` 会**永远保留系统消息**，窗口过小时它会挤掉全部对话消息，
> 表现为模型「失忆」并只回一句寒暄。实测 60 会出问题，≥600 正常。

> ⚠️ **鉴权的开关方式（P1 附带改造）**：`WebInterceptorConfig` 与 `LoginCheckInterceptor`
> 都加了 `@ConditionalOnProperty(nexus.agent.security.enabled, matchIfMissing = true)` ——
> 默认开启。要本地**关掉鉴权**请在**自己不提交的** `application-dev.yml` 里显式设 `false`，
> **不要注释掉 `@Configuration`**（那样没有任何痕迹、容易误提交、也看不出当前状态）。
> `SecurityModeReporter` 会在启动时把实际状态打进日志（关闭时打 WARN 横幅）。

---

## 16. 文件与产物能力（设计说明）

> 回答"能不能像桌面版 AI 工具那样，把文件产物交给用户 / 访问并编辑本地文件空间"。
> 对应计划：`重构计划.md` P2-10 / P2-11，决策 D5。

### 16.1 结论先行

| 能力 | 可行性 | 现状基础 | 结论 |
|---|---|---|---|
| **A. 产物交付**（AI 生成文件 → 交给用户下载） | ✅ 高 | 已有约 80% 链路 | 做，属 P2，**投入中等** |
| **B. 访问/编辑本地文件空间** | ⚠️ 取决于产品形态 | 无 | 需先定形态（决策 D5），四条路各有代价 |

### 16.2 能力 A：产物交付（已有基础，缺口明确）

**现成链路**（这条已经通了）：

```
AI 在 E2B 沙盒里写文件
  → BoxTool.download(file_path, box_id)
    → Java 调沙盒 GET /file
      → Python: sbx.files.read() → str_upload_file() → 阿里云 OSS
    → 返回 {"url": "https://nexus-agent-file.oss-.../xxx"}
```

**缺的四个环节**：

| 缺口 | 现状 | 要做的事 |
|---|---|---|
| ① 没有"产物"这个一等概念 | 只返回一个裸 URL，前端无法区分"文件产物"和"普通工具输出" | 新增 `ARTIFACT` SSE 事件（含 `artifactId/name/url/size/mime`），前端渲染成下载卡片 |
| ② 产物没有落库 | OSS 上传后不留记录 | 写 `sys_file`（`biz_type=ARTIFACT` + `session_id`），便于会话内追溯与清理 |
| ③ OSS 路径硬编码 | `nexus_agent_box/app/utils/oss_utils.py` 里 `prefix='test/'` **写死** | 改为 `user/{userId}/artifact/{date}/`，与 Java 侧 `user/file` 目录规范对齐 |
| ④ 沙盒里缺生成 Office 的库 | E2B 基础镜像不含 `python-docx`/`openpyxl`/`python-pptx` | 两个选择：让 AI 每次 `pip install`（慢且不稳定），或**构建自定义 E2B 模板预装**（推荐） |

**要点**：产物交付本质上是"**给 AI 一个 `publish_artifact` 工具 + 给前端一个 `artifact` 事件**"，不需要动 Agent 核心。这是投入产出比最高的一条能力线。

**实施结果（2026-09-24，P2-10：①②③⑤ 已完成，④ 需你在 E2B 侧执行）**：

| 缺口 | 状态 | 实现 |
|---|---|---|
| ① 产物一等概念 | ✅ | 新增工具 `publish_artifact`（`BoxTool`）→ 结果带 `artifact{name,url,size,extension}`；`ChatServiceImpl.onToolExecuted` 检测到后落库并推 **SSE `artifact` 事件**；新增 `MessageType.ARTIFACT` 与 `MessageVO.artifact` |
| ② 产物落库 | ✅ | 新增 `ArtifactService` → 写 `sys_file`（`biz_type=ARTIFACT` + `session_id`）；`004` 加 `session_id` 列 |
| ③ OSS 路径规范 | ✅ | `oss_utils.object_prefix()` → `user/{userId}/artifact/{date}/`（原来是写死的 `test/`）；顺带修掉**中文文件名未做 URL 编码**的隐患（`quote(safe='/')`）；Java 侧 `SandboxClient.downloadFile` 带上 `user_id` |
| ④ E2B 预装 Office 库 | ⬜ **需你在 E2B 侧执行** | 见下 |
| ⑤ 会话级文件列表 / 删除（虚拟工作区） | ✅ | `ArtifactController`：`GET /api/artifact?sessionId=`、`DELETE /api/artifact/{id}`；删除顺序是**先删记录、再尽力删 OSS 对象**；`AliOssUtil.objectNameOf` 从 URL 反解对象名（须处理 percent-encoding）；`005` 补 `(session_id, user_id)` 索引 |

**为什么"落库 + 发事件"不放在工具类里**：工具只有 `@ToolMemoryId`（会话 ID），
而落库要 userId、推事件要 SSE writer —— 只有 `ChatServiceImpl.onToolExecuted` 同时握有这三样。
所以职责切成「工具负责产出、主流程负责交付」，避免为此把 RunContext 硬塞进工具层。

**④ 怎么做（需要你操作，AI 代劳不了）**：E2B 基础镜像**不含** `python-docx`/`openpyxl`/`python-pptx`。

- 临时方案：让 AI 每次在沙盒里 `pip install python-docx -q` —— 慢、依赖网络、可能失败
- **推荐**：在 E2B 控制台基于基础模板构建**自定义模板**（Dockerfile 里预装这三个库），
  并在创建沙盒时指定该 template。这样每次建沙盒就自带，AI 不必现场安装

### 16.3 能力 B：本地文件空间（四条路，必须先选形态）

**根本约束**：浏览器**不能**直接读写本地文件系统。桌面版 AI 工具能做，是因为它是原生/Electron 客户端。
本项目是 B/S 架构，所以只能走下面四条路之一：

| 方案 | 体验 | 代价 | 适用 |
|---|---|---|---|
| **(a) File System Access API**<br>`showDirectoryPicker()` | 接近桌面端，可真读写本地目录 | **仅 Chromium**（Chrome/Edge），Firefox/Safari 不支持；需 https/localhost；用户要理解授权弹窗；文件内容需经前端中转给后端 | 用户固定用 Chrome/Edge，且愿接受授权流程 |
| **(b) 本地守护进程 / 桌面客户端** | 最接近桌面版：能读写文件、跑命令、做 git | 要开发 + 分发 + 签名安装包，**是一条独立产品线** | 打算做桌面客户端时 |
| **(c) 虚拟工作区（服务端目录 + OSS）** | 跨平台无摩擦，但**不是真本地文件**，需手动导入导出 | 最低：复用现有 OSS + `sys_file` + 沙盒 | ⭐ **推荐先做这个** |
| **(d) 服务端挂载本机目录** | AI 直接读写你指定的本机目录，最爽 | 仅自托管可行；需要容器隔离与权限控制；**与决策 D1 冲突**（见下） | 自托管/单机部署 |

### 16.4 ⚠️ D1 选择 E2B 的一个重要后果

决策 D1 已经定为**保持 E2B 云沙盒**。需要明确它的含义：

> E2B 沙盒是**云端工作区**，不是你的本机磁盘。
> 因此 **AI 无法直接读写你电脑上的文件**——本地文件必须通过"上传 → 处理 → 下载产物"流转。

如果将来的核心诉求变成"**让 AI 直接改我本机项目里的文件**"，那真正需要的是
**方案 (b) 本地守护进程** 或 **(d) 自建 Docker 沙盒 + 挂载卷**，
而不是继续在 E2B 上做优化。这一点建议在 D5 里一次性想清楚，避免 P2 做完才发现方向不对。

### 16.5 推荐路径

1. **先做 (c) 虚拟工作区**（P2-10）。理由：它顺带把"会话中的文件引用 + 产物回传"这套**协议**定义出来，
   而这套协议是四条路**共用的**。先把协议立起来，再决定文件从哪来/去哪。
2. 叠加能力 A 的产物交付（同属 P2-10，天然一体）。
3. 之后若确实需要本地目录，再把 (a) File System Access API 作为**内容来源/去处**接上——
   因为协议已经定好，(a) 只是换一个数据出入口。
4. (b) 仅在决定做桌面客户端时启动。
