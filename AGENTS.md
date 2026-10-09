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
4. **不要把密钥写进代码。** 一律走外部配置。当前代码里已经存在一处硬编码密钥泄漏，见 [§12.1](#121-p0--安全类)。
   <br>🔴 **配套细则（2026-10-09 补，已踩两次）**：密钥的**读取方式**必须走 Spring，
   形式固定为 `@Value("${nexus.agent.<域>.<字段>:${同名环境变量:}}")` ——
   **配置项优先、同名环境变量兜底**（环境变量本身就是 Spring 的一个属性源，不必再调 `System.getenv`）。
   参考实现见 `RuntimeSecretInitializer`。
   <br>**禁止用 `System.getenv(...)` 直接读密钥**：那条路径**绕开 Spring**，
   于是写在外部 yml（`conf/nexus-override.yml`）、面板生成的 `.env.properties` 里的值
   **一律读不到**，用户看到的现象是「我明明写到配置文件里了，日志却说没配置」。
   `JWT_SECRET` / `API_KEY_SECRET` 在 2026-10-03 因此修过一次，
   `web_search` / `web_extract` 在 2026-10-07 又把老坑踩了一遍（2026-10-09 发现）。
   <br>占位符**必须带默认值**（末尾那个 `:`），否则缺配置时 Spring 抛
   `PlaceholderResolutionException`，把「一个可选功能没配」升级成「整个应用起不来」。
   护栏：`ValueInjectionGuardTest`（禁 `@Value` 落在 final 字段、禁占位符无默认值）、
   `ExternalConfigOverrideTest`（钉住「写外部 yml 必须能注入」+「三个消费方必须引用同一个表达式常量」）。
5. **改完一个模块要 `mvn -pl <module> -am compile` 验证依赖链**，不要只看 IDEA 不报红。
6. **数据库 schema 变更必须同时提供 SQL**（目前无迁移工具，见 [§9](#9-数据库)）。
7. 新增工具（Tool）时，**必须同时考虑：注册位置、失败降级、超时、是否需要沙盒生命周期**。见 [§6.4](#64-如何新增一个-tool)。
8. **注释用中文**，与现有代码风格保持一致。
9. **🔴 只要改动会碰到「前端看得见的东西」，必须在同一次改动里更新前端文档。**
   前端是用户自己写的，唯一依据就是文档 —— 后端悄悄加/改一个字段，
   前端不会知道，表现就是"功能明明做了但页面没反应"。
   触发条件（**任一命中即必须更新**）：
   - 新增/删除/重命名**接口**，或改接口的**路径、方法、参数**；
   - 在响应体（含 VO / 实体直接序列化）里**加字段、删字段、改字段名、改字段语义**；
   - 改 SSE 事件名、信封结构、或某个事件的载荷字段（权威文档 `docs/sse-contract.md`）；
   - 改错误码 / 错误消息文案 / HTTP 状态码映射（`GlobalExceptionHandler`）；
   - 改 Nginx 相关的容器配置（上传大小、超时、缓冲）—— 同步前端文档里的 Nginx 片段。
   做法：✅ **写进 `docs/前端增量变更.md`**（只写增量，见 [§8.1](#81-前端文档同步规则)）；
   ❌ **不要**去改 `docs/前端开发指南.md` —— 那是「从零搭前端」的冻结教程，前端早已搭好，
   改它只会让用户看不出这次到底动了什么（2026-10-05 踩过：写成教程式的长段落被明确退回）。
   并在 [§11 变更记录](#11-变更记录本文件维护的更新日志) 里写一行「前端需要同步：……」。
   **不写就等于没改完。** 详见 [§8.1](#81-前端文档同步规则)。

10. **🔴 `AgentProperties` 里的键，禁止在任何 profile yml 里重复声明。**
    profile 属性源的优先级**高于**代码默认值 —— 在 `application-prod.yml` 里写一行，
    就等于把 `AgentProperties` 的默认值静默废掉，而代码那边看起来完全没变。
    这个坑在本项目**已经炸过两次，且两次都逃过了当时所有的护栏测试**：
    - 2026-10-05：`prod` 残留 `sse.timeout: 120s` → 任务跑满 2 分钟必断；
    - 2026-10-06：`prod` 残留 `memory.max-tokens: 24000` → 1M 上下文的模型实际只剩 2.4 万 token 窗口。

    **唯一事实源是 `AgentProperties` 的字段默认值**，要改就改那里（全项目只此一处）。
    现有护栏：`SseTimeoutDriftTest`（盯 `sse.timeout`）、`MemoryWindowDriftTest`（盯 `memory.max-tokens`）。
    <br>⚠️ **新增一个 `AgentProperties` 字段时，顺手给它配一条同款护栏** ——
    手工比对过（2026-10-06 全仓扫描）：目前 prod 里其余声明的值都与默认值一致，
    属于冗余无害；但它们全都是「下次改默认值时的定时炸弹」。
    <br>⚠️ **写这类护栏时最容易犯的错：用 `new AgentProperties()` 的默认值去算生效值。**
    那正是 2026-10-06 漏网的原因 —— 读配置文件的测试必须读**那份文件里写了什么**。

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
| 知识库检索（**仅第三方：腾讯乐享**） | ✅ 可用（用户自带 AppKey/AppSecret，BYOK） | `LexiangRagTool` + `lexiang/`（见 §6.17） |
| ~~RAG 知识库（本地 pgvector）~~ | ❌ **已下线 2026-10-04** | 代码已删、表用 `docs/sql/009` 删除。用户嫌难维护；**向量模型不再需要**。⚠️ 但 `ALI_AI_KEY` **仍然必需** —— 它现在只服务 `nexus.agent.system-models` 里的百炼（qwen）供应商，见 §4.1 |
| 长期记忆 | ✅ 可用（`pg_trgm` 模糊检索 + 字面匹配兜底，P2-7） | `UserMemoryServiceImpl` + `utils/MemoryQueryParser` / `MemoryTool`（见 §6.15） |
| Skill 系统（`langchain4j-skills`） | ✅ 可用（官方=本地目录，用户=DB，`ChatDTO.skills` 生效） | `skills/SkillLoader` + `OfficialSkillSource` + `UserSkillServiceImpl` |
| 技能库（上传 / AI 生成 / 社区共享） | ✅ 可用（2026-10-04 新增，解压**不落盘**） | `SkillPackageParser` + `SkillController` + `user_skill` 表 |
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

**Spring Boot starters**：`web`、`webflux`（沙盒 HTTP 调用）、`mail`、`data-redis`、`validation`、`actuator`、`test`、`spring-security-crypto`（加密）。❗**已移除 `websocket`**（2026-10-04，标题推送下线，见 §6.6）

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

---

### 3.1 🔴 测试分层：别再只堆 mock 单测（2026-10-04 血泪教训）

**背景**：仓库曾有 374 个纯 mock 单测全绿，但上线后连着炸了三个问题
（上传 502、SSE 错误刷屏、百炼 401）。原因高度一致 —— **这三类都发生在
mock 单测看不见的层**：Servlet 容器行为、Spring 配置绑定、文档与配置漂移。

**今后写测试，先问自己"这个 bug 会出现在哪一层"，再选对应的手段：**

| 层 | 能抓到的问题 | 手段 | 现成范例 |
|---|---|---|---|
| L0 纯单测 | 业务分支、边界值 | JUnit5 + Mockito | 绝大多数既有测试 |
| L1 **失败路径契约** | 异常分支返回 null / 空串 / 拼出 "null" | 打桩让依赖抛异常，断言返回值 | `ToolFailureContractTest`、`FileServiceUploadFailureTest` |
| L2 **容器 / Servlet** | Content-Type 与 `HttpMessageConverter` 打架、error dispatch | `MockHttpServletRequest` / `MockHttpServletResponse`（保留真实 Content-Type 语义） | `SseErrorFrameTest` |
| L2' **序列化 / 跨语言精度** | 数值在JSON → JS → 再回传的链路上被改写（**不报错，只变错**） | 真的走一遍目标语言的数值语义，并加**元测试**证明模拟本身有效 | `SnowflakeIdPrecisionTest` |
| L3 **配置真实绑定** | yml 改了但没被 Spring 读到、类型/单位写错 | `ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`（**真读 yml 的迷你容器**，秒级） | `RuntimeConfigBindingTest` |
| L4 **静态资产一致性** | 文档漂移：yml 要 `${XXX}` 但 `.env.example` 里没有 | 把配置文件**当输入解析并断言** | `EnvPlaceholderDriftTest` |
| L5 人工集成 | 真实外部服务 | `@Disabled` + `@Tag("manual")` | `BoxToolTest` 等 |

**配套铁律（自检时新增，全仓适用）：**
0. 🔴 **模拟外部语言语义的测试，必须配一条「元测试」证明模拟本身有效。**
   2026-10-05 血泪：测「雪花 ID 在 JS 里丢精度」时，我用 `BigDecimal.longValueExact()`
   去模拟 `JSON.parse` —— 那是**精确算术**，裸数字走它也「无损」，
   **测试全绿但什么都没测到**。后来实测算 4000 个 id 99.6% 会丢，才发现问题。
   → 元测试写法：拿一个**已知会出错**的输入，断言模拟结果**确实出错**。
   另：断言前先核对"我构造的输入真的是我以为的那个形态"（我曾断言
   `{"id":123}` 不含引号 —— `key` 本身就带引号，断言自己写错了）。
1. **任何 `catch` 都必须留日志**（`log.warn`/`log.error`），只有"这条异常本来就
   在正常分支里"才允许降到 `debug`，且要在注释里写明理由。静默吞异常 = 线上无头案子。
2. **工具（`@Tool`）的返回值绝不能是 null / 空串 / 含 "null" 字样** —— 它会原样
   进模型上下文，模型只会瞎编或反复重试。见 §6.5。
3. 新增外部依赖的配置项时，**同步改 `.env.example`**，`EnvPlaceholderDriftTest` 会兜底。
4. 改容器相关配置（multipart / tomcat / CORS）时，**同步写 `docs/前端增量变更.md`**，
   并注明 Nginx 侧要跟着改什么（`client_max_body_size` / 超时 / 缓冲）—— 网关层拦掉的话后端日志是空的。
5. **🔴 单测里不许用毫秒级 `sleep` / 毫秒级阈值来断言时序。** 这类用例在 GC 停顿或系统调度抖动下会随机失败，
   一旦偶发红了，大家就习惯性"重跑一次"，测试的可信度归零（比没有测试更糟）。
   判据：**阈值与 sleep 之间至少要留一个数量级的安全边界**（例：阈值 100ms + sleep 150ms，而不是 1ms + 5ms）。
   更优先的做法是把"时间"抽成可注入的时钟，或用 `Awaitility` 轮询等待条件成立。
   实例：`SseChunkBufferTest.flushesOnInterval` 原为 `interval=1` + `sleep(5)`，已修。

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
| `ALI_AI_KEY`（旧名 `AI_KEY`） | 阿里云百炼（DashScope）Key —— **系统模型列表里 qwen 系列用**（不是向量模型，向量模型已随 pgvector 下线） |
| `DATABASE` / `REDIS_PWD` | PostgreSQL / Redis 密码（2026-10-01 从写死改为占位符） | `application-prod.yml` |
| `SERVICE_IP` | PostgreSQL / Redis 主机（**注意：不是 `DOCKER_IP`**，历史上文档与 prod.yml 里写错过，以 `application-dev.yml` 为准） | `application-prod.yml` |
| `DB_USERNAME` | PostgreSQL **用户名**（2026-10-03 前写死 `postgres`，用户名不是 postgres 的环境怎么改都连不上）。不填默认 `postgres` | `application-prod.yml` |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | QQ SMTP 验证码 | `application-prod.yml` |
| `nexus.agent.jwt-secret`（或 `JWT_SECRET`） | JWT 签名密钥。**任意字符串都可以**（2026-10-03 起不再强制 Base64）。不设置会随机生成 → 重启后所有 token 失效 | `JwtUtil` |
| `nexus.agent.api-key-secret`（或 `API_KEY_SECRET`） | 用户 API Key 加密主密钥。缺失时不再启动期炸，改成第一次加解密时才抛 | `EncryptorFactory` |
| `nexus.agent.sandbox.base-url`（或 `BASE_URL`） | 沙盒服务地址，默认 `http://localhost:8000`。容器里 `localhost` 是容器自己，连宿主机要写 `host.docker.internal` | `WebClientConfig` |
| `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` | **Java 侧**阿里云 OSS（头像 / 附件上传、产物删除）。**不设置启动不报错，一上传就失败** | `AliOssUtil` |
| `spring.aliyun.access-key-id` / `access-key-secret` | 同上，**配置文件写法**（2026-10-02 支持） | `AliOssProperties` |
| `E2B_API_KEY` | E2B 云沙盒鉴权 | `nexus_agent_box`（`.env`） |
| `ALIBABA_CLOUD_ACCESS_KEY_ID` / `..._SECRET` | **沙盒侧**回传 OSS 用 | `app/utils/oss_utils.py` |

❗**两套 OSS 变量名不一样，别填反**：两侧都叫 `EnvironmentVariableCredentialsProvider`，
但是**两个不同的 SDK** —— Java（aliyun-sdk-oss）只读 `OSS_*`，Python（oss2）只读 `ALIBABA_CLOUD_*`。

❗**prod yml 里所有 `${XXX}` 都是"缺了起不来"，不是建议项**：它们**没有默认值**，
Spring 在建 Bean 时解析不到会抛 `Could not resolve placeholder 'XXX'` → **启动失败**。
（写成 `${XXX:默认值}` 的才是有兜底的。改变量名时两边要同步，
`scripts/check-env.sh` 直接从 yml 抽占位符，不会漂移。）

完整清单与填法见 [`.env.example`](./.env.example)，另可用 `scripts/check-env.sh` 自查
缺项 / 占位符 / 写法（密钥**打码输出**，退出码 0/1）。
⚠️ **Spring Boot 不会自动读 `.env`**：走 compose 由 `env_file` 注入没问题，
直接 `java -jar` 前必须 `set -a; . ./.env; set +a`，否则所有变量为空、启动必失败。

❗**凡是"配置文件里写了却读不到"的，八成是代码直接调了 `System.getenv()`** ——
这条路**绕开 Spring**，yml / `.env.properties` / 面板配置里写的一律看不见。
2026-10-03 已把这几处统一改成「Spring 配置项优先 + 同名环境变量兜底」：
`JwtUtil`、`EncryptorFactory`、`WebClientConfig`、`AliOssUtil`。
新增代码**禁止**再写 `System.getenv`（除非是启动前必须读的，那种要在注释里写清理由），
一律用 `@Value("${nexus.agent.xxx:${ENV_VAR:默认值}}")` 这种写法 —— 配置项和环境变量都能覆盖。

---

## 5. 模块结构

```
nexus-agent (parent, packaging=pom, v0.0.1-SNAPSHOT)
├── nexus-agent-common   v0.0.1-SNAPSHOT  JWT、枚举、异常、ThreadLocal 上下文、常量
├── nexus-agent-domain   v0.0.1-SNAPSHOT  Entity / DTO / VO / Result 信封
├── nexus-agent-mapper   v0.0.1-SNAPSHOT  10 个 Mapper 接口 + 10 个 XML（2026-10-02 实数）
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
| 记忆 / 知识库工具 | `tools/MemoryTool.java`（长期记忆，常驻） + `tools/LexiangRagTool.java`（乐享知识库检索，`enableLexiangRag=true` 时启用；见 §6.17） |
| 沙盒服务端 | `nexus_agent_box/app/routers/{box,file,execute,mcp}.py` |
| 聊天历史落库 | `nexus-agent-service/.../config/PgChatMemoryStore.java` |
| 鉴权 | `nexus-agent-service/.../interceptor/LoginCheckInterceptor.java` |
| MCP | `nexus-agent-service/.../service/impl/McpInformationServiceImpl.java` |
| ~~知识库入库（切分 + 向量化）~~ | ❌ 已随本地知识库下线（2026-10-04） |
| 会话列表 / 重命名 / 搜索 | `nexus-agent-service/.../service/impl/ChatHistoryListServiceImpl.java`（合并与排序）+ `nexus-agent-mapper/.../ChatHistoryListMapper.java`（改标题）+ `ChatMemoryMapper.xml#searchHits`（按正文搜）。见 §6.19 |
| ~~向量库 Bean~~ | ❌ 已随本地知识库下线（2026-10-04）。`langchain4j-pgvector` 依赖已移除。⚠️ `ALI_AI_KEY` **没有一起下线** —— 百炼（qwen）模型还在用它，见 §4.1 |
| 环境变量清单 / 部署前自查 | `.env.example`（清单 + 填法）+ `scripts/check-env.sh`（查缺项 / 占位符 / 写法，密钥打码）。见 §4.1 与 §6.18 |
| 行尾（CRLF） | `.gitattributes` —— `*.sh` / `Dockerfile` / `*.yml` 强制 LF，避免上 Linux 报 `bad interpreter: /bin/bash^M` |

---

## 6. 核心机制说明

### 6.1 对话全链路（SSE）

```
POST /api/chat/stream   body=ChatDTO{messages[], sessionId, skills[], MCPs[], model, enableLexiangRag}
  └─ ChatController.chatStream
      └─ ChatServiceImpl.chat
          ├─ UserContextHolder.getUserId()          ← 来自 LoginCheckInterceptor
          ├─ new SseEmitter(sse.timeout)            ← AgentProperties 默认 1800s
          ├─ ChatContextFactory.create(chatDTO,userId)
          │    ├─ createModel()        → 用户配置模型 or defaultModel
          │    ├─ AiServices.builder(ChatAssistant.class)
          │    │     .streamingChatModel(model)
          │    │     .tools(boxTool, logTool)                   ← 永远注册
          │    │     .chatMemoryProvider(TokenWindowChatMemory, maxTokens=100000)
          │    │     + lexiangRagTool (enableLexiangRag=true 时)
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
> 6. 高成本/有副作用的工具**必须**考虑超时（HTTP 层统一超时见 §15 `tools.http-timeout`）；
> 7. **判断这个工具要不要给用户看**（2026-10-07 起）：内部基建动作（建沙盒、读写记忆、记日志）
>    要加进 `AgentProperties.Tools.hiddenTools`，见 §6.21「工具可见性」。默认可见，
>    忘了加的表现是"用户又看到一堆看不懂的内部卡片"。

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

### 6.6 标题生成

`ChatHistoryListServiceImpl.createTitle()` 标注 `@Async`（启动类已 `@EnableAsync`）：
- 用 **Moonshot** (`OpenAiChatModel` 同步模型) 生成标题
- 失败**降级**为"用户问题前 255 字符"
- 生成后 `mapper.save(history)` **只入库，不推送**
- ⚠️ `@Async` + 内部读 `UserContextHolder` 会在**新线程**执行 → ThreadLocal 取不到值，靠显式传参规避

**🗑 WebSocket 已整体下线（2026-10-04）**

原来生成完标题会推一条 `{type:"title", data:...}` 到 `/api/ws/{userId}`，
**但整个 WebSocket 只为这一个标题存在**。为了让这个非关键字段实时到，
代价是：握手鉴权 + 来源限制 + 连接重连 + 前端全局单例 + 双端心跳处理，
外加一路安全修复。**收益与成本完全不成比例，故整体删除。**

- 已删：`WebSocketService`、`WebSocketConfiguration`、`WebSocketAuthInterceptor`、
  `WebSocketAuthInterceptorTest`、`docs/WebSocket接入（前端）.md`
- 已删依赖：`nexus-agent-service/pom.xml` 的 `spring-boot-starter-websocket`
- 前端改为**下次拉会话列表时自然拿到新标题**（`GET /api/history`），用户无感
- ⚠️ 标题是 `@Async` 生成的，**刚发完消息立刻拉列表可能拿到空标题** ——
  这是既有行为，下线推送后更容易被注意到，但**不是本次引入的**
- ⚠️ 被删的那条链路曾经**零鉴权**（`@ServerEndpoint` 不经过 DispatcherServlet，
  `LoginCheckInterceptor` 对它 100% 无效，且 userId 是自增整数 → 可枚举他人推送）。
  **这正是"功能越少越安全"的典型**：功能下线，漏洞面同时归零，无需再维护鉴权。

> 📌 **将来若真需要实时推送**（多端同步、任务完成通知等），
> 别重新手搓 WS。优先复用现有 SSE（`/api/chat/stream` 那套 `{seq, runId, event, data}`
> 信封已有前端解析代码）。**先确认需求真实存在再动手。**

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

### 6.9 Skill 系统（官方=目录 + 用户=DB，已接通）

**方案**：决策 D3 —— Skill 是「一个文件夹 + 一个 SKILL.md」，**不是可执行代码**。
官方技能来自本地目录；用户技能（上传 / AI 生成）存 DB，**解压不落盘**，见下方 §6.9.1。
旧的 DB 注册表方案（实体/Mapper/Service/表）已整体删除（`docs/sql/002`）。

**目录约定**（由 `langchain4j-skills` 的 `FileSystemSkillLoader` 定义，完整说明见 `skills/README.md`）：

```
skills/                      ← 官方技能根目录，由 nexus.agent.skill.root-dir 指定（默认 "skills"）
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
官方技能：启动/缓存过期 → OfficialSkillSource.scan()   （默认 60s TTL）
用户技能：每次对话     → UserSkillServiceImpl.loadForChat(userId)（实时查库，无缓存）
两者合并  → SkillLoader.available(userId) → resolve(requested, userId)
          → ChatServiceImpl.formatForPrompt 填 {{runtimeCapabilities}}
          → ChatContextFactory 注册 Skills.toolProvider()（activate_skill / read_resource）
模型按需 → activate_skill(name) 取技能正文 → 严格按步骤执行
```

**请求侧语义（`ChatDTO.skills`）**：不传或空 → 启用**全部**；传名称列表 → 只启用指定的；
名称不存在时**只 WARN 不报错**（技能可能刚被删，不该让整次对话失败）。

**实现要点与陷阱**：

| 事项 | 说明 |
|---|---|
| 能力说明必须走 Mustache 变量 | `@SystemMessage` 是静态文本，而「有哪些技能 / 哪些 MCP 连不上」都是运行期才知道的 → `ChatAssistant.chat(..., @V("runtimeCapabilities") String)` 显式传入。不注入的话：模型不知道能调 `activate_skill`，也会把「配了但连不上」说成「我没有这个能力」。（该变量 P2-9 前叫 `availableSkills`，因同时承载 MCP 状态而改名） |
| **Skill 与 MCP 必须合并注册** | 两者都是 `ToolProvider`，连续调 `builder.toolProvider(a)` / `toolProvider(b)` 会**互相覆盖**，只剩最后一个生效。必须收集为 `List` 后一次 `toolProviders(list)`（`ChatContextFactory` 已按此实现） |
| **类型必须是 `Skill` 接口，不是 `FileSystemSkill`** | 用户技能是 `DefaultSkill`（内存态），官方技能是 `FileSystemSkill`。原先把 `cached` 声明成具体类型 `FileSystemSkill`，等于把「技能只能来自文件系统」写进了类型系统里。加用户技能时必须放宽为接口 |
| **`or()` 必须显式包住整个或条件** | MyBatis-Plus 的 `.eq(A).or().eq(B)` 里 `or()` **不带括号**，前一个 `eq` 会被 OR 掉。写用户技能可见性条件时用 `.and(w -> w.eq(...).or().eq(...))` 包起来，否则等于「所有人都能看到所有技能」 |
| **必须传 userId** | `resolve` / `formatForPrompt` 都加了 `userId` 参数（两处调用点：`ChatContextFactory` 用 `runContext.userId()`、`ChatServiceImpl` 已有局部变量）。漏传会让用户技能按「未登录」处理 —— 表现为别人的技能永远不生效，且没有任何报错 |
| 扫描失败不阻塞启动 | 目录不存在/读失败都降级为「无技能」并打日志，不让应用起不来 |
| **缺迁移要降级而不是抛** | `loadForChat` 全程 try-catch：`010` 没跑时表不存在，此时静默降级为「无用户技能」，绝不能让整个对话挂掉 |
| 缓存策略的不对称 | 官方技能走 TTL（部署期固定），用户技能**实时查库** —— 让用户上传完还要等 60 秒才生效是很糟的体验 |
| 路径穿越 | 官方：库按**预索引资源清单**匹配名称，不做运行时拼路径。用户：见 §6.9.1 的解析器 |

**配置**：`nexus.agent.skill.enabled` / `root-dir` / `refresh-interval`（**仅对官方技能生效**），见 §15。
**冒烟**：仓库内置 `skills/verify-skill`（问「今天的暗号是什么」应回答含 `BANANA-7731`），
用于快速验证「扫描 → 提示词注入 → activate_skill → 按步骤作答」整条链路。

#### 6.9.1 用户技能：为什么存 DB 而不是解压落盘

用户上传 `.zip` / `.skill` / `.md`，或让模型生成技能。**刻意不落盘**：

解压到 `skills/users/<userId>/` 要处理四件事 —— **zip slip 路径穿越、zip 炸弹、
删除时机、多用户目录隔离**。而 `Skills.from(Collection<? extends Skill>)` 接受任意实现，
`DefaultSkill.builder()` 能在内存里直接造技能（正文 + `DefaultSkillResource` 资源一起装）。
**落盘这一步被整个消掉**，前三个问题随之不存在。

代价是 `SkillLoader` 的类型必须从 `FileSystemSkill` 放宽为 `Skill`（见上表）。

**安全边界**（`SkillPackageParser`，24 个单测逐条覆盖）：

| 风险 | 处理 |
|---|---|
| zip slip | 条目名含 `..` / 绝对路径 / 反斜杠 / 冒号 → **整包拒绝** |
| zip 炸弹 | 条目数 ≤200、单条目 ≤1MB、总解压 ≤4MB；**用实际读到的字节计数**，不信 zip 头声明的 size（可伪造） |
| 可执行内容 | 只收白名单文本扩展名；`scripts/` 直接忽略 |
| 技能名注入 | 必须 `^[a-z0-9][a-z0-9-]{0,63}$` 且**全局唯一**（模型 `activate_skill` 只认名字，重名会让路由变糊） |
| 越权 | 改/删只能操作自己的；私有技能对别人返回 404 而非 403（不泄露「它存在」） |

⚠️ **不防「技能内容有害」**：技能正文只是提示词，模型读它然后行动。
真正的行为边界在工具层（`ToolCallGuard` 幂等、沙盒隔离、配额）。开放社区前要清楚这点。

**解析器的两个易错点**（都真踩过）：

| 问题 | 现象 |
|---|---|
| `isCollectable` **不能**排除 `SKILL.md` | 排除后后面从 entries 找它永远找不到，**所有 zip 包都报「未找到 SKILL.md」**。SKILL.md 不作为资源暴露是在定位到它之后 `remove` 掉的 |
| **先剥引号再去行尾注释** | 反过来的话 `description: "做 C# 相关 # 重点"` 里的 # 会在引号还在时被当注释，把后半句连引号砍掉 |

⚠️ `ZipInputStream` 遇非法数据**不抛异常**，只是 `getNextEntry()` 一直返回 null。
所以「一条都没读到」必须单独报「文件不是有效 zip 包」，否则会误报成「包内未找到 SKILL.md」，把用户的问题指歪。

**两步提交**：上传（`/upload`）与 AI 生成（`/ai-generate`）**都只返回草稿，不落库**，
用户确认后调 `/save` 才入库。理由：解析可能失败，且让人有机会看清模型到底写了什么。
AI 生成的结果走**与上传完全相同的校验路径**（提示词要求模型输出纯 Markdown 原文），
模型起名不合规会被同一个解析器拦下。

接口清单见 `docs/前端开发指南.md` 与 `SkillController` 的 Swagger 注解。

### 6.10 启动配置自检（P1-10）

`config/StartupConfigValidator`（`@PostConstruct`）在启动时**一次性**校验关键配置，
把结果分成两级打日志：

| 级别 | 缺失时行为 | 包含 |
|---|---|---|
| 必需 | **默认 fail-fast 阻止启动**（`nexus.agent.startup.fail-fast=false` 可降级为 WARN） | `spring.datasource.url`、对话模型 api-key、`API_KEY_SECRET` |
| 建议 | 只 WARN，**明确写出哪项能力会不可用** | `JWT_SECRET`、`AI_KEY`、`MOONSHOT`、Redis 主机、SMTP 账号/密码 |

⚠️ **prod 下"建议"级基本轮不到它报**：`application-prod.yml` 里这些值写成 `${MOONSHOT}` 之类
且**没有默认值**，占位符解析发生在建 Bean 时（比 `@PostConstruct` 更早），
所以真缺了会先抛 `Could not resolve placeholder` 直接起不来，而不是等到这里 WARN。
换句话说：**prod 部署时上面两级的区分意义不大，缺哪个都是起不来**；
这个"两级"主要服务于 dev（yml 里写的是真实值，缺的是另一批东西）。

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
| 调用超时 | `WebClientConfig` 的 `responseTimeout`（= `tools.http-timeout`，默认 100s） | 原先**没有响应超时**，沙盒挂起/网络黑洞时工具无限等待、整条 SSE 卡死（用户只看到「一直不出字」）。刻意设得比 `sse.timeout`(1800s) 小，以便先返回结构化 `TIMEOUT` 而不是掐断整条流 |

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
| 失败降级 | **仅指 `resetQuotaPeriod` 那条 UPDATE**：缺列时按累计用量判定（保守，不放行）；周期值非法时按 `NONE` |
| 时区 | 服务端默认时区（DAILY = 当天 00:00，MONTHLY = 当月 1 号 00:00） |

> **为什么不跑定时任务**：`@Scheduled` 挂了（或实例没起来）会导致「所有人配额都不刷新」，
> 且多实例部署还要抢锁。惰性重置把这个故障模式整个消掉了 ——
> 代价只是长期不说话的用户不会被清零（而他也没在消耗额度）。

> ⚠️⚠️ **`007` 是「必须执行」而不是「可选」**（2026-10-01 更正，原先这里写得过于乐观）：
> `User.tokenPeriod` / `tokenPeriodStart` 是**普通字段**（没有 `@TableField(exist = false)`），
> MyBatis-Plus 自动生成的 `selectById` / `query().eq("email", ...)` / `insert`
> **都会带上 `token_period`、`token_period_start` 两列**。
> 缺列时应用**能启动**，但**登录、注册、任何查用户的接口都会 500**
> （`column "token_period" does not exist`）——上表说的"降级"只覆盖手动写的
> `resetQuotaPeriod`，**覆盖不到 MP 的自动 SQL**。上线前务必先跑 `007`。

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
>
> 🔴 **2026-10-05 起：那份指南冻结，后续改动一律写 [`docs/前端增量变更.md`](./docs/前端增量变更.md)**
> （只写增量："改了什么 / 你要动哪几行"）。前端早已搭好，不需要教程。见 [§8.1](#81-前端文档同步规则)。

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

### 6.16 知识库 RAG：入库 / 检索的向量口径与隔离（P2-13）—— 🔴 已随本地知识库下线，本节仅作历史归档

> **2026-10-04：本地知识库（pgvector）整体下线。** 用户反馈"不好搞同时又不好用"，
> 决定只保留第三方（乐享）知识库检索。本节描述的 `RagTool` / `KnowledgeBaseFileServiceImpl` /
> `PgVectorEmbeddingFactory` / `knowledge_embedding` 表**代码里已全部删除**，
> 表由 `docs/sql/009_drop_local_knowledge_base.sql` 删除，依赖 `langchain4j-pgvector` 与
> 环境变量 `ALI_AI_KEY` 一并移除。
>
> **仍然有效的教训**（换成任何向量检索方案都适用）：
> ① 入库与检索必须同一个向量模型，否则相似度无意义且不报错；
> ② 检索**必须**带用户维度过滤，缺过滤就是跨用户越权；
> ③ 拿不到用户上下文时**拒绝**检索，绝不能退化成不过滤。
>
> 下面的具体类名/表名已不存在，查细节请看 `git log` 里下线前的版本。

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
| `docker-compose.yml` | **本地**一键起全套：pgvector / redis / box / app（含 depends_on 健康检查）。⚠️ 会新建一个空 PG 并在首次启动时执行 `001_baseline.sql`（**会 DROP 全表**） |
| `docker-compose.server.yml` | **服务器部署**用：只起 `box` + `app` 两个容器，PG / Redis 用外部已有的。故意**不写** `SERVICE_IP` 与 CORS（写了会覆盖 `env_file`），`BASE_URL=http://box:8000`，box 只 `expose` 不映射宿主端口 |
| `.env.example` | **应用**的环境变量模板（与 `nexus_agent_box/.env.example` 是两份，别混） |
| `scripts/check-env.sh` | 部署前自查 `.env`：必需项是否填、占位符是否漏改、写法是否正确。退出码 0/1，密钥打码输出 |
| `.gitattributes` | 强制 `*.sh` / `Dockerfile` / `*.yml` / `*.yaml` / `.env.example` 为 LF 行尾 |
| `nexus_agent_box/docker-compose.yml` | 沙盒服务，**以镜像为准** |
| `nexus_agent_box/docker-compose.override.yml` | 开发用（自动合并）：把源码挂回去热重载 |

**⚠️ Spring Boot 不会自动读 `.env`**（最常见的一次启动失败）

`env_file` / `--env-file` 是 Docker 的能力，不是 Spring 的。所以：

- `docker compose up` / `docker run --env-file .env` → Docker 把变量注入容器进程，**没问题**；
- 直接 `java -jar` → **必须自己 export**，否则所有变量为空、启动自检 fail-fast 拒绝启动：

  ```bash
  set -a; . ./.env; set +a
  exec java $JAVA_OPTS -jar nexus-agent-web.jar
  # systemd 用 EnvironmentFile=/path/to/.env，别写成 Environment=
  ```

- `.env` 里**不要**写 `export ` 前缀：docker compose 的 `env_file` 不认，
  那样 key 名会变成 `export`、值整行错乱。`scripts/check-env.sh` 会查这一项。

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

### 6.20 产物归属：哪个文件是哪一轮产出的（2026-10-05，方案 B）

> 诉求来源：`产物归属-后端诉求.md`（前端侧已回归通过，只差后端补字段）。

**问题**：`GET /api/artifact?sessionId=` 只说"这个会话产出了哪些文件"，不说"哪个是哪一轮产出的"；
而 `GET /api/history/{sessionId}` 里又通常没有 `ARTIFACT` 行。于是**刷新页面后**，
前端在数据上无法把产物归到某一轮，只能全部堆进右侧「成果文件」面板。

**方案选型（A / B 二选一，选了 B）**：

| 方案 | 做法 | 为什么不选 |
|---|---|---|
| A | 历史里补 `ARTIFACT` 行 | ❌ `chat_memory` **同时是 LangChain4j 的 ChatMemoryStore**：`PgChatMemoryStore.getMessages()` 对查出来的**每一行**执行 `ChatMessageDeserializer`，插非消息行会污染模型上下文；增量写入的「锚点去重」也会被这些行打乱 |
| **B** | 两边都加持久化的 `runId` | ✅ 只加列、不新增行，**完全不碰记忆语义** |

**落地**（`docs/sql/011_add_run_id.sql`）：

- `sys_file.run_id` / `chat_memory.run_id`，都**可空**。
- `runId` 在 `ChatServiceImpl` 里生成（16 位十六进制），**必须在 `RunContext` 之前生成** ——
  `RunContext` 是把它带到流式回调线程（拿不到任何 ThreadLocal）的唯一通道。
- 同一个 `runId` 同时写进「本次运行落库的每一条历史消息」与「本次运行产出的每个产物」。
- 前端匹配规则就是**字符串相等**：`artifact.runId === message.runId` → 内联到那条消息末尾。

**两个必须记住的点**：

1. ⚠️ `runId` 是「**产出该文件的那次运行**」，不是当前请求的运行。所以**必须持久化**，
   进程内临时 id 重启后就归不上了。
2. ⚠️ `ChatMemoryServiceImpl.getHistoryBySessionId` **必须逐行处理**，不能
   「先把所有行映射成 `ChatMessage` 列表、再统一转 VO」—— 那样行上的 `runId` 就丢了，
   补字段也补不出来。这是 2026-10-05 重构的直接原因，回归测试见 `ArtifactRunIdTest`。

**老数据**：`runId` 为 `null`，前端按「归属不明」处理（只进面板、不进对话）。
**不要**为了覆盖老数据用"按时间/顺序猜"的方案 —— 猜错会把产物挂到没产出它的那一轮，比不显示更糟。

---

### 6.21 工具可见性：哪些工具调用不发给用户看（2026-10-07）

> 诉求：用户反馈「创建沙盒、查看用户记忆这种内部动作不该摊在对话里」，
> 而「执行代码」这类功能性动作要照常显示。

**配置**：`nexus.agent.tools.hidden-tools`（`AgentProperties.Tools.hiddenTools`，
**唯一事实源，任何 profile yml 都不许声明** —— 它是 List，yml 里写一份会**整份取代**而不是合并，
护栏 `ToolVisibilityDriftTest`）。

默认隐藏 5 个：`create_box` / `delete_box` / `search_user_memory` / `save_user_data` / `record_log`。
置空数组 = 全部可见。

**判定入口**：`ToolVisibility#isHidden(toolName)`（忽略大小写与空格；null / 空白一律放行）。

**两个过滤点，缺一不可**：

| 层 | 位置 | 作用 |
|---|---|---|
| 实时流 | `SseResponseConverter#sendRequest` / `#writeToolResult` | 不推 `tool_execution` / `tool_execution_result` |
| 历史 | `ChatMemoryServiceImpl#toMessageVO` | `GET /api/history/{sessionId}` 不返回（读取时过滤，库里不动） |

🔴 **三条硬约束**（改动时别踩）：

1. **绝不动存储层**。`chat_memory` 里的 `tool_calls` 与 `tool_result` 必须成对留着 ——
   OpenAI 兼容协议要求 `tool_calls` 后面必须跟配对 id 的 `tool_result`，
   删掉任何一半，下一轮请求会被供应商直接拒（400）。过滤只在"转给前端"这一瞬发生。
2. **请求与结果必须一起隐藏**。只发一半会在前端留下一个永远等不到配对的孤儿结果卡片。
3. **并行调用逐项过滤**。一条 `AiMessage` 可能带多个 `tool_calls`；
   只有「正文、思考、可见调用」全空时才整条不返回，否则前端会渲染空气泡。

**已知取舍**：隐藏工具执行期间前端收不到任何事件（画面静默）。用户已拍板不加「处理中」事件，
连接由 15s 心跳注释帧保活。若日后觉得静默太久，加事件要同时改 `docs/sse-contract.md` 与前端。

**新增工具时**：默认可见。属于内部基建的就加进 `hiddenTools`，并同步 `docs/前端增量变更.md`。

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
| GET | `/api/artifact?sessionId=` | `ArtifactController` | 列出某会话里 AI 交付的产物（P2-10，供前端"本会话文件"面板）。每项带 `runId`，与历史行的 `runId` 匹配可定位到产出它的那一轮（§6.20） |
| DELETE | `/api/artifact/{id}` | `ArtifactController` | 删除产物：**先删记录、再尽力删 OSS 对象**（P2-10） |
| POST | `/api/file/image` | `FileController` | 上传图片 |
| GET | `/api/file` | `FileController` | 当前用户文件列表 |
| ~~POST/GET | `/api/knowledge*`~~ | ~~`KnowledgeController`~~ | ❌ **已删除 2026-10-04**（本地知识库下线）。`/api/file` 系列**不受影响**，仍服务聊天附件 |
| GET | `/api/mcp/service` | `McpController` | 从服务端拉 MCP 列表 |
| GET/POST/PUT | `/api/mcp` | `McpController` | 查 / 存 / 改 |
| GET/DELETE | `/api/mcp/{id}` | `McpController` | 详情 / 删除 |
| ~~WS~~ | ~~`/api/ws/{userId}`~~ | — | — | ❌ **已删除 2026-10-04**（标题推送下线，见 §6.6）。前端改为拉 `/api/history` 拿标题 |

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

### 8.1 前端文档同步规则（2026-10-05 立，对应铁律第 9 条）

**前端是用户自己写的，后端文档是他唯一的依据。** 后端改了字段却不写文档，
前端不会知道 —— 表现永远是"功能明明做了、接口也有数据，但页面没反应"，
而排查成本极高（前端会先怀疑自己、后端会先怀疑前端）。

### 8.1.1 两份文档，各管一段（2026-10-05 定）

| 文档 | 定位 | 什么时候动 |
|---|---|---|
| **`docs/前端增量变更.md`** ✅ 默认写这里 | **只写增量**：这次改了什么 + 前端要动哪几行。按日期倒序追加 | **每次**碰到前端可见的改动 |
| `docs/前端开发指南.md` 🔒 冻结 | 「从零搭前端」的完整教程，按后端 P0–P3 冻结时的状态写成 | **不动**。用户前端早已搭好，改它只会让 diff 淹掉真正的增量 |
| `docs/sse-contract.md` | SSE 契约权威版 | 改事件名 / 信封 / 载荷字段时 |

> 为什么拆成两份：2026-10-05 第一版把 runId 写进了完整指南（7 处、带伪代码和边界表），
> 用户反馈"太全面了，我只需要需要更新的部分，因为我前端已经搭好了"。
> —— 已搭好的前端不需要教程，只需要 diff。

**必改清单（命中任一即同步）**：

| 改了什么 | 同步到哪 |
|---|---|
| 接口路径 / 方法 / 参数 | `docs/前端增量变更.md`（新增 / 变更 / 删除分别写清） |
| 响应体字段（含 VO / 实体直接序列化） | 增量文档：字段名 + 类型 + **可空性** + 一句"前端怎么用" |
| SSE 事件名 / 信封 / 载荷字段 | `docs/sse-contract.md` + 增量文档 |
| 错误码 / 状态码 / 错误消息文案 | 增量文档（前端错误分支要跟着改的才写） |
| 上传大小 / 超时 / 缓冲等容器配置 | 增量文档，并写明 **Nginx 侧要同步什么**（`client_max_body_size` 等） |
| 需要用户先执行 SQL 才能生效 | 🔴 **必须单独写一条**并给 `psql` 命令 —— 否则用户看到全是 `null` 会以为后端没做 |

**写法要求**：
1. 🔴 **只写增量，不写教程。** 读者要的是「这次改了什么、我那边要动哪几行」。
   判断标准：**写出来的内容在本次改动之前就已经成立 → 那是教程，删掉。**
2. 结构固定为：背景一句话 → 后端改了什么（表格）→ 你要动哪几处（编号）→ 不用动/注意（短列表）。
3. **不要**堆实现步骤、JS 伪代码、长篇要点列表、边界情况表格 —— 2026-10-05 第一版就写成这样被退回。
4. **可空字段必须写明"什么时候为 null、前端该怎么兜底"** —— 最常被漏，也最容易出线上问题。
5. 涉及"新字段 + 老数据没有"时，明确写老数据的行为（例：`runId` 上线前的历史行为 `null`，前端跳过）。
6. 在 [§11 变更记录](#11-变更记录本文件维护的更新日志) 加一行，备注里写「**前端需要同步：……**」。

**不要**：只在代码注释里写清楚就算完（前端看不到 Java 注释）；
也不要"等前端来问"—— 前端不知道有这个字段，就不会问；
更**不要**借"同步文档"的机会重写整份指南 —— 那会让用户看不出这次到底改了什么。

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
| 2026-10-09（密钥读取） | **🔴 `TAVILY_API_KEY` 读不到外部配置：`System.getenv` 绕开 Spring（10-03 修过的坑又被踩一遍）** | 改 `AgentProperties.Websearch`（新增 `API_KEY_PROPERTY` / `API_KEY_EXPRESSION` 两个常量，一个事实源）、《`WebSearchTool`、`WebExtractTool`（Key 改由构造器参数 `@Value(API_KEY_EXPRESSION)` 注入；**合并成一个/两个构造器**）、`EffectiveConfigReporter`（改读同一个源，并新增**「来源是哪一条」**）；`WebSearchToolTest` / `WebExtractToolTest` 随之调整；`ValueInjectionGuardTest` 从「字段」扩到**构造器/方法参数**；`ExternalConfigOverrideProbe` +3；改 `conf/nexus-override.yml`（补示例 + 放大警告）、`.env.example`（**补 `TAVILY_API_KEY` 一行** —— 此前压根没有）、`docs/部署指南（改配置不重打包）.md`（§八 重写、§七 表格同步）、`AGENTS.md` §0 铁律 4 补配套细则 | **无前端影响**（工具是否注册与后端配置读取方式，接口/SSE 字段一字未动）。<br>**① 现象**：用户问「我明明写到 nexus-override.yml 了，为啥说没有这个 key」。<br>**② 根因**：`web_search` / `web_extract` / 配置快照三处都直接调 `System.getenv("TAVILY_API_KEY")` —— 这条路径**绕开 Spring**，所以写在外部 yml、面板 `.env.properties` 里的值**一律读不到**。而 `EffectiveConfigReporter` 同样用 `getenv`，于是快照永远显示「未配置」，用户毫无办法自查。**这是 `RuntimeSecretInitializer` 类注释里一字不差记着的同一个病**（2026-10-03 线上，`JWT_SECRET` / `API_KEY_SECRET`），当时确立的修法就是「密钥走 Spring：配置项优先 + 同名环境变量兜底」——`web_search` 是 10-07 新增的，把老坑又踩了一遍。<br>**③ 修法**：`@Value("${nexus.agent.websearch.api-key:${TAVILY_API_KEY:}}")`，表达式抽成 `AgentProperties.Websearch.API_KEY_EXPRESSION` 一个常量，三个消费方**都引用它**（避免各写一份后漂移）。两条路都通：外部 yml / 面板 properties 走配置项，`.env` / compose / systemd 走环境变量，两处都写时配置项赢。<br>**④ 仍不违反铁律 4**：代码里只有占位符**表达式**、没有 Key 的**值**；值来自仓库之外。占位符末尾的 `:默认值` 不能省 —— 缺了它会把「可选功能没配」升级成「整个应用起不来」（这正是同日早些时候那次启动事故的形态）。<br>**⑤ 顺带把构造器形态收敛了**：`WebSearchTool` 从「2 个构造器（一个还是为单测加的）」合并为**1 个**，`WebExtractTool` 从 3 个减到 2 个 —— 参数签名本来就够用（Spring 和单测传的是同一个东西：Key 字符串），合并后 2026-10-08 那类「多构造器二义」的风险在本类**不存在了**。<br>**⑥ 快照新增「来源」**：现在会写明 `来源：配置项 nexus.agent.websearch.api-key` 或 `环境变量 TAVILY_API_KEY` —— 这正是回答「我写的那处到底被读到没有」，同时顺手解决了「两份都写、其中一份写错」这种更难查的形态。<br>**⑦ 护栏**：① `ValueInjectionGuardTest` 扩展到**构造器/方法参数**上的 `@Value`（注入点从字段挪到参数后，缺默认值的危害一模一样）；② `ExternalConfigOverrideTest` 用**真实 ConfigData + 真实 `@Value` 注入**断言「外部 yml 里的 `websearch.api-key` 必须被注入」——**已反向验证**：把表达式临时改回只认环境变量，立刻红并报 `expected: <tvly-from-external-file> but was: <>`，与线上症状一致；③ 同一处新增「三个消费方必须引用同一个表达式常量」——否则有人把工具改回 `getenv`，上面那条测试**照样绿**（它测的是常量本身），这条专门堵这个漏。<br>**⑧ 踩坑记录**：第一版测试用 `env.getProperty(表达式)` 断言，全红 —— `Environment.getProperty` **不做占位符解析**（拿表达式当 key 查必然是 null）。改成用一个与工具**同款注入方式**的替身 bean 去取，才测到真机制。<br>**⑨ 文档为什么也要改**：§八 原文写着「100% 是环境变量没进到 Java 进程，不关代码的事」——现在这句话已经错了，不改就是把后来人往反方向带。同时 `.env.example` 里**从来没有过 `TAVILY_API_KEY` 这一行**，而 §八 却让用户去 `.env` 里配它；护栏 `EnvPlaceholderDriftTest` 管不到这个缺口（它只比对「prod yml 里的占位符」，而 TAVILY 压根不在 yml 里）。<br>**⑩ 🔴 顺带逮到一个更值钱的问题：`ExternalConfigOverrideProbe` 从来没被 `mvn test` 跑过。** 它的类名不匹配 surefire 的默认 include 模式（`Test*` / `*Test` / `*Tests` / `*TestCase`），而唯一的手动 `-Dtest=` 运行会在 `target/surefire-reports` 里留下报告 —— **看起来"跑过了"，全量计数里却从来没有它**，README 与部署指南还都拿它当「已实测验证」的依据引用。这正是项目里第二次遇到「护栏静默失效」（第一次是 `MemoryWindowDriftTest`）。已改名 `ExternalConfigOverrideTest`（`git mv`），并新增护栏 `SurefireDiscoveryGuardTest`：扫 `target/test-classes`，凡带 `@Test`/`@ParameterizedTest`/`@RepeatedTest`/`@TestFactory`/`@TestTemplate` 的类，类名必须能被 surefire 发现（用 `Class.forName(name, false, loader)` 加载，**不初始化**，避免扫描阶段触发测试类的静态块）。**已反向验证**：临时塞一个 `TempWrongNameProbe` → 立刻红并精确点名。<br>测试 **668→675**（+5 为改名后首次被纳入的 `ExternalConfigOverrideTest`，+2 为新增 `SurefireDiscoveryGuardTest`），0 失败，12 人工跳过 |
| 2026-10-09（MCP） | **MCP 客户端三处收口：日志能定位到具体服务、不再被 30 秒健康检查刷栈、失败客户端不再永久驻留；顺带修掉「一个坏 MCP 把整个聊天请求打成 500」** | 改 `McpClientRegistry`（`DefaultMcpClient.builder()` 新增 `.key("mcp-<id>:<名字>")`、`.autoHealthCheck(false)`、挂 `McpClientListener.onExecuteToolError` 剔除缓存；**`try` 从 `build()` 开始**包住握手；新增 `clientKey()` / `evictSelf()` / `rootMessage()` / `closeQuietly(McpTransport)`）；新增 `McpClientRegistryTest`(4) | **无前端影响**（MCP 客户端的建立/复用方式，接口与 SSE 字段一字未动）。<br>**① 起因**：用户贴服务器日志问「咋回事」。日志里是 `DefaultMcpClient : MCP server health check (client key: 34b4aed1-…) failed … Unexpected status code: 401` **反复刷**。<br>**② 根因一：日志定位不了**。我们没设 `Builder.key(...)`，langchain4j 就生成随机 UUID —— 拿着日志**不知道是哪个 MCP 服务**在报错。已改成 `mcp-<库里的 id>:<名字>`（名字拍平换行、截断到 30 字，**不带 URL 与 header**）。<br>**③ 根因二：自动健康检查只刷日志、不做任何恢复**（反编译 1.12.1-beta21 核实）：`autoHealthCheck` **默认 true**、间隔 **默认 30s**，失败打完整堆栈 + 重连、**永不放弃**；而 `getOrCreate()` **命中缓存时不做健康检查**，`evict` 又只在配置增删改时调用 → 一个「建连时通过、之后失效」的客户端（token 过期这种最典型的场景）会**永久赖在缓存里**：每轮对话都拿它、每次调用都失败，同时每 30 秒刷一次栈，**直到重启进程**。类注释里写的「由用户或后续的健康检查机制处理」，实际上没人处理。→ 关掉自动健康检查，改挂 `onExecuteToolError`：**工具调用失败即按身份剔除该客户端**（`clients.remove(id, self)` 原子操作，天然解决「并发失败只关一次」与「别误关配置变更后重建的新客户端」两件事），下轮重新建连 + `checkHealth()`，仍失败就 `available=false`，用户可见。<br>**④ 根因三（测试跑出来的，之前没意识到）：连不上的异常会冒到聊天请求上**。`DefaultMcpClient` 的**构造器里就做 MCP 握手**，所以 401/连不上是在 **`build()`** 抛的，而原来 `try` 只包住 `checkHealth()` → 异常穿过 `create()` 冒到 `getMcp()`，把**整个聊天请求打成 500**，与调用方「连不上就标记 `available=false` 并注入提示词」的设计预期**正好相反**。→ `try` 从 `build()` 开始（`client == null` 时手动关 transport，否则每失败一次漏一个 HttpClient）。<br>**⑤ 必须两条一起改的理由**：只把自动健康检查关掉，等于把一个「吵但会自愈」的问题换成一个「安静但永不恢复」的问题 —— **更糟**。所以护栏里两条一起断言。<br>**⑥ 顺带排除一个怀疑方向**：反编译确认 `customHeaders` 在 `createRequest` 与 `startSubsidiaryChannel` **两条路径都会带**，所以 401 不是「头只发 POST、SSE 的 GET 没带」造成的。<br>**⑦ 未覆盖的部分（诚实标注）**：`autoHealthCheck(false)` 本身没有测试覆盖 —— 要拿到 `DefaultMcpClient` 实例就得完成一次真实 MCP 握手，为它写一个假服务端不划算；该行以「上游默认值已由字节码核实 + 代码注释说明」为准。<br>**⑧ 另一类日志不是问题**：`Http11Processor: Error parsing HTTP request header`（`RTSP/1.0…` / 裸 TLS `0x16 0x03 0x01…`）是**外部扫描器噪音**，Tomcat 记一条 INFO 即断开，与本项目无关。但它暴露了 `docker-compose.server.yml` 里 app 是 `ports: "8080:8080"`，**8080 直接映射到公网**，建议改成只给 nginx（本次未改，属部署决策）。<br>测试 **664→668**，0 失败，12 人工跳过 |
| 2026-10-09 | **🔴 又一次启动不了：`WebSearchTool.apiKey` 被改成 `@Value("${TAVILY_API_KEY}")` 打在 `final` 字段上（→ 没配 Key 整个应用起不来）** | 改 `WebSearchTool`（`apiKey` 去掉 `@Value` 与常量初始值，恢复「构造器读 `System.getenv`」；被注释掉的生产构造器补回并标 `@Autowired`，测试构造器改为 4 参 `(props, guard, sourceStore, envKey)`）；新增 `ValueInjectionGuardTest`(2) | **无前端影响**（纯启动路径，接口/SSE 字段一字未动）。<br>**① 现象**：`Error creating bean with name 'webSearchTool': Injection of autowired dependencies failed`，依赖链与 10-08 那次**一模一样**（`chatController ← chatServiceImpl ← chatContextFactory ← toolRegistry ← webSearchTool`），最内层是 `PlaceholderResolutionException: Could not resolve placeholder 'TAVILY_API_KEY' in value "${TAVILY_API_KEY}"`。<br>**② 根因是「修错了方向」**：10-08 确定的修法是「**保留两个构造器** + 给生产那个标 `@Autowired`」，但实际落地成了**把生产构造器整段注释掉、只留测试那个**，再把 Key 换成 `@Value` 字段注入。这样做的动机可以理解 —— 单构造器不再触发构造器二义，`BeanConstructorInjectionGuardTest` 确实变绿了；**但那条护栏只管构造器形态，管不到字段注入**，于是换了个姿势炸得更大。<br>**③ 这一行同时踩两个坑（JDK21 + Spring 6.2.17 已实测复现，两条都写进了代码注释）**：<br>　**(a) 占位符没有默认值** → Boot 的 `PropertySourcesPlaceholderConfigurer` 走 `resolveRequiredPlaceholders`，环境变量缺失时**直接抛异常**，顺着依赖链把整个应用拉挂。这把「一个**可选**功能没配」升级成了「**全局**不可用」，与本工具「没配 Key 就不注册、其它功能照常」的设计正面冲突。<br>　**(b) `@Value` 打在 `final` 字段上**（且带 `= ""`）→ 该字段是**编译期常量**，javac 会把类内所有读取**常量折叠**。实测：反射看字段确实被 Spring 写成了 `"abc"`，但同一个类里 getter 读到的仍然是 `""` → `apiKey != null` 恒为 true，工具照常注册、然后每次调用 401。**注入看起来成功了，实际完全没生效** —— 比直接报错更难查。<br>**④ 为什么三道防线这次又全瞎（与 10-08 同源，但多了一课）**：编译期本来会报错 —— `WebSearchToolTest` 已按 4 参构造器写好，主类却只剩 3 参；但**前一次会话是在改这个文件的中途断的**，IDE 用旧的 `test-classes` 跑主类，于是「测试模块编译不过」被完全忽略，直接炸在启动上。<br>　👉 **教训（建议当规矩用）：报 `Application run failed` 之前，先 `mvn test-compile`。当前状态下 `nexus-agent-web` 的测试源码能不能编过，是一个比日志更早、更准确的信号。**<br>**⑤ 新护栏 `ValueInjectionGuardTest`**：纯反射扫组件扫描范围内的类（不需要起容器），两条判据 —— **`@Value` 不落在 `final` 字段上**、**每个 `${...}` 必须带 `:默认值`**。判据只看写法不看运行时环境，所以本地与 CI 结论一致，不存在「本地绿、服务器红」。**已反向验证**：把坏写法放回去 → 立刻红并精确点名 `WebSearchTool.apiKey（final 字段：${TAVILY_API_KEY}）` 与 `（占位符 ${TAVILY_API_KEY} 没有默认值 → 缺配置时整个应用起不来）`。现网其余 4 处 `@Value` 全是 `:默认值` 且非 final，扫描后**零误报**。<br>**⑥ 收尾做了容器级实测**：起了个只含该链路的 Spring 上下文（`AgentProperties/ToolCallGuard/ToolSourceStore/UrlGuard/WebSearchTool/WebExtractTool`，不需要 PG/Redis）——**没配 Key 也能 refresh 成功**，且 `web_search`/`web_extract` 的 `enabled()` 均为 `false`（= 工具集不注册，模型看不到，其它功能照常）；配上 Key 则两者为 `true`。<br>测试 **662→664**（新增护栏 2 条；另有 3 条 `WebSearchToolTest` 来源用例此前因测试模块编译不过**从未真正执行过**，本次首次跑通），0 失败，12 人工跳过 |
| 2026-10-08（第六批） | **「配了 `TAVILY_API_KEY` 却没生效」的自查通道（用户反馈「AI 说没有这个工具」）** | 改 `EffectiveConfigReporter`（配置快照新增 `联网搜索(web_search)` / `正文提取(web_extract)` 两行，报 Key 的**长度与形态**、绝不打印本体）、`WebSearchTool` / `WebExtractTool`（未启用日志 INFO→**WARN**，且写明"env 只在进程启动时读一次、改完必须重启"）；新增 `EffectiveConfigReporterTest` +1（用 logback `ListAppender` 抓输出，断言这两行存在、且本机若配了 Key 时**绝不出现在日志里**）；改 `docs/部署指南（改配置不重打包）.md`（新增 §八「三步排查」） | **无前端影响**（纯启动日志与文档）。<br>**① 现象与根因**：`web_search`/`web_extract` 的设计是「没读到 Key 就**整个工具集不注册**」—— 模型压根看不见它，所以只会说"没有这个工具"。这是刻意的（"看得见却用不了"的工具对模型最糟，会反复尝试白烧 token），但代价是**配置没生效与"没做这个功能"表面上一模一样**，且此前**没有任何可见痕迹**。<br>**② 根因是运维侧**：**Java 进程只在启动时读一次环境变量** —— 「配了」和「进程读到了」是两件事，中间隔着一个重启。`docker compose restart` 复用创建时的环境（无效），必须 `up -d` 让 compose 检测到配置变化**重建容器**；`docker exec ... export` 只影响那条命令的子进程（同样无效）。<br>**③ 所以修的是「可见性」而不是代码逻辑**：启动快照里那两行能一眼区分「没配 / 配了但进程没读到 / 配了但值不对（前缀不是 `tvly-`）」。<br>**④ 🔴 只报长度与形态，绝不打印 Key 本体** —— 日志会外发、会归档；测试里也用 `ListAppender` 钉住了这条（本机若配了 Key 会真的抓到泄漏）。<br>测试 **660→661**，0 失败 |
| 2026-10-08（第五批，同日补） | **首字延迟**三段**归因：收尾事件新增 `preflightMs` + `firstTokenMs`** | 改 `ChatServiceImpl`（登记 `CHAT_PREFLIGHT.total` → `markPreflightMs`；`onPartialThinking`/`onPartialResponse` 首次触发时 `markFirstTokenMs`）、`SseResponseConverter`（两个字段随 `withTtfb` 一并下发）、`docs/sse-contract.md`（§3.6 表 +2 行、「5 个字段」改「7 个」，并新增「延迟三段归因」小节）、`docs/前端增量变更.md`（第五批）；`FrontendTelemetryTest` +3 | **前端影响：三个收尾事件的 data 多一个可选 number 字段 `preflightMs`（后端前置耗时）。** **① 起因**：用户反馈首字 0.8s~58s 巨幅波动、怀疑后端也有份。查日志确认慢在模型侧，但**能下结论的两个数当时只在服务端日志里**（`CHAT_TTFB` / `CHAT_PREFLIGHT`），前端与用户都看不到 → 只能互相猜。这是同一天第二次出现「到底谁慢」的争论。<br>**② 口径**：`ttfbMs − preflightMs` = 纯模型侧等待。典型值 24ms（缓存命中）/ 969ms / 5845ms（MCP + 技能 TTL 过期重建，属于**我们自己的**周期性尖刺，已知待办）。<br>**③ 当次实测结论（重要）**：21 轮里 `deepseek-flash` 稳定 0.7~4.6s，而 `mimo-v2.6-flash` 中位 3~5s 但**有 14.0s / 25.6s / 51.3s / 58.3s 的长尾**（`tools=0`，不是工具；后端 preflight 仅 24ms~5.8s）→ **用户以为在跟 deepseek 比，实际慢的那些轮次跑的是 mimo。**<br>**④ 顺带排除了两个怀疑点**：前端测量与服务端 `CHAT_TTFB` 完全吻合（58s vs 58348ms），所以延迟不在前端/网络；SSE 帧比官方胖（每帧带若干 null 字段）属实，但一帧只多几百字节，**与 50 秒量级无关**。<br>测试 **658→660**，0 失败 |
| 2026-10-08（第四批） | **🔴 服务器容器起不来：`WebSearchTool` 多构造器无 `@Autowired`（→ 整个应用起不来）；顺带接入 Tavily `/extract` 与「搜索来源」下发** | 改 `WebSearchTool`（生产构造器补 `@Autowired`；新增 `sources()` 抽取结构化来源并交给 `ToolSourceStore`）、`SseResponseConverter`（+`takeSources`、新增兼容构造器）、`ChatServiceImpl`（接 `ToolSourceStore`）、`MessageVO.ToolResultVO`（+`sources`，`@JsonInclude(NON_NULL)`）、`AgentProperties.Websearch`（+extract 系列配置）、`GetModelListContractTest`（构造器加参）——**加构造器参数连带打挂既有测试这件事今天又踩了一次，详见下方 ⑤**；新增 `WebExtractTool`、`ToolSourceStore`、`BeanConstructorInjectionGuardTest`(2)、`WebExtractToolTest`(10)、`SearchSourceDeliveryTest`(5)，`WebSearchToolTest` +3；改 `docs/sse-contract.md`（§3.4 新增 `sources` 小节）、`docs/前端增量变更.md`（第四批） | **① 启动事故**：日志最后一行 `BeanInstantiationException: Failed to instantiate [WebSearchTool]: No default constructor found`，依赖链 `chatController ← chatServiceImpl ← chatContextFactory ← toolRegistry ← webSearchTool`。根因是 Spring 的构造器注入推断规则 —— **只有类"恰好有一个"构造器时才无条件用它做注入**；`WebSearchTool` 昨天为了让单测能注入显式 API Key 加了个包级可见的 3 参构造器，于是 2 个构造器、都没标 `@Autowired`，Spring 选不出就退回无参实例化，而它没有无参构造器 → refresh 失败、**整个应用起不来**。修法就是给生产那个补 `@Autowired`（**有效改动仅此一行**）。<br>**② 三道防线全瞎，这是最值钱的部分**：编译期加构造器不报错；单测 `WebSearchToolTest` **正是用那个新构造器 new 的**，6 条全绿；唯一加载 Spring 上下文的 `NexusAgentWebApplicationTests` 是 `@Disabled`（要真实 PG/Redis/模型 Key）。→ 一个 100% 起不来的版本带着全绿测试上了服务器。<br>**③ 护栏 `BeanConstructorInjectionGuardTest`**：不起容器，纯反射复查组件扫描命中的每个类，判据 = **既无无参构造器、也无 `@Autowired` 构造器、且构造器数 > 1 → 必然炸**。该判据**完备**（其余形态 Spring 都有唯一解），故不误报不漏报；第 2 条是反向验证（4 个嵌套样例断言好/坏形态判定正确）。**已实测证明它抓得住**：删掉 `@Autowired` → 立刻红并精确点名 `WebSearchTool（构造器 [3 参, 2 参]）`。<br>**④ 顺手全仓扫了一遍**：目前**只有** `WebSearchTool` 一个是这种形态，无同类隐患。<br>**⑤ 🔴 加构造器参数会连带打挂别的模块测试**（项目第 N 次踩）：给 `SseResponseConverter` 加 `ToolSourceStore` 参数后，6 个按位置 `new` 的既有用例编译不过。解法是补一个**不含新参数的兼容构造器**（该类由 `ChatServiceImpl` 用 builder 手工构造，不是 Spring Bean，不存在多构造器歧义）；而 `ChatServiceImpl` 是 `@RequiredArgsConstructor`，只能更新那 1 处调用点（`GetModelListContractTest`）以保持单构造器。<br>**⑥ `/extract` 为什么值得接**：Tavily 免费档 1000 credits/月是**所有端点共用的池子**，`/extract` 是「每 5 个**成功**提取的 URL 扣 1 credit」且**失败不计费** —— 拉 5 个网页正文和搜一次同价，是套餐里性价比最高的端点，所以默认单次 URL 上限取 5（恰好一个计费单位）。`/research` 单次最坏 250 credits（= 月额度 25%）且是异步接口，**本次明确不接**。<br>**⑦ 搜索来源走"带外信道"是因为没别的路**：工具由 LangChain4j 自动执行，结果到业务层时**已经是字符串**，拿不到原始结构；又不能把 JSON 混进返回值（那串要进模型上下文和历史消息，多一份就是白烧 token）。所以工具把结构存进 `ToolSourceStore`（按 sessionId + 工具名 FIFO），SSE 发 `tool_execution_result` 时取走。<br>**⑧ `sources[].index` 必须是 `int` 不是 `Long`** —— 否则被全局 Jackson 规则序列化成 `"1"`，与今天刚修的 `ttfbMs` 完全同源。<br>**⑨ 已知取舍（已写进契约）**：历史消息**没有** `sources`（库里只存了文本），刷新后来源卡片不回来。 <br>测试 **640→658**，0 失败，12 人工跳过 |
| 2026-10-08（第三批） | **补漏 `AttachedFileVO.fileSize` + 新增「Long 字段强制分类」护栏** | 改 `JacksonConfig`（+`ForAttachedFileVO`/`ForUser` mixin；**mixin 映射抽成 static `mixins()` 供运行时与护栏共用**）、`docs/前端增量变更.md`；新增 `LongFieldClassificationTest`(4) | **前端影响：`AttachedFileVO.fileSize` 补进 number 白名单（这是漏网的那个，见下）。**<br>**① 漏网**：我做第二批时扫出 domain+vo 共 **37 个 `Long` 字段**，而第一遍只改了 `SysFile.fileSize` / `KnowledgeFileVO.fileSize`，**漏了 `AttachedFileVO.fileSize`** —— 它是**历史消息里的附件**（`ChatMemoryServiceImpl` 反序列化 `attachedFilesRaw`），走另一条链路。而 frontend 清单里明确写了 `AttachedFile.fileSize`、已按 number 收窄 → **漏这个就是线上回归**。**同一语义散在两个类里，靠人记必漏。**<br>**② 护栏形态：不猜语义，强制显式分类。** `JacksonConfig` 的 Long→String 是一刀切，所以每个新 `Long` 字段都要做一次「主键 or 计量值」的判断，**而做错了没有任何提示**（编译过、测试绿、运行时静默下发另一种形态）—— 当天两轮踩的都是这个。护栏要求每个字段出现在两份清单之一：`MEASURED`(13，计量值，必须进 mixin) / `IDS`(25，主键类，保持字符串)。**不按名字猜**：`MessageVO.supersededBy` 是引用主键的外键但名字里没有 `Id`，按名判定必漏（它就在清单里）。<br>**③ 四条断言**：①每个扫到的 Long 字段都在清单里（核心，新增字段忘分类直接红）；②`MEASURED` 与运行时 mixin **双向一致**（防"清单写了没配"空转）；③主键类绝不许出现在 mixin 里；④清单无已不存在的字段（防腐化）。**已反向验证**：往 `MessageVO` 加一个 `tempUnclassified` → 测试立刻红并精确点名。<br>**④ 护栏自身也要防漂移**：`JacksonConfig.mixins()` 抽成 static 方法，运行时配置与测试共用同一份类名映射，避免测试里另抄一份又造出"两处各写一遍"的点。<br>**⑤ `User.tokenQuota`/`tokenUsed`/`fileQuota` 也进了 mixin**：目前不下发（login/register 只返回 token），但与 `QuotaVO` 是同源的配额数字 —— 哪天有人直接返回 `User` 实体，形态必须已经是对的（`User.id` 不在其中）。<br>**⑥ 反向验证的技术细节**：第一次拿 `Result` 加字段做验证，**编译就失败了** —— 它有 `@AllArgsConstructor`，加字段会改构造器签名，连带既有测试编译不过。改用 `@Builder` 的 `MessageVO`（builder 是具名方法，加字段不破坏调用点）才验证成功。<br>测试 **634→638**，0 失败，12 人工跳过 |
| 2026-10-08（第二批） | **🔴 `ttfbMs` / `seq` 被全局 `Long` 序列化误伤成字符串（契约承诺 number，实际下发 `"6667"` / `"7"`）** | 改 `SseResponseConverter`（`withTtfb` 写入 `int` + 封顶；计数器 `AtomicLong`→`AtomicInteger`）、`SseEvent`（`seq`：`long`→`int`）、`application.yml`（cors 加 dev 5174）、`docs/sse-contract.md`（新增 §1「数字形态」小节）、`docs/前端增量变更.md`（新增 1 节）；新增 `NumberFieldSerializationTest`(3)；`FrontendTelemetryTest` 3 处断言 `Long`→`Integer` | **前端需要同步（已写进 `docs/sse-contract.md` 与增量文档）：`ttfbMs`、`seq` 从字符串变回 number（契约一直如此承诺），**需重新部署后端生效**；前端可摘掉 `Number()`/`parseInt()` 兜底。**<br>**① 谁发现的**：frontend 在**真实链路**（真实账号 + 真实后端，非 mock）用 fetch 拦截器抓原始 SSE 帧，拿到 `{"status":"DONE","ttfbMs":"6667","contextWindow":300000,…}` —— **同一帧里 `context*` 是裸数字、`ttfbMs` 带引号**，这才是能一眼定位的形态。<br>**② 根因**：`JacksonConfig`（10-05 为雪花 ID 精度）把**所有** `Long`/`long` 序列化成字符串。规则本身对，但它**一刀切**：`ttfbMs` 是 `Long`（需 `null` 表示未测到）、`seq` 是 `long`，都被算进去；`context*` 是 `int`/`double` 所以没事。**差别只在类型，不在有没有下发** —— 这就是它长期没被发现的原因。<br>**③ `seq` 的失效方式是静默的，比 `ttfbMs` 更危险**：契约明确要求前端拿它做跳号检测，而字符串形态下 `cur === prev + 1` 恒为 false —— **功能看着在、其实早就瞎了**。这是最坏的一类失效。<br>**④ 改法刻意绕开而非拆掉**：删 `JacksonConfig` 会让雪花 id 变裸数字，「每一行点删除都报文件不存在」立刻复发。所以只是让**计量字段用 `int`** 躲开那条规则。<br>**⑤ 🔴 护栏必须真的跑一遍序列化**：原 `FrontendTelemetryTest` 断言 `assertEquals(1820L, data.get("ttfbMs"))` —— 断言的是 **Map 里的 Java 对象**，那一刻**序列化还没发生**，「值对了、形态错了」在断言里完全不可见。新增 `NumberFieldSerializationTest` 用**与运行时同源的** `JacksonConfig` customizer 建 ObjectMapper 真序列化，断言**方向相反的两条**：`seq`/`ttfbMs` 必须是 number、`MessageVO.id`（19 位）必须仍是 string（守原规则的意图，防止有人为修前者把全局规则删掉）。**已反向验证**：改回 `Long`/`long` → 3 条全红，报错原文即 `ttfbMs 下发成了 "6667"`、`seq 下发成了 "1"`，与实测形态一致。<br>**⑥ 副产物：dev CORS 加 5174**。frontend 发现 Vite 代理转发时浏览器带的是 `Origin: http://localhost:5174`（5173 被占用自动顺延），后端只放行 5173 就回 403 —— **而 `curl` 不带 `Origin`，所以平时永远测不出来，真实浏览器一跑才现形**。代理侧改写 Origin（等效 `changeOrigin: true`）是正统解，后端把 dev 端口列进名单是兜底。<br>**⑦ 第二批已改齐（同日 10:45）**：frontend 按前端类型定义给了清单，`fileSize` / `size` / `total` / `quota` / `used` / `remaining` / `fileQuota` / `fileUsed` / `fileRemaining` 全改成 number，**`id` 类一个不动**。**实现方式是 `JacksonConfig` 的 mixin 白名单，不改 Java 类型**（`fileSize` 是字节数，改 `int` 要赌 2GB 边界；配额字段改类型要连带动调用方）。**方向刻意保持「默认字符串、显式列出的才是数字」** —— 两种失败模式的代价不对等：漏标计量值 → 该字段还是字符串（前端本来就 `string | number` 双兼容，不坏）；反转默认值 → 某个 id 变裸数字 → 19 位精度被 JS 抹掉 → 该接口的 id 操作**静默失效**（正是"文件删不掉"那类）。**让失败模式落在安全那一侧。** `artifact.size` 另需运行时归一：它塞在 `Map<String,Object>` 里，**Map 的值按运行时类型挑序列化器、类级 mixin 管不到**，沙盒返回的 JSON number 小值是 `Integer`（恰好没事）、大值是 `Long`（变字符串），同一字段形态会随文件大小漂移 —— 统一成「能进 `int` 就用 `int`，否则 `BigInteger`」。踩坑：`@JsonSerialize(using = NumberSerializer.class)` 会在**运行时**抛 `InvalidDefinitionException: NumberSerializer has no default (no arg) constructor`（它只接受 `Class` 参数，**编译期完全看不出**），已改用自定义 `LongAsNumberSerializer`。护栏扩到 5 条，含「mixin 声明的 getter 必须在目标类上真实存在」—— mixin 按方法名匹配，**写错名不报错、只静默失效**。两批均做反向验证：去掉 `SysFile` 的 mixin → `fileSize 要 number … 实际 "20971520"` 立刻变红。<br>测试 **632→634**，0 失败，12 人工跳过 |
| 2026-10-08 | **🔴 `GET /api/file`（文件列表）稳定 500：`@TableField(exist=false)` 字段顶掉了「按列序」的构造器映射** | 改 `SysFile`（补 `@NoArgsConstructor` + `@AllArgsConstructor`，并给 `failCode` 补事故复盘注释）；新增 `EntityConstructorMappingGuardTest`(1)、`SseContractDocDriftTest`(2)；改 `docs/sse-contract.md`（§2 一览 + §3.6/§3.7/§3.8 补齐收尾公共字段）、`docs/前端增量变更.md`（新增 1 节） | **行为有变、契约无变（已写进 `docs/前端增量变更.md`）：`GET /api/file` 从「稳定 500」恢复 200；路径/字段/错误码一字未改，前端若为它写过兜底可撤掉。**<br>**① 现象**：`ResultMapException: Error attempting to get column 'upload_status'` → `No enum constant UploadFailCode.SUCCESS`，栈指 `FileServiceImpl.getFileByUserId:174`（每次必现）。<br>**② 根因是三件事凑齐，与枚举本身无关**：`SysFile` 只有 `@Builder` 生成的全参构造器（**无无参构造器**）+ `failCode` 标了 `@TableField(exist = false)`（**不参与 SQL 列**）→ MyBatis 退化成 `applyColumnOrderBasedConstructorAutomapping`，**按结果集列顺序**喂构造器参数。SELECT 出来 12 列、构造器 13 个参数，第 7 列 `upload_status='SUCCESS'` 被喂给第 7 个参数 `failCode` → 当场抛异常。`failCode` 是 2026-10-07 上传那次插进 `SysFile` 的，**恰好插在 `uploadStatus` 前面**，把它之后所有字段的位置全顶掉。<br>**③ 护栏选得不放宽也不收紧**：断言「带 `exist=false` 字段的实体必须有无参构造器」。这条在 MP 自动 SQL 场景下是**完备**的 —— MP 生成的列顺序严格按实体字段声明顺序，而 `exist=false` 字段不生成列，所以「字段顺序 == 列顺序」**当且仅当**「没有 `exist=false` 字段」；不会多报（其余 9 个 domain 实体都无无参构造器，但都不含该字段，安全）也不会漏报。<br>**④ 修法是止血不是治本**：真正的治本是把这类**纯传输字段从实体挪到 VO**（属待办技术债）。加 `@NoArgsConstructor` 让 MyBatis 回到按名映射是零风险且立刻生效的做法。<br>**⑤ 同一份日志（`Agent_Back-20261008092751.log`）还澄清了首字口径 —— 这条对排查方向更值钱**：`ttfb=86058ms` 那轮的 `RUN ... tools=5 seq=[create_box,execute_code,execute_code,publish_artifact,delete_box]` 说明**首个文本 token 要等工具全跑完**，不是模型卡住更不是后端阻塞；而 `ttfb=19976/20516ms` 那两轮对照 `CHAT_PREFLIGHT.total=1376/1137ms`，**模型侧占 18.6s / 19.4s**，同 6 轮里另外 4 轮模型侧只有 1.3~3.4s —— 抖动来自上游供应商，不在本仓库。后端另有**周期性**尖刺：`CHAT_CONTEXT ... mcp=3902ms`、`skillResolve=958/1339/1103ms`（其余轮次 0~2ms），是 MCP 工具列表 / 技能目录 TTL 缓存过期重建，属**待优化**项。<br>**⑥ 又一次印证「前端数条数」这条路走不通**：本次 `window=300000`、`contextUsed≈3.7k`（`contextRatio≈0.012`）、历史 23 条，用户却收到「聊了很多轮」的提示。后端 2026-10-06 已下发 `contextWindow`/`contextUsed`/`contextMsgs`/`contextRatio`，已再次要求前端改用 `contextRatio >= 0.8`，并按用户要求给出兜底：「做不到就先把这个提示关掉」。<br>**⑦ 🔴 同日补上的真正堵口 —— 契约漂移（比这个 500 更值钱）**：frontend 回信确认「`context*` 四个字段**一个都没接**」，根因是 **`docs/sse-contract.md` 里根本没有它们** —— 连 10-06 加的 `ttfbMs` 也没有。10-06 那次只写进了 `docs/前端增量变更.md`，而**那是一份按时间追加的流水账，不是契约**；前端是「按文件接」的，搜不到自然接不上 → 长会话引导至今还在数条数，于是有了本次的误报。已把 5 个字段补进 `sse-contract.md`（新增 §3.6「收尾公共字段」，`stopped`/`error` 引用同一节而非各写一遍，避免将来三处漂移）并作附件同步给 frontend。新增 `SseContractDocDriftTest`：**从 `SseResponseConverter` 正则抽出所有 `data.put("context*"/"ttfb*")` 字段名，逐个断言在 `sse-contract.md` 里找得到** —— 这类「实现改了、契约没改」的断链不再靠人自觉。<br>**教训（写进 §8.1 那一类规矩的升级版）：项目里存在两个交付口径时，只有契约算数，写进流水账等于没写。**<br>测试 **627→629**（新增 2），0 失败，12 人工跳过 |
| 2026-10-07（晚 7） | **`GET /api/chat/model` 兼容入口：加了又撤回（不留死代码）** | 改 `ChatController`（+`getModelListLegacy` 后又删除）、`docs/部署指南（改配置不重打包）.md`（新增 §七「三个密钥」）、`docs/前端增量变更.md`；登录线按 frontend 拍板不改代码 | **无（已撤回）** —— frontend 确认一直用的 POST。<br>**① 教训**：破坏性契约变更（10-05 GET→POST）当时**没留过渡入口**，这是个疏漏；但补兼容入口前**先问一句**更省事 —— frontend 早已迁完，加了就是纯死代码。<br>**② 撤回流程本身值得记**：加了兼容入口 → 打 WARN 观察 → 确认无调用方 → 删除 + 文档标注撤回。兼容入口不是永久 API，是有生命周期的东西。<br>**③ 部署指南补 §七「三个密钥」**：`API_KEY_SECRET`（凭据主密钥，**必须永久固定** —— 漂移会让所有用户的已存 Key 全部解不开，而现象只是厂商回一句 401）、`JWT_SECRET`（不配则每次重启全员被踢下线）、`TAVILY_API_KEY`（可选）。含「主密钥指纹」自检法与 401 排查三步。<br>**④ 登录线闭环**：frontend 拍板用户名保持 `妖怪_xxxxxx`（技能卡署名公开可见，邮箱前缀会泄漏邮箱片段），我被说服不改；已 done 收工。
| 2026-10-07（晚 6） | **🔴 自带 Key 不再被平台 token 配额拦 + 配额超限改走 SSE `error`（前端不再只看到「服务异常」）** | 改 `ChatContextFactory`（+`usesUserProvidedModel`，复用 `matchModel` 同源判定）、`ChatServiceImpl`（`assertWithinQuota` 移到 `writer.start()` 之后、仅平台 Key 时执行、超限走 `writer.onError`）；新增 `ChatContextFactoryOwnKeyTest`(4)；`docs/前端增量变更.md` | **前端需要同步（已写进 `docs/前端增量变更.md`）：① 自带 Key 时不再校验平台 token 配额（文件/产物配额仍生效）；② 配额超限时 SSE 会先发 `run` 再发 `error`，前端请展示 `data.message` 原文，不要一律替换成「服务异常，请稍后重试」。**<br>**① 用户原话「为啥用自己的模型还报额度没了的错」** —— 以前 `chat()` 开头**无条件**校验平台配额，而自带 Key 的费用由用户自己的供应商账号承担，平台拿平台额度拦他毫无道理（额度是他之前用系统模型耗掉的）。<br>**② 判定必须与「实际用谁的 Key」同源**：复用 `matchModel` 而不是另写一套 —— 两处口径一旦分叉，就会出现「判定放行、实际走平台 Key」的漏拦，等于白送平台成本且更难查。查库异常时**保守按平台 Key 处理**（不白放行）。<br>**③ 🔴 「服务异常，请稍后重试」的根因**：`/api/chat/stream` 是 SSE 请求，而 preflight 的业务异常经 `GlobalExceptionHandler` 变成 **JSON** 返回；前端用 `EventSource` 只解析 SSE 帧，拿不到 msg 只能兜底成通用文案。→ 配额校验移到首帧**之后**，超限时用 `writer.onError(e)` 发 `error` 事件，msg 是给人看的具体原因。<br>**教训**：SSE 接口上的 preflight 异常**不能**用 JSON 错误响应兜 —— 那等于把可读原因扔掉，所有错误都退化成"服务异常"。凡是 SSE 链路上的业务拒绝，都要走 SSE 事件下发。<br>测试 **621→625**（新增 4），0 失败 |
| 2026-10-07（晚 5） | **🔴 产物打不开复发（png/jpg/docx 坏、svg/md/html/csv 正常）+ 新增联网搜索工具** | 沙盒：`file.py` 下载响应加 `binary_read: true` 版本标志；改 `BoxTool`（`requireBinaryRead` + `MAGIC_BY_EXT` 魔数校验，构造器 +`AliOssUtil`）、`AliOssUtil`(+`readObjectHead` Range 读)、`AgentProperties`(+`Websearch`)；新增 `tools/WebSearchTool`（Tavily，`web_search`）；`BoxToolPublishQuotaTest`(构造同步)、新增 `BoxToolArtifactGuardTest`(6)、`WebSearchToolTest`(6)、`docs/前端增量变更.md`、`AGENTS.md` | **① 用户症状「png/jpg/docx 打不开、svg/md/html/csv 正常」是「二进制被按文本处理」的精确特征**（文本无非法字节怎么解码都不坏）—— 10-05 修过一次（E2B SDK `format="text"`），但修复在**沙盒侧**，线上沙盒跑旧代码时 Java 侧无从察觉（响应形状一模一样 `{url,size}`）。**用户必须重新部署 nexus_agent_box（必要时重建 E2B 模板）**，这是本次修复能生效的前提。<br>**② 防线只有一道：发布前读 OSS 对象头校验魔数**（png/jpeg/gif/webp/bmp/pdf/docx/xlsx/pptx/zip/doc/xls/ppt），不匹配 → `SANDBOX_ARTIFACT_CORRUPTED` + 尽力删掉坏对象。文本类不校验（没有魔数，误校验必炸）；读不到对象头放行（OSS 抖动不该挡发布）。
🔴 **同批还撤回过一个设计**：原先把「沙盒响应缺 `binary_read` 标志」当成硬失败（`SANDBOX_CODE_OUTDATED`）拒绝所有下载 —— **错在 web 与 box 独立部署、升级节奏不同步**，只更新 jar 就会让**所有**下载全挂，而那些 csv/md/html 本来完全没问题。现改为**只打 WARN 日志**做诊断，拦截只交给魔数校验（它才是针对性防线）。诊断信息也不浪费：魔数失败时若确认缺标志，hint 里直接给出 box 的重建命令。<br>**③ 读不到对象头放行不拦截**：OSS 抖动不该挡发布，下载链路自己会暴露。<br>**④ web_search 走 Tavily**：Key **只走环境变量 `TAVILY_API_KEY`**（铁律 4，刻意不提供 yml 配置项）。**没配 Key 时整个工具集不注册** —— 模型看不到这个工具，而不是调用了才报错；"看得见却用不了"的工具会让模型反复空试。<br>**⑤ 🔴 又一次构造器加参的连带**：BoxTool +`AliOssUtil` 后 BoxToolPublishQuotaTest 编译失败已同步；且 mock 的 downloadFile 响应必须补 `binary_read`，否则新校验会把既有用例全部判成旧沙盒。<br>测试 **609→621**（新增 12），0 失败 |
| 2026-10-07（晚 4） | **`POST /api/chat/model` 降级不再静默**：新增 `ModelListResult`（live/reason），401 单独定性打 ERROR | 新增 `dto/ModelListResult`；改 `ChatService`(+`getModelListWithMeta`，旧方法保留)、`ChatServiceImpl`（降级分支重写 + `looksLikeAuthFailure`/`shorten`）、`ChatController`（降级时把原因写进 `Result.msg`）；`GetModelListContractTest`(+2)、`docs/前端增量变更.md` | **前端需要同步（已写进 `docs/前端增量变更.md`）：`data` 仍是 `List<String>` 完全不变；降级时 `msg` 会写明「厂商 /v1/models 不可用，当前返回的是配置中已保存的模型名」+ 原因。前端可选：据此提示"列表可能是旧的"，不读也不影响。**<br>**① 用户问「这个接口获取的模型不对，没有向供应商请求吗？」——**后端确实请求了厂商 `/v1/models`**，但失败时是**完全静默降级**（只打一行 WARN 就返回库存的旧模型名），页面上看不出任何异常，于是只能猜。<br>**② 401/403 必须单独定性**：以前它与「厂商不支持 /v1/models」一样被吞成 WARN，而后者是常态（DeepSeek、小米都不实现该接口）。现在 401 打 **ERROR** 并附排查顺序（Key 是否有效 → 主密钥是否漂移 → 比对主密钥指纹）。<br>**③ 兼容设计**：元信息只进 `msg`，`data` 结构一字不动 —— 前端不读 msg 也不会坏。<br>测试 **607→609**（新增 2），0 失败 |
| 2026-10-07（晚 3） | **🔴 线上 401 Invalid API Key：主密钥漂移会让解密静默产出乱码，并被当成 Key 发给厂商** | 改 `EncryptorFactory`（新增 `decryptChecked` + `fingerprint` + 主密钥指纹启动日志）、`ChatContextFactory`、`ChatServiceImpl`、`UserConfigServiceImpl`、`LexiangServiceImpl`、`McpInformationServiceImpl`（6 处解密全部换成带校验版）；新增 `EncryptorFactoryDecryptTest`(5) | **无前端契约变更**（报错文案变了：解密异常现在是明确的中文原因，不再是厂商那句 401）。**<br>**① 用户报「没改 Key / 换了个新 Key 还是 401」。根因隐患：Spring 的 `Encryptors.text()` 是 AES-CBC + 随机 IV，**解密不校验完整性** —— 主密钥与加密时不是同一把时它**不抛异常**，而是解出一串乱码；乱码被原样发给厂商，厂商只回 401，真实原因被完全掩盖。<br>**② 所以 `decryptChecked` 在解密后校验形状**：凭据必须是可打印 ASCII（各家 API Key 都是字母数字+少量符号），含 U+FFFD 或非可打印字符即判定为乱码并抛带原因的异常 —— **绝不让乱码流出网**。<br>**③ 启动日志新增「主密钥指纹」（SHA-256 前 12 位）**：用于比对「保存凭据时」与「使用凭据时」是不是同一把密钥，没有指纹时这类问题只能靠猜。<br>**④ ⚠️ 排查 401 的第一步是看 `CHAT_DECISION` 那行**：`模型=用户自带:xxx` 才走加密链路；`模型=系统默认(用户未配置该模型)` 走的是 **系统内置模型的环境变量 Key（`${DEEPSEEK}` 等）**，此时换自带 Key 当然无效。<br>**⑤ 反向验证**：用 A 加密、换 B 解密，测试必须变红（`wrongMasterKeyMustFailLoudly`）。<br>测试 **602→607**（新增 5），0 失败 |
| 2026-10-07（晚 2） | **🔴 工具 `id` 不可用作列表 key**：`tool_execution` 新增 `index`；历史侧空 id 兜底合成（frontend 踩坑后根治） | 改 `MessageVO.ToolRequestVO`（+`index`）、`SseResponseConverter#writeToolRequestWithStream`（下发 index，`sendRequest` 加重载）、`ChatMemoryServiceImpl`（+`ensureToolIds`/`syntheticId`）；`docs/sse-contract.md` §3.3/§3.4、`docs/前端增量变更.md`；新增 2 条用例 | **前端需要同步（已写进 `docs/前端增量变更.md`）：`toolRequestList[]` 新增可选 `index`（仅 SSE 有，历史没有）；历史里工具 id 为 null 时后端合成 `row-<行id>#<序号>`。**<br>**① frontend 报「点历史记录后整个应用卡死」，根因是他用 `call.id` 做 `v-for` key：id 缺失/重复 → key 撞车 → Vue patch 拿到 null el → `Cannot set properties of null (setting '__vnode')`。**<br>**② 🔴 我核实后确认这是后端契约缺陷不是前端粗心**：`PartialToolCall.id()` 由供应商给、后端原样透传从未加工 —— 流式帧除首帧外 id 常为 null，某些兼容层完全不回传，同一批多条全是 null。**而 `PartialToolCall.index()` 一直存在、首帧就有、同批唯一，我们却从没下发。** 契约里"只是稀疏"这种措辞不够，已改成"可能缺失，也可能重复 —— 不要用 id 做 key"。<br>**③ 历史侧为什么必须兜底**：历史消息是 langchain4j 序列化的，`ToolExecutionRequest` 不含 index，只能合成 id。形如 `row-1002#0`，**带行主键所以全局唯一**（前端把不同消息的工具卡铺进同一个列表也不撞）。⚠️ 只补展示层，不动 `chat_memory` —— 动了会破坏模型侧的配对语义。<br>**④ 另一条易漏**：工具调用请求挂在 **AI 行的 `toolRequestList`** 上，不是独立的 `TOOL_EXECUTION` 类型行；frontend 以前只认后者 → 历史里工具卡丢参数、只剩孤儿结果。<br>测试 **600→602**（新增 2），0 失败 |
| 2026-10-07（晚） | **内部工具调用不再下发给前端（工具可见性）**：5 个基建工具照常执行，但不推 SSE 事件、不进历史 | 新增 `tools/ToolVisibility`；改 `AgentProperties.Tools`（+`hiddenTools`）、`SseResponseConverter`（+字段与 2 处过滤）、`ChatServiceImpl`（传参）、`ChatMemoryServiceImpl#toMessageVO`（历史过滤）、5 个既有测试（补构造参数）；`docs/sse-contract.md` §3.3/§3.4、`docs/前端增量变更.md`；新增 `ToolVisibilityTest`(6)、`ToolVisibilityDriftTest`(2)、`SseToolVisibilityTest`(5)、`HistoryToolVisibilityTest`(5) | **前端需要同步（已写进 `docs/前端增量变更.md`）：事件与历史结构一行未改，只是 `create_box` / `delete_box` / `search_user_memory` / `save_user_data` / `record_log` 这 5 个工具不再出现；清单可配，前端不要硬编码工具名。**<br>**① 🔴 只过滤展示层，绝不动存储层**：工具调用与结果必须留在 `chat_memory` —— OpenAI 兼容协议要求 `tool_calls` 后面必须跟配对 id 的 `tool_result`，从记忆里删掉任何一半，下一轮请求会被供应商直接拒（400）。所以过滤点只有 `SseResponseConverter#sendRequest` / `#writeToolResult`（实时流）与 `ChatMemoryServiceImpl#toMessageVO`（历史）两处，存储层一个字没动。<br>**② 只在 SSE 屏蔽是不够的**：刷新页面历史会把藏起来的卡片原样带回，表现为「当时看不见、刷新又有了」，比不屏蔽更让人困惑。历史过滤发生在**读取时**，`chat_memory` 里的行不动。<br>**③ 隐藏工具执行期间画面静默是预期行为**（用户拍板：不加「处理中」事件），连接靠 15s 心跳注释帧保活；前端不要把「收到工具事件」当唯一加载信号。<br>**④ 并行调用要逐项过滤**：一条 `AiMessage` 可能带多个 `tool_calls`，只剔隐藏的那些；**整条只剩隐藏工具（无正文、无思考）时才整条不返回**，否则前端会渲染出一个空气泡。<br>**⑤ 继承铁律 10**：`hiddenTools` 是 List，yml 里声明会**整份取代**默认清单而不是合并 —— 配了 `ToolVisibilityDriftTest` 盯住任何 yml 不得声明该键（与 `SseTimeoutDriftTest` / `MemoryWindowDriftTest` 同款）。<br>**⑥ 改构造器签名的连带成本又一次出现**：加 final 字段后 5 个既有测试编译失败（它们直接调全参构造），已逐一补 `null` / mock 并注明「本用例不涉及可见性」—— 与同日上传那次是同款教训。<br>测试 **577→595**（新增 18），0 失败 |
| 2026-10-07 | **上传接口：类型不支持不再静默消失 + 单次个数上限 10**（agent-mail 与 fronted 对齐后实现，他拍板「a 选 A、b 要」） | 新增 `em/UploadFailCode`、`FileServiceUploadRejectTest`(5)；改 `FileServiceImpl#uploadFile`（`continue` → 逐条 `FAILED` + 个数校验 + 注入 `AgentProperties`）、`SysFile`(+`failCode`，`@TableField(exist=false)` **不入库**)、`KnowledgeFileVO`(+`failCode`)、`FileTypeUtils`(+`supportedExtensionsText`)、`AgentProperties`(+`Upload.maxCount`=10)、4 个 `FileService*Test` 与 `SnowflakeIdPrecisionTest`(构造参数同步)、`docs/前端增量变更.md` | **前端需要同步（已写进 `docs/前端增量变更.md`）：`POST /api/file` 返回数组新增 `failCode`（机器可读失败码，成功为 null）；类型不支持**不再从列表里消失**，改为返回一条 `uploadStatus=FAILED` 记录；单次 > 10 个整批拒绝（200 + `code=1`）。**<br>**① 用户反馈「什么都能传很离谱」的根因<b>不在前端</b>**：原实现 `if (!isSupportedDocument) continue;` —— 文件既不入库也不报错，返回列表里直接没有它，前端拿不到任何失败信号。前端加 `accept` 只是掩盖，后端本就该拒绝。改为**逐条标记失败**（fronted 选的 A 方案：坏文件不毁整批），与 OSS 失败的既有结构一致。<br>**② `failCode` 是为 fronted 的验收要求「必须能区分『类型不支持』与『超出配额』」加的** —— 只靠 `failReason` 中文文案区分不可靠（文案会改/会本地化），而两者处理方式完全不同（换格式 vs 清理空间）。⚠️ 它与 `UploadStatus` 是两个维度，不要合并。<br>**③ `failCode` 刻意 `@TableField(exist = false)` 不入库**：它只服务当次响应，落库没有意义，加列要付迁移成本（铁律 6）。列表页渲染用 `failReason`（给人看），分支判断用 `failCode`（给代码看）。<br>**④ 个数上限放在配额校验<b>之前</b>**：这是请求级问题，没必要为一个必然失败的请求先查配额。<br>**⑤ 反向验证已做**：把实现改回 `continue`，`FileServiceUploadRejectTest` 两条用例立刻变红（`expected: <1> but was: <0>` / `<3> but was: <2>`）。<br>**⑥ 教训**：改构造函数签名会连带 5 个测试编译失败（它们直接 `new FileServiceImpl(...)` / `new KnowledgeFileVO(...)` 全参构造）—— `@AllArgsConstructor` 加字段是**破坏性**的，改完要跑全量而不是只跑相关模块。<br>测试 **572→577**（新增 5），0 失败 |
| 2026-10-06 | **🔴🔴 fix：1M 上下文的模型实际只拿到 2.4 万 token 窗口（prod 残留 `max-tokens: 24000` 盖掉代码默认 300000）+ 前端「聊几次就提示新建会话」误导**；顺带给前端下发真实上下文用量信号 | 改 `application-prod.yml`（删残留 `max-tokens: 24000`）、`application-dev.yml.example`（删同款残留 `100000`）、`context/ContextUsage`（**新增**）、`ChatContext`(+`contextUsage`)、`ChatContextFactory`（估算器改为建一次共用 + 建 `ContextUsage`）、`PgChatMemoryStore`（`forRun` 新增 usage/estimator 参数、加载后回填用量）、`SseResponseConverter`(+`markContextUsage`/`withContext`)、`ChatServiceImpl`（`chat()` 返回后登记快照）、`ProdLatencyGuardTest`（**修正测错对象**）、`ModelCapabilities`/`AgentProperties`（注释订正）、`AGENTS.md` 配置表、`docs/前端增量变更.md`、`docs/模型能力配置（前端）.md`；新增 `MemoryWindowDriftTest`(3)、`ContextUsageSignalTest`(4) | **前端需要同步（已写进 `docs/前端增量变更.md`）：① `finish`/`stopped`/`error` 三事件 `data` 新增可选字段 `contextWindow`/`contextUsed`/`contextMsgs`/`contextRatio`；② 🔴「长会话引导」必须**停止按 12 条消息触发**，改用 `contextRatio >= 0.8`。**<br>**① 根因与 2026-10-05 的「2 分钟被掐断」完全同构**：当天上午压窗口时把 `AgentProperties.Memory.maxTokens` 从 100000 调到 24000，下午证伪后**只把 Java 默认值改回 300000，`application-prod.yml` 里那行没删** —— profile 属性源优先级高于代码默认值，而 `active: prod`。结果 1M 上下文的 `mimo-v2.6-pro/flash` 生效窗口 `min(24000, 1000000−32768)` = **24000（声明值的 2.4%）**，`deepseek-flash` 同样 24000。用户反馈原话：「现在很多模型都是 1M，对话几次就让我 new 窗口，怎么可能那么快」。<br>**② 🔴 更值得记的是：当时三个护栏测试全绿**。`ProdLatencyGuardTest` 用 `new AgentProperties()` 的**代码默认值**去算生效窗口，**从不读 prod 里写了什么** —— 读配置文件的测试却读默认值，等于护栏测了个寂寞。已改为用 prod 实际声明值；报错也拆成「卡在全局天花板」与「卡在模型元数据」两种（修法完全不同，混在一起会把人带沟里 — 反向验证时亲历）。<br>**③ 第二处漂移是新增护栏自己扫出来的**：`application-dev.yml.example` 里还残留 `max-tokens: 100000`。与 `sse.timeout` 同一治理方式：**该键禁止出现在任何 profile yml，唯一事实源收归 `AgentProperties.Memory.maxTokens`**，由 `MemoryWindowDriftTest` 盯（已反向验证：把 24000 塞回去，两条护栏都变红）。<br>**④ 前端误报的第二层原因是结构性的**：它没有「真实用量」可看，只能数消息条数，而条数与占用完全不成正比（一轮带工具调用的 agent 对话能顶几十轮闲聊）。所以补了 `ContextUsage`：记忆存储加载历史后回填条数与估算 token，随收尾事件下发。⚠️ 口径是**加载量（裁剪前）**而非发送量 —— 加载量才是「这个会话积累了多少」，裁剪后永远贴着窗口、看不出趋势；且 `ratio > 1` 正好就是「本轮已开始丢更早历史」的信号。<br>**⑤ 时序坑**：`ContextUsage` 必须在 `chatAssistant.chat()` **返回之后**再登记 —— 历史是 LangChain4j 在 `chat()` 内部同步加载的，读早了是空壳（全 0）。<br>**⑥ 顺带**：估算器原来每次请求 new 两次（给 `TokenWindowChatMemory` 一个、记忆存储一个），BPE 词表冷启动成本付两遍；现在共用一个实例。<br>**⑦ 文档订正**：`docs/模型能力配置（前端）.md` 里 65536/16384 与「全局上限 24000」都是已推翻的旧结论，已改为 131072/32768 与 300000，并写明「填成 1M 也不会真用满，生效值被压到 30 万」（有意兜底，不是 bug）。<br>测试 **565→572**（新增 7），0 失败 |
| 2026-10-06 | **用户分级配额（NORMAL / TEST / VIP）+ 长期记忆写不进库的修复 + 首字延迟治理** | 新增 `em/UserRole`、`docs/sql/012`、`FileMapper#countSince`、`BoxToolPublishQuotaTest`(3)、`FileServiceFileQuotaTest`(1)；改 `User`（+`role`/`fileQuota`）、`QuotaService(+Impl)`、`FileServiceImpl`、`UserServiceImpl`、`BoxTool`、`MemoryTool`、`UserMemoryService(+Impl)`、`QuotaVO`、`AgentProperties`、`application-prod.yml`、`QuotaServiceTest`(+11)、`ToolFailureContractTest`(+1)、`docs/前端增量变更.md` | **前端需要同步（已写进 `docs/前端增量变更.md`）：`GET /api/user/quota` 纯增量新增 `role` / `fileQuota` / `fileUsed` / `fileRemaining` / `fileUnlimited` 五个字段；上传超限返回 HTTP 200 + `Result{code=1}`（msg 已带「已用/上限/明天 00:00 重置」）。**<br>**① 长期记忆「接口说 ok、库里啥也没有」根因**：`MemoryTool#saveLongMemory` 用 `UserContextHolder.getUserId()`，而工具跑在**流式回调线程**，ThreadLocal 恒为 null → `user_id=null` → 撞 `user_memory.user_id NOT NULL`；**而 `UserMemoryServiceImpl#saveMemory` 带 `@Async`，异常被线程池吞掉** → 工具照常返回 `"ok"`。修复三件套：改用 `RunUserRegistry` 反查（`@ToolMemoryId`）、**去掉 `@Async`**（记账类异步必须有补偿，这里没有）、`saveMemory` 开头对 `userId==null` 直接抛 `UnauthorizedException`。顺带把工具失败文案改成带原因的人话（原来 `"错误，请勿重复"+null`）。<br>**② 顺手修了同款 ThreadLocal 坑**：`BoxTool#download_file` 与 `publish_artifact` 也在工具线程里读 `UserContextHolder` → 传给沙盒的 `user_id` 恒为空，OSS 对象不归属任何用户。已改为 `RunUserRegistry` 反查。<br>**③ 配额拦截点必须选对**：文件（用户上传）拦在 `FileServiceImpl#uploadFile` 的**最前面**（OSS 之前）；产物拦在 **`BoxTool#publish_artifact` 调用 `sandboxClient.downloadFile` 之前** —— 一旦走完 downloadFile，文件已经生成并上传，再拦只能拦住「落库 + 下载卡片」，对象会变成 OSS 孤儿。工具侧返回**结构化失败**（`errorCode=QUOTA_EXCEEDED` + hint 让模型别重试、别用 `download_file` 绕开）而不是抛异常，这样模型能转述给用户。<br>**④ 角色档位**：`NORMAL` 每日 100 万 token / 100 个文件；`TEST` 每日 1000 万 / 不限文件；`VIP` 预留 1000 万 / 1000 个。注册按 `nexus.agent.quota.default-role`（默认 `NORMAL`）写 `role` + `token_quota` + **`token_period=DAILY`** + `file_quota`。⚠️ `token_period` 必须显式写 DAILY：全局默认 `period=NONE`（累计不重置）会让「每天 100 万」变成「一辈子 100 万」。原 `default-quota` 降级为兜底，生产别再依赖。<br>**⑤ `UserRole` 编译坑**：枚举常量的实参里用**简单名**引用本类后声明的 `static final long` 属「非法前向引用」，必须写全限定名 `UserRole.FILE_UNLIMITED`（值是编译期常量，加限定不会读到 0）。<br>**⑥ 测试教训**：Mockito 对 **`Map` 返回值默认给空 Map 而不是 null**，而 `BoxTool` 的判据是 `blocked != null` → 不打桩就会被空 Map 当成「命中拦截」直接返回；必须显式 `when(guard.intercept(...)).thenReturn(null)`。另 `MockitoExtension` 严格模式下，把「配额拦截」用例塞进已有测试类会因 setUp 里的 `saveBatch` 桩用不上而报 `UnnecessaryStubbing`，独立成类更干净。<br>**⑦ 首字延迟**见上一条（commit `02cf00c`）。测试 **505**（新增 15），0 失败，12 人工跳过 |
| 2026-10-06 | **首字延迟治理（第二轮）：记忆窗口大幅收紧 —— 主因不是 CPU/DB，是发给模型的 prompt 太大** | 改 `AgentProperties.Memory`(maxTokens 100000→24000、新增 `maxHistoryMessages`=200)、`ModelCapabilities`(DEFAULT_CONTEXT_WINDOW 256000→65536、DEFAULT_MAX_OUTPUT_TOKENS 32000→16384)、`PgChatMemoryStore`(改用限量查询 + `CHAT_MEMORY` 埋点)、`ChatMemoryService(+Impl)`、`ChatMemoryMapper(.xml)`(新增 `getRecentForChat`)、`ChatContextFactory`(日志加 window/ctx/out/tools)、`application-prod.yml`(**新增 logging 段覆盖为 info**、修 `deepseek-flash` 元数据 1000000/384000→65536/8192、max-tokens→24000)、`docs/模型能力配置（前端）.md`、`docs/前端增量变更.md`；新增 `ProdLatencyGuardTest`(3)、`ChatMemoryRecentForChatTest`(2)、`ModelCapabilitiesTest`(+1 护栏) | **① 排查方法（值得复用）**：先用 `jshell`/`javac` 跑一个独立基准量化各段 —— 结果 `AiServices.build` 1ms、200 条历史的 BPE 估算 52ms、本地前置合计仅 ~100ms 量级 → **4 秒不在本地 CPU**。真正的量在「发给模型的 prompt 有多大」。<br>**② 真凶（三处配置）**：`memory.max-tokens: 100000` + `deepseek-flash` 填了 `contextWindow: 1000000`/`maxOutputTokens: 384000` → 记忆窗口 = `min(100000, 1000000−384000)` = **10 万 token**。模型吐第一个字前必须把整个 prompt 做 prefill，历史越长越慢（线上实测首字 4 秒、长会话 21 秒，且**越聊越慢**）。同时 384000 会作为 `max_tokens` 原样发给服务商（远超真实上限）。<br>**③ 🔴 生产一直在跑 DEBUG**：`application.yml` 把 `com.huzhijian.nexusagentweb` 设为 debug，而 `application-prod.yml` 没有覆盖 → MyBatis 的 mapper 同在这个包下，**每轮 5~8 条 SQL 连同参数全量同步写 stdout**。已在 prod 显式覆盖为 info（排查延迟要的 `CHAT_PREFLIGHT`/`CHAT_CONTEXT`/`CHAT_MEMORY`/`CHAT_TTFB`/`RUN` 本来就是 INFO，不受影响）。<br>**④ 默认值取向改为「宁小勿大」**：`contextWindow`/`maxOutputTokens` 填大的代价（prefill 拖慢首字、`max_tokens` 超真实上限被 400）**远大于**填小的代价（少带一点上下文）。<br>**⑤ 读历史改为限量**：新增 `getRecentForChat`（SQL 先 `order by create_at desc, id desc limit N` 再外层正序还原）。⚠️ **直接 `order by create_at limit N` 会拿到最早的 N 条，正好相反**。⚠️ `limit<=0` 必须在 Java 层分流走全量 —— SQL 的 `LIMIT 0` 返回 0 行（模型直接失忆）、`LIMIT -1` 报错。<br>**⑥ 护栏测试的方向要钉对**：一开始断言「声明的 contextWindow 不得 > 20 万」，结果误伤了真实宣称 1M 上下文的小米 MIMO —— 但记忆窗口 = `min(max-tokens, 窗口−输出)` 已被 `max-tokens` 兜住，声明大窗口无害。改为断言**实际生效值**（用 `ModelCapabilities.of(Model).memoryWindow(globalMax)` 算一遍）才对。<br>**⑦ 前端需要同步（已写进 `docs/前端增量变更.md` + `docs/模型能力配置（前端）.md`）**：模型编辑表单 placeholder 改 `65536` / `16384`；长会话会开始丢更早的上下文，建议把「新建会话」做得更显眼。测试 **511**（新增 6），0 失败，12 人工跳过 |
| 2026-10-06 | **新增「停止生成」+ 修「技能多选取消全部反而启用全部」+ SSE 新增 `stopped` 事件** | 新增 `context/RunCancellationRegistry`、`exception/RunCancelledException`、`dto/StopChatDTO`、mapper `existsByRunId`(+XML)；改 `ChatController`(+`/stop`)、`ChatServiceImpl`（3 个中断检查点 + 补落库）、`SseResponseConverter`(+`writeStopped`)、`SseEventType`(+`STOPPED`)、`SkillLoader`(空数组语义)、`ChatMemoryService(+Impl)`、`docs/sse-contract.md`(§3.7)、`docs/前端增量变更.md`；新增 `StopGenerationTest`(12)、`SkillLoaderTest`(+3) | **前端需要同步（已写进 `docs/前端增量变更.md` 与 `docs/sse-contract.md` §3.7）：新增 `POST /api/chat/stop`（`runId`/`sessionId` 二选一，失败也是 200 + `code=1`）；SSE 新增 `stopped` 事件（`data={reason, partial}`），前端**不得**弹错误提示、**不得**触发 `finish` 的自动后续动作；`ChatDTO.skills` 空数组语义从「全部」改为「都不用」。**<br>**① 「断开 SSE ≠ 停止任务」是 2026-10-03 刻意的（断线就丢弃产出、任务还在烧钱是最坏组合），所以「等太久只能干瞪眼」一直缺这条路。**停止与被动断开必须严格分开**：`onTimeout`/关网页仍让任务跑完落库，只有显式 `POST /stop` 才真的停。<br>**② 🔴 langchain4j 的 `TokenStream` 没有 cancel/close** —— 唯一能真正停掉它的办法是让回调链抛异常（内部 `Spliterator` 循环因此终止、上游 HTTP 流取消）。代价：**它会走 `onError`，而 langchain4j 只在 `onCompleteResponse` 时写记忆** → 已生成的那半截回答刷新后消失。补救：按 `run_id` **幂等**补写一条 AI 消息（`existsByRunId` 守卫；重复行会让下一轮锚点定位错位、把整段历史重写一遍）。一个字符都没生成时**不写**（空回答会污染上下文）。<br>**③ 中断要有 3 个检查点**：思考流 / 正文增量 / 工具参数流。只在正文加检查点的话，「思考了 15 秒」和「正在吐几万字符的工具参数」这两种都停不下来。<br>**④ 停止是用户意图不是失败**：新增 `stopped` 事件而**不是**复用 `error`（弹红字会让用户以为出错）；且前端**不应**在 `stopped` 时触发 `finish` 的自动后续动作。<br>**⑤ 🐛 `putIfAbsent` 用错导致停止功能静默失效**（写测试时被抓到）：`register` 已放了 `FALSE`，而 `putIfAbsent` 的语义是「key 不存在才放入」→ 永远返回旧值 `FALSE`、**永远改不了状态**。必须用 `put`（覆盖语义，靠返回的旧值判断是否从运行中改过来）。这类「集合 API 语义搞反」的 bug 编译期与肉眼 review 都发现不了，只有断言 `cancel() == true` 才抓得到。<br>**⑥ 手动叫停不发 `finish`**：所以 `isNewSession` 时**不生成标题**（新会话被叫停 → 会话列表里是空标题）。已知取舍，优先级低。<br>测试 **526**（新增 15），0 失败，12 人工跳过 |
| 2026-10-06 | **「重新生成」成一等公民 —— 后端支持 n/n 版本切换（原状：刷新后变成「问题A 回答A / 问题A 回答B」两轮平铺）** | 新增 `docs/sql/013`、`config/SchemaStartupChecker`；改 `ChatDTO`(+`regenerateFromMessageId`)、`RunContext`(+1 字段与 5 参便捷构造)、`MessageVO`(+`id`/`supersededBy`)、`ChatHistory`(+`supersededBy`)、`ChatMemoryMapper(.xml)`(+`getActiveForChat`/`markSupersededSince`/`findFirstAiMessageIdOfRun`，历史查询补列)、`ChatMemoryService(+Impl)`、`PgChatMemoryStore`、docs；新增 `RegenerateAnswerTest`(7) | **前端需要同步（已写进 `docs/前端增量变更.md`）：历史接口每条消息新增 `id`（**字符串**形态雪花 ID，做切换 key 用）与 `supersededBy`（非 null = 已被更新版本替代）；`POST /api/chat/stream` 请求体新增**可选** `regenerateFromMessageId`（不传 = 普通发问，行为完全不变）。⚠️ **必须先执行 `docs/sql/013_add_superseded_by.sql`**，否则历史与对话接口全线报错。**<br>**① 折叠显示是前端的活，但**真正的问题在服务端**：原来每轮从库里读**全量**历史，两版回答会**同时进模型上下文** → ① 白烧 token（直接踩同一天刚治的首字延迟）② 模型看到「问过两遍答过两遍」容易啰嗦 ③ **用户切回 1/2 接着聊，模型记得的仍是 2/2，n/n 切换形同虚设**。所以后端必须让「被替代」成为**可持久化**的标记，进程内标记做不到（刷新一次就丢）。<br>**② 记忆加载与历史接口必须用不同的查询**（同一个类里两个方法，`getActiveForChat` vs `getAllByMemoryIdAndUserId`）：被替代的只是「不参与模型上下文」，**不是删除** —— 不返回就没法做 n/n 切换。这条用 `verify(never())` 钉死（`RegenerateAnswerTest`）。<br>**③ 🔴 重新生成时必须跳过用户提问**：问题已在库里，重复存会让模型看到「同一问题问两遍」，且历史里两条一模一样的提问没法归成一组做切换。⚠️ **显式剔，不能指望锚点法恰好跳过** —— 重新生成时锚点命中的是**已被排除的旧回答**（已不在记忆里），会退化成内容去重，那条逻辑的行为不该承担「跳过提问」这个语义。<br>**④ 🐛 `markSupersededSince` 的边界是 `id >= sinceId` 而不是 `id >`**：`sinceId` 就是用户点的那条回答本身，用 `>` 会漏掉它。表现极隐蔽 —— 切换 UI 做得再好看，用户切回 1/2 继续问时模型仍记得 2/2。<br>**⑤ 该语句还必须排除本次 runId**（`run_id is distinct from #{excludeRunId}`），否则新回答会把自己标成被自己替代，记忆加载时被排除，用户刚点重新生成就「什么都没发生」。另加 `type <> 'USER'` 兜底：提问被标记掉的话，模型下一轮不知道在回答谁。<br>**⑥ 语义 =「回到旧版本继续 = 开新分支」**：在 1/2 上重新生成时，1/2 之后聊过的内容一并让位。与 ChatGPT 的分支语义一致，不是缺陷。<br>**⑦ 🔴 新增 `SchemaStartupChecker`：少执行迁移脚本的故障从「运行时一条 SQL 报错」提前到「启动期明确报缺哪一列」**。`StartupConfigValidator` 只查 Environment（yml/环境变量），**不查 schema** —— 两者不重叠，yml 全对也会缺列。缺列时 `column does not exist` 会让「发消息」与「拉历史」两条核心链路一起挂，日志上只是一条 SQL 报错，极易被误判成代码写错而回滚版本。⚠️ **连不上 DB 时只 WARN 不阻断**（连接问题由连接池自己 fail-fast，重复阻断会把真正的错误盖掉）。<br>测试 **533**（新增 7），0 失败，12 人工跳过 |
| 2026-10-06 | **采纳 fronted 两条建议：SSE 收尾事件带服务端首字延迟 `ttfbMs` + 上传被拒时 `data` 带配额快照** | 改 `SseResponseConverter`(+`markTtfb`/`withTtfb`)、`ChatServiceImpl`(TTFB 处回填)、`Result`(+`error(msg, data)`)、`QuotaExceededException`(+data 载荷)、`QuotaServiceImpl`(超限时附快照)、`GlobalExceptionHandler`、`docs/前端增量变更.md`；新增 `FrontendTelemetryTest`(7) | **前端需要同步（已写进 `docs/前端增量变更.md`）：`finish`/`stopped`/`error` 三事件的 `data` 新增 `ttfbMs`；上传被拒时 `data` 带 `fileQuota`/`fileUsed`/`fileRemaining`/`fileUnlimited`/`role`。**<br>**① 建议①成立**：前端自己测的「首字 xx ms」**含网络往返与反代缓冲**，与 `CHAT_TTFB` 不同源，出现「前端 300ms / 服务端 1800ms」时两边都以为对方错了。`markTtfb` 在**收到第一个内容 token** 时回填（与日志同源），三个收尾事件统一带上。<br>**② ⚠️ `ttfbMs` 缺字段而不是 `null`**：契约写「可能不存在」比「存在但为 null」对前端更省事（少一个 falsy 判断）。另注：纯思考模型（先 thinking 再正文）时该值**偏小**，不能拿它冒充体感延迟。<br>**③ 建议②成立**：以前超限只有一句文案，前端只能弹 toast 让用户「再试一次」，可他不知道自己离上限还有多远 —— 等于「突然不让用了」。快照字段名与 `QuotaVO` **同名**，前端一套取值两处通用。<br>**④ ⚠️ `Result.error(msg, data)` 与 `Result.ok(data)` 参数顺序相反**（前者 msg 在前）。这类「两个重载顺序不一致」的 API 编译器不报错、写反了运行期才暴露，用测试钉住。<br>**⑤ 首帧前点停止的时序问题（fronted 问，已确认安全）**：`register` 在**请求线程 preflight** 里完成，而首帧在它之后才发 —— 所以「前端拿到 runId」时后端必然已登记完，此时补发的 stop 一定命中。理论空窗（stop 早于 register）要求前端先发过 `/stream`，无从触发。<br>测试 **540**（新增 7），0 失败，12 人工跳过 |
| 2026-10-06 | **🔴 fix：切到旧版本后发【新提问】，提问被整条丢掉**（fronted 发现的真 bug）** | 改 `PgChatMemoryStore`（删掉那段「重新生成就过滤所有 USER 行」）**；`RegenerateAnswerTest` 改写并新增 1 项回归护栏 | **无前端影响**（接口与事件都没变，是后端落库逻辑的漏洞）。<br>**① 错在哪**：我把「是否重复存提问」错误地绑在了 `regenerateFromMessageId` 上 —— 「有值就过滤所有 USER 行」。但同一个入口有两种场景：**重新生成**（问题已在库里，该跳过）与**切旧版本后发新提问**（问题不在库里，必须存）。后者被整条吃掉，用户说的话凭空消失 —— 比显示问题严重得多，是最不可接受的一类丢数据。<br>**② 正确判据是「这条提问库里是否已有」，而 `resolveInsertStartIndex` 的内容去重已经在做这件事**：重新生成时去重碰到库里那条问题A 就停 → 只写新回答；切旧版本时「问题C」不在库里、继续往前找到问题A 才停 → 写 `[问题C, 回答D]`。两种都正确。<br>**③ 教训：加「显式过滤」前先问「现有机制是不是已经在管这件事了」**。我当时的理由（「锚点命中的是已被排除的旧回答，退化成内容去重，那条逻辑不该承担这个语义」）是**错的** —— 退化成内容去重恰恰就是正确判据。**多余的一层过滤制造了 bug，而不是修 bug。**<br>测试 **541**（新增 1）
| 2026-10-06 | **新增用户资料接口（头像链路补全）** —— fronted 要做设置页表单化+头像上传，查完发现**头像链路是断的** | 新增 `vo/UserProfileVO`、`dto/UpdateProfileDTO`；改 `UserService(+Impl)`(+`getProfile`/`updateProfile`、注入 `OssUrlGuard`)、`UserController`(+`GET`/`PUT /api/user/profile`)、`docs/前端增量变更.md`；新增 `UserProfileTest`(9)；`EmailCodeOneTimeUseTest` 补构造参数 | **前端需要同步（已写进 `docs/前端增量变更.md`）：新增 `GET /api/user/profile` 与 `PUT /api/user/profile`；头像上传 `POST /api/file/image` **已有**。无 DB 变更。**<br>**① 🔴 头像链路原先是**断的**：`POST /api/file/image` 只把文件传上 OSS 并返回 URL、**不落库**，而 `users.avatar_img` 压根没有接口可写 —— 「上传头像」做完刷新就没了。补 `PUT /profile` 才闭环。<br>**② 🔴 不能继续拿 JWT 当用户数据源**（前端原先在用）：JWT 已带 `user_name`/`image_url`，但 ① **没有 email**（设置页要显示邮箱）② 那是**登录那一刻的快照**，token 有效期 7 天 —— 用户改了昵称 7 天内页面都不同步 ③ JWT 是签名过的**凭据**，解析逻辑一错就显示错用户。<br>**③ 头像 URL 必须校验 OSS 域名**（`OssUrlGuard`）：否则用户能把任意外链接追踪像素存进库，页面每次渲染都等于给第三方递一次访问。<br>**④ 更新走 MyBatis-Plus 的 `updateById`（忽略 null 字段）而非手写 `updateByPrimaryKeySelective`** —— 后者是历史遗留 XML；`users` 表没有 jsonb 列，不存在「MP 更新类型不匹配」的限制。<br>**⑤ 「两个字段都不传」是静默 no-op 不报错** —— 前端「保存」在无改动时也可能被点到。<br>测试 **561**（新增 9），0 失败，12 人工跳过 |
，0 失败，12 人工跳过 |

| 2026-10-05 | **🔴🔴 修「AI 交付的文件全部损坏」：沙盒把二进制当 UTF-8 文本处理**（PNG/docx/xlsx/zip 一律打不开，用户报「刚生成的 docx 打不开」） | 改 `nexus_agent_box/app/routers/file.py`、`app/utils/oss_utils.py`、`app/routers/box.py`、`.env.example`、`README.md`；新增 `nexus_agent_box/e2b/Dockerfile.template`、`tools/test_binary_roundtrip.py`(25)；`docs/前端增量变更.md` 新增 1 节；`AGENTS.md` §16.2 之 ④ 补模板构建方式 | **前端需要同步（已写进 `docs/前端增量变更.md`）：接口/字段/下载方式全无变化，唯一语义变化是 `artifact.size` / `file.fileSize` 现在等于「文件真实字节数」**（修复前报的是损坏后的大小，故偏大）。<br>**根因**：`download_file` 写的是 `sbx.files.read(file_path)`，而 **E2B SDK 的 `format` 参数默认值就是 `"text"`**，实现是 `return r.text` —— httpx 按 charset 解码，**非法字节一律替换成 U+FFFD**。PNG 头 `89 50 4E 47` → `EF BF BD 50 4E 47`（`EF BF BD` 即 U+FFFD 的 UTF-8 编码）。两个后果缺一不可：① 首字节不再是魔数 → 按格式识别的工具拒绝打开；② **每个坏字节 1 字节涨成 3 字节 → 体积凭空膨胀**（实测 94367 → 171196，正是当年「上报 171196、实际 94367」的由来）。docx/xlsx/pptx/zip 同理：都是 zip 容器，任一字节被替换就解包失败 → Office 报「已损坏」。<br>**先排除的嫌疑**：Java 侧 `AliOssUtil`（`putObject` 用 `ByteArrayInputStream`、`getObject` 用 `readAllBytes`）**本来就是正确的字节流处理**，不是源头 —— 别一看到文件损坏就先改Java。<br>**修复**：`files.read(path, format="bytes")` 走 SDK 的 `bytearray(r.content)`，不经过任何编解码；再用 `bytes(...)` 收口，因为**SDK 返回的是 `bytearray` 而非 `bytes`**（且升级可能改签名）。<br>**配套两道防线**：① `str_upload_file` 更名为 **`upload_bytes`** —— 名字里的 `str_` 把调用方误导成"这里传字符串"，是一路误导到根因的帮凶；且它现在**拒收 `str`，传错类型第一行就抛 `TypeError`**，让同类 bug 在最早就炸掉；② 上传时显式带 `Content-Type`（`guess_content_type`），`docx` 不再被浏览器当裸二进制流。<br>⚠️ **排查这类问题要把两件事分开**：**字节损坏**看 hex 文件头（上例），**Content-Type 不对**只是浏览器不预览 —— 表现都是"打不开"但根因不同，别混为一谈。<br>**已核实无需修的**：报告 3「乐享检索报无法确定当前用户」在上一批已修（`ChatServiceImpl` 请求线程 `runUserRegistry.register(sessionId, userId)` + 工具侧 `@ToolMemoryId` 反查），并复核 `@ToolMemoryId` 注入的确实是 `sessionId`（`ChatContextFactory` 里 `.id(sessionId)`），链路完整；报告 4（CSV/TSV）本就是成功记录。<br>**报告 5 已升级为可执行项**：新增 `nexus_agent_box/e2b/Dockerfile.template` 预装 python-docx/openpyxl/python-pptx/matplotlib + **.NET 8 SDK** + fonts-noto-cjk；`box.py` 支持 `E2B_TEMPLATE_ID`（**留空则行为完全不变**，启动不会失败），模板 id 走环境变量而非硬编码。⚠️ **构建与建模板需用户在本机执行**（涉及 E2B 控制台/本机凭据，AI 代劳不了）。<br>⚠️ **测试教训（与 AGENTS.md 铁律第 0 条同源）**：新测试**自带元测试** —— 第 0 组先断言「桩用旧写法确实会损坏 PNG 魔数、确实会体积膨胀」，第 2 组再断言「旧写法确实坏、bytes 模式确实好」。只有元测试成立，后面那些通过才有意义；否则一个写错的模拟会全绿放行。已**反向验证**：把 `format="bytes"` 改回旧写法，测试立即变红（退出码 1）且被 `upload_bytes` 的类型闸门直接抓到。<br>**Python 侧测试范式**：`nexus_agent_box/tools/` 下用「桩对象 + 本地 server + `sys.exit(1 if failed)`」，仿 `test_url_guard.py`；不依赖外网 / OSS / 真实沙盒。桩的形状要**对齐真实调用形态** —— `Sandbox.connect(box_id)` 是**类方法**，直接把模块里的 `Sandbox` 换成函数会报 `'function' object has no attribute 'connect'`。Java 侧 **484** tests, 0 failures, 12 人工跳过；Python 侧 **25** 项全绿 |
| 2026-10-05 | **🔴🔴 修正上一条「文件删不掉」的结论：真凶是 19 位雪花 ID 在 JS 里丢精度**，并在序列化层根治；顺带新增批量删除接口 | 新增 `config/JacksonConfig`、`dto/BatchDeleteFileDTO`、`vo/BatchDeleteResultVO`、`FileService(+Impl)#batchDelete`、`FileController#batchDelete`；新增 `SnowflakeIdPrecisionTest`(8)、`FileServiceBatchDeleteTest`(12)；`docs/前端增量变更.md` 新增 1 节并给旧节加「已被推翻」横幅；`AGENTS.md` §3.1 新增 L2' 分层与铁律第 0 条 | **前端需要同步（已写进 `docs/前端增量变更.md`）：① 所有 `Long` 字段变成带引号的字符串（`code` 等 `Integer` 不变）；② 新增 `POST /api/file/batch-delete`（部分成功语义）。**<br>**上一条结论是错的**：10-05 上午定位为「`ArtifactServiceImpl#delete` 硬加 `biz_type=ARTIFACT`」，那确实是个真问题（已修），但**不是「每一行都删不掉」的主因** —— 去掉限制后用户反馈「还是每次都这样」。<br>**真凶**：`SysFile.id` 是 `@TableId(ASSIGN_ID)` 雪花 ID，**19 位十进制数**；而 JSON 里输出的是裸数字，`JSON.parse` 产出 IEEE754 double，`Number.MAX_SAFE_INTEGER` 只有 **16 位**（9007199254740991）→ 实测抽样 4000 个雪花 ID **99.6% 被改写**。删除是「按 id 回传」的操作（`返回 id → 浏览器 double → 拼 URL → WHERE id = ?`），id 在浏览器里已经变了，后端必然落空。⚠️ 该缺陷**不限于删除**：凡「拿 id 去改/删」的接口都中招（技能 / 用户记忆 / MCP / 聊天记录）。<br>**修复选择**：在**序列化层**全局把 `Long/long` 序列化为 `String`（`Jackson2ObjectMapperBuilderCustomizer#serializerByType`），而不是逐个字段加 `@JsonSerialize` —— 后者治标，新增实体忘了加就换个接口复现。**刻意不碰 `Integer`**：`res.code !== 0` 这类判断不能失效。用 customizer 而非 `@Bean ObjectMapper`，后者会替换掉 Boot 自动配置的那个 mapper，清空时间格式等一堆默认设置；也刻意不用 `modulesToInstall(SimpleModule)`（按模块顺序生效，行为依赖装配顺序）。反序列化方向不受影响（Jackson 字符串→Long 是标准行为），所以前端<b>不需要</b> `parseInt`。<br>**批量删除的两个设计决定**：① 语义是「**部分成功**」而非全有全无 —— 一批里有的能删有的不能删时，能删的照样删，结果分 `deletedIds`/`failedIds` 返回（对外不区分「不存在」与「不是你的」，防 IDOR 探测）；② **刻意不加 `@Transactional`** —— 逐条独立提交才符合部分成功语义，一条失败就整批回滚反而让前端只拿到「全失败」；且记录删除与 OSS 删除本就不原子，套事务也原子不了。**必须先按 `id + user_id` 查出来再 `removeByIds`**，直接按 id 删就是「知道 id 就能删任何人的文件」。<br>⚠️ **测试教训**：① 写「id 精度」测试时**必须真的走一次 `double`** —— 我第一版用 `BigDecimal.longValueExact()`，那是精确算术，裸数字走它也「无损」，等于没测；② `ApplicationContextRunner#run` 的 Consumer 返回 void，不能在 lambda 里 return 值，要用数组 holder 带出；③ `IService#removeByIds` 在 Mapper 层落到的是 **`deleteBatchIds`**，stub/verify 写 `deleteByIds` 编译不过；④ **`nexus-agent-domain` 模块没有 swagger 依赖**（只有 web 模块有），在 domain 的 DTO/VO 上写 `@Schema` 会编译失败，该模块一律用普通 javadoc。<br>另：本次写测试时**又把文件写进了错误包目录**（`com/huzhujhian/`），且发现历史遗留的 `SseTimeoutDriftTest` 一直躺在错误目录 `com/huhuhuzhijian/` 里**从未被编译执行**（幽灵测试）—— 已全部归位并修正 package（详见 §易踩的坑），归位后它那 2 个用例**第一次真的跑了起来**。<br>**测试 484**（新增 20 + 归位 2），0 失败，12 人工跳过 |
| 2026-10-05 | **🔴 修「任务跑满 2 分钟被掐断」（第二次事故）**：prod 残留的 `sse.timeout: 120s` 盖掉了 10-03 改的默认 1800s | `application-prod.yml`（删 `timeout: 120s`）、`application-dev.yml.example`（同款残留一并删）、`AgentProperties`/`ChatServiceImpl`/`RunUserRegistry`（引用旧值的注释更新）、`AGENTS.md` §6.1/§6.11/配置表、`README.md` 配置表；新增 `SseTimeoutDriftTest`(2) | **根因是 10-03 修复只改了一半**：把 `AgentProperties.Sse.timeout` 的<b>默认值</b>调到 1800s，但 `application-prod.yml` 里显式写的 `timeout: 120s` 没删 —— 而 **profile 属性源优先级高于代码默认值**，用户以 prod profile 运行 → `new SseEmitter(120_000)` 满两分钟必断（前端截图 stream 的 Time 恰好 2 min）。这是「两份配置不同步」坑的又一次发生（上次是 CORS，见 §5.x）。<br>**修复方式**：① 删掉 prod / dev 模板里的 `timeout` 键，超时的**唯一事实源**收归 `AgentProperties.Sse`（1800s）；② 新增 `SseTimeoutDriftTest`——把 4 份 yml 当输入，断言任何一份都不得声明 `nexus.agent.sse.timeout`、且默认值不得低于 1800s，**已反向验证**（把 120s 塞回去测试立刻红）。<br>⚠️ 用户本地若有自己不提交的 `application-dev.yml` 且写了这个键，同样会盖掉默认，需自查。前端无感知（心跳 `:ping` 一直在，SSE 契约未变），**改完需重启后端生效**。测试 **464**（+2），0 失败 |
| 2026-10-05 | **🔴 修三个「界面上不对」的问题**：① MCP 预置服务能重复添加；② 文件/产物一行都删不掉；③ `GET /api/lexiang/teams` 恒 500 | 改 `McpInformationServiceImpl`（两级去重 + `added`/`localId`）、`McpServerItemVO`（+2 字段）、`ArtifactServiceImpl`（去 `biz_type` 条件）、`FileService(+Impl)`（新增 `delete`）、`FileController`（新增 `DELETE /api/file/{id}`）、`AliOssUtil`（抽出 `deleteByUrl`）、`GlobalExceptionHandler`（+3 个 handler）；新增 `FileServiceDeleteTest`(7)、`AliOssUtilDeleteByUrlTest`(5)、`GlobalExceptionHandlerMappingTest`(5)，`McpInformationServiceImplTest` +3；`docs/前端增量变更.md` 新增 3 节 | **① MCP 能重复添加是两层**：前端只能自己比 `strId`（可能 null/空串/带空格）必然有漏；后端去重<b>只认 strId</b>，漏传就直接 insert，而 PG 唯一约束里 `str_id IS NULL` 的行<b>互不冲突</b> → 能无限插重复行。→ 后端直接在预置列表里返回 `added` / `localId`（`localId` 用字符串避免 JS 精度），去重升级为 **strId → url 两级**（url 归一化只去首尾空格与末尾斜杠，**刻意不转小写**，URL path 大小写敏感）。<br>**② 删不掉**：`ArtifactServiceImpl#delete` 硬加了 `biz_type=ARTIFACT`，而前端「文件与产物」是统一视图（42 = 13 对话附件 + 29 产物），删除按钮对所有行打这个端点 → 13 个 CHAT 附件永远命中不了。⚠️ `biz_type` **不是安全边界**，安全边界是 `user_id`。新增不限类型的 `DELETE /api/file/{id}`，`ArtifactController#delete` 保留（兼容已上线前端）但去掉类型条件。删除顺序：**先删记录、再尽力删 OSS**，`deleteByUrl` 吞所有异常只记 WARN（对象残留只是成本，报错会让用户以为没删掉）。<br>**③ 乐享 500**：不是乐享挂了 —— 它整条链路抛 `IllegalStateException`，而 `GlobalExceptionHandler` **没有该类型的处理器**，全掉兜底 500 变成「系统内部错误」，真实原因（未配凭证 / AppKey 无效 / 限频 / 授权范围不含该成员）被吞掉。补 `IllegalStateException` / `IllegalArgumentException` / `ParserFileException` 三个 handler，用 `log.warn`（本项目这类异常都带中文人话消息，属可预期，不该和真 NPE 一起报警）。<br>⚠️ **测试教训**：`@ExceptionHandler` 映射这类配置层错误，纯 mock 单测**永远抓不到**（只会看到 500），必须专门写「异常 → `Result`」映射测试。另：单测里 `ServiceImpl#removeById` 会 NPE（`TableInfoHelper` 未初始化），需在测试里手工 `initTableInfo`；且 mock 的 `deleteById` 默认返回 0，断言返回值前要 stub。测试 **462**（+20），0 失败 |
| 2026-10-05 | **前端文档拆成两份**：`前端开发指南.md` 冻结（"从零搭前端"教程），新增 `前端增量变更.md` 只写增量 | 回滚 `docs/前端开发指南.md`（撤掉上一版塞进去的 7 处教程式 runId 段落）；**新增** `docs/前端增量变更.md`；`AGENTS.md` 铁律第 9 条与 §8.1 改写 | **起因**：上一版把 runId 写进完整指南（含 JS 伪代码、要点列表、边界表共 7 处），用户反馈"太全面了，我只需要需要更新的部分，因为我前端已经搭好了"。<br>→ 定为规则：**主指南冻结不动，所有前端可见的增量一律写进 `docs/前端增量变更.md`**，按日期倒序，结构固定为「改了什么（表）/ 你要动哪几处（编号）/ 不用动的（短列表）」。<br>→ 判断标准写进 §8.1：**写出来的内容在本次改动之前就已成立 → 那是教程，删掉。**<br>增量文档里另外补了两条最容易被漏的：① `runId` 必须先执行 `docs/sql/011_add_run_id.sql`，否则两列全是 `null`，用户会以为后端没做；② Nginx 侧要同步 `client_max_body_size 30m`，否则网关默认 1m 就把上传拦了，后端日志里什么都看不到 |
| 2026-10-05 | **产物归属（方案 B）：持久化 `runId`，产物能落回产出它的那一轮** | 新增 `docs/sql/011_add_run_id.sql`、`ArtifactRunIdTest`（9）；改 `SysFile`、`ChatHistory`、`RunContext`、`ChatServiceImpl`、`ArtifactService(+Impl)`、`PgChatMemoryStore`、`ChatMemoryServiceImpl`、`MessageVO`、`ChatMemoryMapper.xml`、`FileMapper.xml`、`docs/sql/README.md`、`docs/前端增量变更.md`（**新建**）；`AGENTS.md` 新增 §6.20、§8.1 与铁律第 9 条 | **前端需要同步（已写进 `docs/前端增量变更.md`，前端只看这一份）**：`GET /api/history/{sessionId}` 每行新增 `runId`、`GET /api/artifact?sessionId=` 每项新增 `runId`（可空）。匹配规则就是**字符串相等**：`artifact.runId === message.runId`。<br>**问题**：产物列表只说"这个会话产出了哪些文件"，不说"哪个是哪一轮产出的"，历史里又通常没有 `ARTIFACT` 行 → 刷新页面后前端在**数据上**无法归属，只能全堆进面板。<br>**为什么选 B 不选 A**：`chat_memory` **同时是 LangChain4j 的 ChatMemoryStore**，`PgChatMemoryStore.getMessages()` 对查出的**每一行**执行 `ChatMessageDeserializer`，插 `ARTIFACT` 行会污染模型上下文，还会打乱增量写入的「锚点去重」。方案 B 只加列、不新增行，不碰记忆语义。<br>⚠️ **两处必须记住**：① `runId` 必须在 `RunContext` **之前**生成（RunContext 是把它带进流式回调线程的唯一通道，那里没有任何 ThreadLocal）；② `getHistoryBySessionId` **必须逐行处理** —— 原来是「先映射成 `ChatMessage` 列表再统一转 VO」，行上的 `runId` 在这一步就丢了，补字段也补不出来，已重构为逐行转换（`toChatMessage` / `toMessageVO`）。<br>**行为兼容**：两列都可空，老数据 `runId=null` → 前端按"归属不明"处理（只进面板、不进对话）；**刻意不加索引**（runId 匹配在前端做，服务端没有 `WHERE run_id=?`，按仓库「无真实查询就不加索引」的约定）。测试 **426**（新增 9），0 失败 |
| 2026-10-04 | **🔴 全仓自检批次（"测试全绿但线上老炸"）**：补 4 类更高层级的测试，并据此修出 11 处静默失效 | 新增测试：`EnvPlaceholderDriftTest`(3)、`SseErrorFrameTest`(3)、`RuntimeConfigBindingTest`(3)、`ToolFailureContractTest`(3)；修复：`EmailUtils`、`ChatServiceImpl`、`ChatMessageConverter`、`FileUtils`、`AliOssUtil`、`ChatHistoryListServiceImpl`、`ChatMemoryServiceImpl`、`RedisUtils`、`MemoryTool`、`LogTool`、`JwtUtil`、`PgChatMemoryStore`、`UserConfigServiceImpl`；`AGENTS.md` 新增 §3.1 | **起因**：374 个纯 mock 单测全绿，上线却连炸三次，根因都在 mock 看不见的层（Servlet 容器行为 / Spring 配置绑定 / 文档漂移）。新增的四类测试层级见 **§3.1**。修出的真 bug：① `EmailUtils` 的 `send()` 写在 try **外面**，且 catch `MessagingException` 而 Spring 抛 `MailException`（两者**无继承关系**）→ 验证码已写 Redis 却告诉用户"发送成功"，实际从未发出；② `LogTool` 局部变量叫 `log`，与 `@Slf4j` 生成的字段同名（加日志时必须改名）；③ `ChatMemoryServiceImpl` 里 `entity.getContent().toString()` 与 `entity.getType().equals(...)` 在 jsonb 为 null 时双双 NPE → **一条脏历史让整个会话 500**；④ `UserConfigServiceImpl.decryptKey()` 解密返回 null 时 `.length()` NPE；⑤ 全仓 3 处声明 `throws com.aliyuncs.exceptions.ClientException` 但**无任何抛出点**（死代码）。新测试本身也抓出 2 个 bug（SSE 帧未设 UTF-8 致中文变问号；注释里的 `${}` 被误判为必需环境变量）。测试 **417**，0 失败 |
| 2026-10-04 | **修：SSE 报错时日志刷一屏** `HttpMessageNotWritableException ... preset Content-Type 'text/event-stream'`；顺带更正 `ALI_AI_KEY` 的文档错误 | `SseResponseConverter`（收尾由 `emitter.completeWithError(error)` 改为 `complete()`）、`GlobalExceptionHandler`（检测到已在 SSE 流里就直接写 error 帧，不再返回 JSON 信封）、`SseResponseConverterTest`（+1）；`.env.example`、`AGENTS.md` §1/§4.1/§5.2 | ❗**根因**：`SseEmitter.completeWithError(ex)` 会让 Servlet 容器对这个异步请求做一次 **error dispatch**（转发到 `/error`），而 SSE 响应的 Content-Type 已经是 `text/event-stream`，没有任何 HttpMessageConverter 能把 `/error` 的 Map（或我们的 `Result`）写成这个类型 → 二次抛 `HttpMessageNotWritableException` → 全局 advice 试图补一个 `Result` 又失败 → 一屏堆栈，前端反而收不到干净错误。错误信息早就由 `sendErrorEvent` 作为 `error` 事件发给前端了，收尾只需 `complete()` 关流。❗ **文档错误（会直接坑到人）**：`.env.example` 与 `AGENTS.md` 三处写着「向量模型下线后 `ALI_AI_KEY` 不再需要」是**错的** —— 它仍被 `nexus.agent.system-models` 里的百炼（qwen）供应商使用：不填启动就失败，填错/填成 OSS 的 AccessKey 就是对话时报 `Incorrect API key provided`。测试 **374**，0 失败 |
| 2026-10-04 | **修：上传文件报 502**（Tomcat 吞请求体超限 → 直接断连），顺带修 OSS 异常 catch 错类型 | `application.yml`（multipart 8→20MB/30MB、`server.tomcat.max-swallow-size=-1`、`connection-timeout=120s`）、`GlobalExceptionHandler`（新增 `MaxUploadSizeExceededException`→**413**、`MultipartException`→400）、`AliOssUtil`（catch 换成 `com.aliyun.oss.ClientException`/`OSSException`、加连接 10s / 读写 60s / 重试 2 次的超时、上传耗时日志）、`FileServiceImpl`（新增 `clip()`）、`docs/前端开发指南.md`（Nginx 补 `client_max_body_size 30m` 等）；新增 `FileServiceUploadFailureTest`（3） | ❗**502 不是后端报错导致的**：文件超过 `max-file-size` 时 Spring 在 multipart 解析阶段抛 `MaxUploadSizeExceededException`，而 Tomcat 必须先把剩余请求体吞完才发得了响应 —— **默认 `maxSwallowSize` 只有 2MB**，吞不完就**直接掐断连接**，网关/代理看到的就是 **502**，后端日志只剩一句 `SocketTimeoutException at NioEndpoint$NioSocketWrapper.fillReadBuffer`。同时全仓**没有**该异常的处理器，就算响应发出去也是 500。→ 两处一起修才能拿到可读的 413。❗ **OSS 那边一直 catch 错了类**：写的是 `com.aliyuncs.exceptions.ClientException`（aliyun-java-sdk-core），而 OSS SDK 真正抛 `com.aliyun.oss.ClientException` / `OSSException`（包名只差一点）→ 凭证错、网络超时**一个都没被捕获**，整批上传直接 500；现已降级为「单文件 FAILED + 可读原因」。❗ `e.getMessage().substring(0,450)` 在 message 为 null 时 NPE、不足 450 字符时越界，换成 `clip()`。测试 **373**（361 通过 + 12 人工跳过），0 失败 |
| 2026-10-04 | **技能库：用户上传 / AI 生成 / 社区共享**（对应 minmax 的「技能」页）。官方技能仍走部署目录，用户技能存 `user_skill` 表，**解压不落盘** | 新增 `docs/sql/010_create_user_skill.sql`、`domain/UserSkill`、`mapper/UserSkillMapper`、`em/SkillVisibility`、`em/SkillSource`、`skills/OfficialSkillSource`、`skills/SkillPackageParser`、`skills/SkillGeneratePrompt`、`service/UserSkillService(+Impl)`、`controller/SkillController`、`dto/SkillGenerateDTO`、`dto/SkillSaveDTO`、`vo/SkillVO`、`vo/SkillDetailVO`；`SkillLoader` 重写；新增 `SkillPackageParserTest`(24) 并改 `SkillLoaderTest`(14)；`application.yml` 加 `spring.servlet.multipart` | **关键决策**：不把 zip 解压到 `skills/users/` —— 那要处理 zip slip、zip 炸弹、删除时机、多用户隔离四件事，而 `Skills.from(Collection<? extends Skill>)` 接受任意实现，`DefaultSkill.builder()` 能直接在内存里造技能，**落盘这一步被整个消掉**。代价是 `SkillLoader` 类型必须从 `FileSystemSkill` 放宽为 `Skill` 接口（原来等于把「技能只能来自文件系统」写进了类型）。踩到三个坑：① `isCollectable` 不能排除 `SKILL.md`，否则所有 zip 包都报「未找到 SKILL.md」；② frontmatter 必须**先剥引号再去行尾注释**，否则 `description: "C# 相关 # 重点"` 会被截断；③ `ZipInputStream` 遇非法数据不抛异常只返回 null 条目，「一条都没读到」要单独报「不是有效 zip」而非误报「缺 SKILL.md」。另：`or()` 不带括号会让可见性条件把「自己的技能」OR 掉 |
| 2026-10-04 | **🔴 修 MCP 登记 500**（`null value in column "header" ... violates not-null constraint`），顺带清掉同链路上 4 个未爆雷 | `McpInformationServiceImpl`、`McpInformation`、`McpInformationMapper.xml`、`McpClientRegistry`、`McpServerItemVO`、`docs/sql/README.md`、`docs/前端开发指南.md`；新增 `McpInformationServiceImplTest`（13 个单测） | **主因**：`JSONUtil.toJsonStr(null)` 在 hutool 里返回 `null` 而非 `"null"`，而 `header` 是 `jsonb NOT NULL`；触发路径是「服务商预置列表一键添加」—— `McpServerItemVO` **刻意不含 header**（凭据不该进列表接口），回传时必然为 null。**连带修**：① `updateMCP` 的 `header` 缺 `::jsonb`（同 `llm_api_token` 那条，一改就报类型不匹配）；② `available` 是 `boolean NOT NULL`，DTO 不传时写了 null；③ `header` 存了但 `McpClientRegistry` **从未传给 transport**，配了鉴权头的服务必然连不上；④ `Collectors.toMap` 遇 `str_id IS NULL` 的历史行直接 NPE（PG 唯一约束里 NULL 不算冲突，拦不住）。另加请求头**换行注入**防护（tchar 白名单正则，非黑名单） |
| 2026-10-04 | **🗑 下线 WebSocket**（标题推送），连带移除 `spring-boot-starter-websocket` | 删：`WebSocketService`、`WebSocketConfiguration`、`WebSocketAuthInterceptor`、`WebSocketAuthInterceptorTest`、`docs/WebSocket接入（前端）.md`；改：`ChatHistoryListServiceImpl`（只入库不推送）、`ChatHistoryListServiceImplTest`、`BoxToolTest`、`nexus-agent-service/pom.xml` | 整个 WS 只为"标题实时到"这一个非关键字段存在，却要引入握手鉴权 + 来源限制 + 重连 + 前端单例 + 双端心跳，收益与成本不成比例。前端改为拉 `GET /api/history` 拿标题，用户无感。**附带好处**：原本零鉴权（可枚举他人推送）的漏洞面随功能一起归零。详见 §6.6 |
| 2026-10-04 | **🔴 安全修复批次（P0 3 项 + P1 5 项）**：① 邮箱验证码用后即删（原来可无限重放）；② MCP URL 加 SSRF 校验；③ box `upload_file` 加 SSRF/体积/重定向防护 | 新增 `UrlGuard`、`OssUrlGuard`、`box/app/utils/url_guard.py`；重写 `FileUtils`；改 `ChatMessageConverter`、`FileServiceImpl`、`UserServiceImpl`、`UserConfig`、`EncryptorFactory`、`LexiangClient`、`McpClientRegistry`、`McpInformationServiceImpl`；新增 4 个测试类 | ⚠️ 单测抓出 2 个「防护写了但从未生效」的真 bug（① IPv6 ULA 因 signed byte 比较 `(b[0]&0xFE)==(byte)0xFC` 永不成立 → 所有 `fc00::/7` 私网此前都能绕过 SSRF；② 文档归属校验只在 `FileUtils` 内部，入口层无独立防线）。另 `UserConfig` 三字段加 `@JsonIgnore`、`queryFileByids` 补 `user_id`（原来谁的 id 都查得到）、主密钥 <16 字符打 ERROR、乐享日志脱敏。prod CORS 按用户要求豁免（前后端分离，启动时动态填前端域名） |
| 2026-10-04 | **🔴 下线本地知识库（pgvector）**，知识库检索只保留乐享 | 删：`KnowledgeController`/`RagTool`/`KnowledgeBase*Service(Impl)`/`KnowledgeBase*Mapper(+xml)`/`PgVectorEmbeddingFactory`/`Knowledge*DTO`/`KnowledgeBase*` 实体；改：`ChatDTO`（去 `enableRag`）、`ToolSelection`（单字段）、`nexus-agent-service/pom.xml`（去 pgvector）、`application-prod.yml`、`.env.example`、`scripts/check-env.sh`；新增 `docs/sql/009` | **行为变更**：① `POST /api/chat/stream` 不再接受 `enableRag`；② `/api/knowledge*` 全部 404；③ `${ALI_AI_KEY}` 不再必填。**⚠️ 聊天附件不受影响** —— `/api/file` 与三个 document-parser 依赖都保留（`FileUtils` 仍被 `ChatMessageConverter` 用）。表由 `009` 删。测试 299 全通过 |
| 2026-10-03 | **新增乐享知识库接入（只读检索）**：`LexiangRagTool` + `LexiangClient` + `LexiangTokenProvider` + `lexiang_credential` 表；`ChatDTO` 新增 `enableLexiangRag` | `docs/sql/008`、`lexiang/`、`LexiangController`、`ChatDTO`、`ToolSelection` | 只做检索不做上传；token 双层缓存（进程内+Redis）因限频 20 次/10 分钟；前端文档 `docs/乐享知识库接入（前端）.md` |
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
| 2026-09-30 | **P2-8 遗留完成**：配额周期重置 + 用量查询接口 | 新增 `em/QuotaPeriod`（周期枚举，容错解析）、`vo/QuotaVO`、`docs/sql/007_add_user_token_quota_period.sql`（`users` 加 `token_period`/`token_period_start`）；`User` 加两字段；`UserMapper.java`/`.xml` 加 `resetQuotaPeriod`（带 WHERE 的原子 UPDATE）；`AgentProperties.Quota` 加 `period`；`QuotaServiceImpl` 抽出 `resetPeriodIfDue`/`periodOf`；`UserController` 加 `GET /api/user/quota`；新增 `QuotaPeriodTest`（7 个）+ `QuotaServiceTest` 扩到 29 个 | ✅ **惰性重置**：不跑 `@Scheduled`（挂了会导致全员配额不刷新、多实例还要抢锁），改为校验/查询时顺手判断；并发只命中一个，天然幂等。✅ `007` 未执行时**降级为按累计用量判定**（保守，不放行），周期值非法按 `NONE` —— 行为与 `003` 完全一致。⚠️ **2026-10-01 更正**：这里的「降级」只覆盖手写的 `resetQuotaPeriod`；MyBatis-Plus 自动生成的查用户 SQL 会带上这两列，缺列会让**登录 / 注册直接 500**，所以 `007` 是**必须执行**项（见 §6.13）。✅ 查询接口失败返回 `degraded=true` 而不抛异常；身份取自 `UserContextHolder`，**不接受入参**（防越权）。⚠️ **需执行 `007`** 才能用到周期重置；两个 yml 补 `period: NONE`。测试 190（178 通过 + 12 人工跳过），0 失败。§6.13 局限 ① 已划掉 |
| 2026-09-30 | **P3-4 完成**：API 文档（SpringDoc 自动生成 + 手写 SSE 契约） | 父 `pom.xml` 加 `springdoc.version=2.8.13`（dependencyManagement）+ web 模块引 `springdoc-openapi-starter-webmvc-ui`；新增 `nexus-agent-web/.../config/OpenApiConfig`（元信息 + `token` 请求头安全方案 + 指向 `docs/sse-contract.md`）、`OpenApiConfigTest`（4 个）；8 个 Controller 全部补 `@Tag`，关键接口补 `@Operation`，4 个免鉴权接口加 `@SecurityRequirements`；`WebInterceptorConfig` 白名单加 swagger 路径；`application.yml` 默认开、`application-prod.yml` 默认关 | ⚠️ **安全取舍**：Swagger 路径免鉴权（否则页面自身不带 token 打不开），因此**是否暴露只由 `springdoc.*.enabled` 决定**，prod 默认 `false`。⚠️ SSE 接口**不在** OpenAPI 里描述帧结构（OpenAPI 表达不了同一连接内的事件序列），只在 `@Operation` 里把人引到手写契约。⬜ **待用户验证**：重启后 `/v3/api-docs` 应返回 200、`/swagger-ui.html` 可打开；若 SpringDoc 与 Boot 3.5 有兼容问题导致启动失败，把两个 `enabled` 设为 `false` 即可降级。新增 §6.17；README 补「API 文档」章节；测试 194（182 通过 + 12 人工跳过），0 失败 |
| 2026-09-30 | **P3-3 完成**：部署形态固化（修 build+volumes 混用、应用镜像、健康检查、优雅停机） | 新增根目录 `Dockerfile`（maven 多阶段 → `temurin:21-jre`，非 root + `HEALTHCHECK` + `exec java` 保 PID 1）、`.dockerignore`、`docker-compose.yml`（pgvector/redis/box/app 一键起，depends_on 走健康检查）、`.env.example`（**应用**的环境变量模板）；`nexus_agent_box/docker-compose.yml` **去掉源码挂载并加 healthcheck**，热重载移到新建的 `docker-compose.override.yml`（Compose 自动合并）；`nexus_agent_box/Dockerfile` 装 curl 供健康检查；`application.yml` 加 `server.shutdown=graceful` + `timeout-per-shutdown-phase=30s` + actuator（只暴露 health/info、`show-details: never`）；`WebInterceptorConfig` 白名单加 `/actuator/health`、`/actuator/info`；`.gitignore` 忽略 `/.env` 与 `/nexus_agent_box/.env` | ✅ **修掉的坑**：`build: .` + 挂载源码混用 → 跑的代码 ≠ 镜像里的代码（同一镜像不同行为、"改了没生效"最难排查），现拆成「主文件以镜像为准 + override 只服务开发」。⚠️ **安全**：actuator 只开 health/info（`env` 会把库密码与 API Key 原样吐出），health 免鉴权故 `show-details: never`。⚠️ **容器里的坑**：镜像里没有 `application-dev.yml` → 必须 `SPRING_PROFILES_ACTIVE=prod`；互访用 compose 服务名而非 localhost（写 localhost 会连到自己）。⬜ **未在真机验证**：本机无 Docker，`docker build` / `compose up` 未实跑，首次使用请先 `docker compose config` 与 `docker build` 各跑一遍。新增 §6.18；README 补「部署」章节；测试仍为 194，0 失败 |
| 2026-10-01 | **P3-1 转向**：前端由用户自己写，本仓库产出 `docs/前端开发指南.md` | 新增 `docs/前端开发指南.md`（12 节）：环境准备（⚠️ 后端未配 CORS → 必须 dev proxy / 同源）、鉴权 `token` 头、统一响应 `Result`、全部 37 个接口的请求/响应结构、SSE 前端视角（6 个必踩坑 + 状态机）、页面与路由清单 + 三轮迭代范围、**极简黑白色板与组件规范**、联调顺序、上线 Checklist、后端未做清单；`docs/sse-contract.md` §4 修正（401 实际返回 JSON `Result` 而非纯文本 `NOT_LOGIN`，并补 CORS 提醒）；README 文档索引登记；`重构计划.md` P3-1 状态改为进行中 | ⚠️ **用户拍板**：不写前端代码，只出文档。⚠️ **教训（已更正）**：这里原写「全仓库搜不到任何 CORS 配置」是**错的**——只 grep 了 `addCorsMappings|@CrossOrigin|allowedOrigin`，漏掉了走 `CorsFilter` 的 `CorsConfig`。实际**一直有**跨域配置，且是 `allowedOriginPatterns("*")` + `allowCredentials(true)`（对任意网站开放）。排查跨域请直接 grep `cors`（不区分大小写）。该配置已于同日修复为配置驱动 + prod 默认关。⬜ **待用户确认 3 件事**：是否加 CORS 配置类、skills 无列表接口首版是否不做、是否补「会话重命名 / 搜索」接口。纯文档改动，未跑测试 |
| 2026-10-01 | **后端三处修复**：① YAML 重复键阻断启动 ② 跨域对任意网站开放 ③ 登录拦截器不清 ThreadLocal | ① `application.yml` 合并重复的 `spring:` 顶层键（原写法让 Boot 抛 `found duplicate key spring`，**应用起不来**，且 `spring.profiles.active=dev` 与 `spring.threads.virtual.enabled=true` 会被静默丢弃）；② `CorsConfig` 由 `allowedOriginPatterns("*")` + `allowCredentials(true)` 改为**配置驱动**：`nexus.agent.cors.enabled`（prod 默认 false、dev 默认 true）+ `allowed-origins`（dev 默认 `localhost:5173`），放行 `token`/`Content-Type` 头、不开 credentials、为空则不注册任何规则（**不**退化成放行所有）、配 `*` 打 WARN；③ `LoginCheckInterceptor` 补 `afterCompletion` → `UserContextHolder.removeUserId()` | ⚠️ ①②是**安全/可用性**问题：①会让应用完全起不来（P3-3 引入，未重启过所以没暴露）；②等于把接口对任意网站开放。③在开着虚拟线程时不会立刻炸，属**消除隐患**：一旦线程被复用，下一个请求会读到上一个用户的 userId（越权且极难复现）。新增 `ApplicationYmlTest`（4）、`CorsConfigTest`（5）、`LoginCheckInterceptorTest`（3）。测试 **206**（194 通过 + 12 人工跳过），0 失败。§15 补两项 cors 配置；`docs/前端开发指南.md` §0/§1.2/§11/§12 与 `docs/sse-contract.md` §4 一并更正 CORS 描述 |
| 2026-10-01 | **补两个会话接口（P3-1）**：重命名 + 搜索 | `PUT /api/history/{sessionId}/title`（body `{title}`，最长 100）与 `GET /api/history/search?keyword=`；新增 `RenameSessionDTO`、`ChatSessionSearchVO`、`ChatMemorySearchHit`；`ChatHistoryListMapper#updateTitleBySessionAndUserId`（**带 user_id**，顺带刷 update_time）；`ChatMemoryMapper.xml#searchHits`（jsonb 抽正文 + ILIKE）；`ChatHistoryListServiceImpl#rename/search`（合并标题与正文命中、去重、排序）；`AgentProperties` 新增 `history.*` 三个参数 | ⚠️ **三个不这么做就出事的点**：① 改标题 SQL 不带 `user_id` = 越权写别人会话；② 搜索不能直接 `content::text ilike` —— `content` 是 jsonb，会把 `type`/`text`/`USER` 这些**JSON 键名**当成正文，用户搜 `type` 会命中全部会话；③ `escape '\'` 必须与 `toLikePattern` 的转义成对出现，少任何一方，用户搜一个 `%` 就退化成匹配全部。⚠️ **索引**：匹配表达式含 `jsonb_array_elements`（set-returning），PG 要求索引表达式 immutable，**建不了表达式索引** → 目前全表扫描，加速路径记在 `docs/sql/README.md`「尚未处理」（**未新增 008**）。新增 `ChatHistoryListServiceImplTest`（20）。新增 §6.19；§5.2 文件地图、§7 API 一览、§15 配置表同步；`docs/前端开发指南.md` §4/§5.2/§7/§9/§11/§12 同步（原「待拍板」第 2 条关闭） |
| 2026-10-01 | **上线前复核：更正 `007` 的严重性** —— 不是「可选」，是**必须执行** | `docs/sql/README.md`（007 状态改为必须 + 文件表加 ⚠️ + 复核查询加第 6 条）、`AGENTS.md` §6.13（「失败降级」限定为仅 `resetQuotaPeriod` 那条 UPDATE，并加醒目警告段 + P2-8 行补更正）、`docs/后端变更review.md`、`重构计划.md` | ❗**我之前的结论是错的**：原以为「007 不跑也能用，只是没有周期重置」。实际 `User.tokenPeriod`/`tokenPeriodStart` 是普通字段，**MyBatis-Plus 自动生成的 SQL 会带上这两列**，缺列时表现为「应用能启动，**一登录就 500**」。通用教训：判断"迁移没跑能不能用"必须区分**手写 SQL 的降级**（可以 try-catch）与 **MP 自动生成 SQL**（无法降级） |
| 2026-10-01 | **修：用户加的 CORS 白名单在 prod 不生效** | `nexus-agent-web/src/main/resources/application-prod.yml` 改为 `enabled: true` + `allowed-origins: http://120.235.30.202:5173`（注释里写明原因） | ❗**坑**：`application-prod.yml` 会**覆盖** `application.yml` 的同名配置；而 `CorsConfig` 是 `@ConditionalOnProperty`，`enabled: false` 时**整个配置类不注册** —— 在 `application.yml` 里加了白名单也完全不生效，且不报错 |
| 2026-10-01 | **环境变量导出为文件（P3-3 收尾）**：`.env.example` 补全 + 新增部署前自查脚本 | `.env.example` 重写为四节（必需 = prod yml 的 `${}` 占位符 + 代码读的 `API_KEY_SECRET`；建议 `JWT_SECRET`/`OSS_*`；可选 `BASE_URL`/`SPRING_PROFILES_ACTIVE`/`JAVA_OPTS`；填值注意）；新增 `scripts/check-env.sh`；新增 `.gitattributes`（`*.sh`/`Dockerfile`/`*.yml`/`*.yaml`/`.env.example` 强制 LF）；`README.md` 部署章节加「§0 先填环境变量再自查」+ 目录结构/文档索引登记；`AGENTS.md` §4.1 加 OSS 行、§5.2 补两行（顺带删掉重复的「向量库 Bean」行）、§6.18 加「Spring Boot 不会自动读 .env」段 | ❗**补上的最大缺口**：旧的 `.env.example` **完全没有 OSS 两项**。Java 侧 `AliOssUtil` 走阿里云 SDK 的 `EnvironmentVariableCredentialsProvider`，**只认 `OSS_ACCESS_KEY_ID`/`OSS_ACCESS_KEY_SECRET`**；沙盒侧 oss2 认 `ALIBABA_CLOUD_*` —— 两侧类名同名但读的变量不同，极易填反。不填的表现是「启动不报错、一上传就失败」。❗ **Spring Boot 不会自动读 `.env`**：compose 的 `env_file` 是 Docker 的能力，直接 `java -jar` 必须 `set -a; . ./.env; set +a`。`check-env.sh` 查缺项/占位符/写法（`export` 前缀、`KEY = value`、CRLF），密钥**打码输出**，退出码 0/1；已用 mock `.env` 实跑验证三种分支。纯文档+脚本改动，未跑 Java 测试 |
| 2026-10-01 | **环境变量清单订正**：对齐 prod yml 新增的 3 个变量，并更正"哪些算必需" | `.env.example` 重写（必需 = prod yml 的 `${}` 占位符：`SERVICE_IP`/`DATABASE`/`REDIS_PWD`/`DEEPSEEK`/`MOONSHOT`/`ALI_AI_KEY`/`MAIL_*` + 代码读的 `API_KEY_SECRET`；建议只剩 `JWT_SECRET`/`OSS_*`）；`scripts/check-env.sh` 改为**从 `application-prod.yml` 现抽 `${}` 占位符**作为必需清单（yml 找不到时退回内置清单）；`AGENTS.md` §4.1 加 `DATABASE`/`REDIS_PWD` 并把 `AI_KEY` 标为现名 `ALI_AI_KEY`、§6.10 补 prod 下说明 | ❗**更正我自己上一行的错误分类**：原把 `MOONSHOT`/`AI_KEY`/`MAIL_*` 写成"建议（不填也能启动）"。实际 `application-prod.yml` 里它们写作 `${XXX}` 且**没有默认值**，Spring 建 Bean 时解析不到会抛 `Could not resolve placeholder` → **启动失败**，比 `StartupConfigValidator` 的 `@PostConstruct` 更早。所以 prod 部署时**缺哪个都是起不来**。❗ 变量清单改为**动态抽取**而非写死，避免在 yml 改名后漏查漂移。用真实 prod yml 实跑三个分支验证：缺项（EXIT=1）/ yml 缺失兜底 / 全齐（EXIT=0）|
| 2026-10-03 | **修：首次设置 MCP Token 必然 500**（`UserConfigServiceImpl.saveOrUpdateMcpToken` 空指针） | `UserConfigServiceImpl` 补 `config == null` 的**建行**分支，并给 `getApiConfig` / `saveOrUpdateAPIConfig` 兜住 `llm_api_token` 为 null；`UserConfigMapper` + XML 新增 `updateMcpTokenById`（只改 `mcp_token`/`salt`）；`UserConfigMapper.xml#save` 的 `::json` 改 `::jsonb` 并加 `jdbcType=VARCHAR`；新增 `UserConfigServiceImplTest`（5 个）；`nexus-agent-web/pom.xml` 给 surefire 注入测试专用 `API_KEY_SECRET` | ❗**方法名叫 saveOrUpdate，实际只写了 update 分支**：`getById(userId)` 返回 null 时直接 `config.getSalt()` → `NullPointerException`。任何「还没配过 LLM API Key 就先配 MCP」的用户 **100% 触发**（线上实测即如此）。❗ **连带两个坑（一起修了）**：① **不能改用 MP 的 `updateById`** —— `llm_api_token` 是 **jsonb** 列，MP 把 Java String 当 varchar 传进去，PostgreSQL 报 `column is of type jsonb but expression is of type character varying`（这也是原作者另写 `updateAPIconfigById` 手写 SQL 的原因）；② 新建行时 `llm_api_token` **不能留 null** —— 该列 `jsonb NOT NULL`，`#{llmApiToken}::jsonb` 传 null 会让 PG 报 `could not determine data type of parameter`，而且后面 `getApiConfig()` 里 `.toString()` 会 NPE，所以统一写成 `[]`。❗ 单测需反射注入 MP 的 `protected baseMapper` 字段（Spring 环境由框架注入，纯单测没有）。测试 **234**（222 通过 + 12 人工跳过），0 失败 |
| 2026-10-03 | **修：`JWT_SECRET` 非 Base64 时全站 500**（`JwtUtil` 类初始化永久失败） | `JwtUtil` 密钥解析改为**惰性**且**容错**：能按 Base64 解开就用，解不开就当普通字符串的 UTF-8 字节，不足 32 字节用 SHA-256 派生（HS256 硬要求）；`EncryptorFactory` 同样改为惰性解析 | ❗**根因**：原实现在 `static {}` 块里做 `Base64.getDecoder().decode(System.getenv("JWT_SECRET"))`，用户填了带 `-` 的普通字符串 → `IllegalArgumentException: Illegal base64 character 2d` → **类初始化永久失败** → 之后每次访问都是 `NoClassDefFoundError: Could not initialize class JwtUtil` → 登录、鉴权、所有接口一起 500，报错完全指不到 JWT_SECRET。❗ **通用教训**：**不要在 `static` 初始化块里做可能失败的外部依赖解析**（环境变量 / 文件 / 网络），失败一次就永久毒化整个类，且错误信息与真因脱节。改成惰性解析后，缺值只在真正用到的那一次报错。新增 `JwtUtilTest`（6，含"带连字符"、"短于 32 字节"、"换密钥后旧 token 失效"） |
| 2026-10-03 | **统一：`System.getenv` 全部改为走 Spring**（配置项优先，环境变量兜底） | 新增 `config/RuntimeSecretInitializer`（启动时把配置值注入两个 static 工具类）；`JwtUtil` + `EncryptorFactory` 加 `setConfiguredSecret()`；`WebClientConfig` 的 `BASE_URL` 改 `@Value("${nexus.agent.sandbox.base-url:${BASE_URL:http://localhost:8000}}")`；`NexusAgentWebApplication.main` 删掉 `getenv` 日志（容器启动前拿不到配置值，会显示成默认值误导人）；`StartupConfigValidator` 的 `Requirement` 加 `envKey` 字段，`JWT_SECRET`/`API_KEY_SECRET` 两项改为同时认配置项与环境变量 | ❗**用户报的现象**：「JWT_SECRET / API_KEY_SECRET 我设置了，但说我没有设置，我在配置项设置了啊」—— 因为这两个类直接 `System.getenv()`，**绕开 Spring**，写在 yml / `.env.properties` / 1Panel 面板配置里的一律读不到。与 2026-10-02 的 `AliOssUtil` 是**同一类问题**。❗ 统一写法：`@Value("${nexus.agent.xxx:${ENV_VAR:默认值}}")` —— Spring 的 Environment 自带 systemEnvironment 属性源，配置项与环境变量都能覆盖，不需要再调 `getenv`。新增 `EncryptorFactoryTest`（4）、`RuntimeSecretInitializerTest`（5，用 **ApplicationContextRunner 起真实迷你容器**验证 `@Value` 注入与 `@PostConstruct`，不依赖数据库）。⚠️ 已写入 §4.1：**新增代码禁止再写 `System.getenv`**。测试 **249**（237 通过 + 12 人工跳过），0 失败 |
| 2026-10-03 | **实测发现：`pgVectorEmbeddingStore` 在建 Bean 时就真实连库** → 数据库不可用时应用**起不来**（不是"能启动但登录 500"） | 仅记录，未改代码 | 用假配置实跑 jar 验证时撞到：`PgVectorEmbeddingStore` 的 `init` 在建 bean 阶段就执行建表/连库 → `PSQLException: Connection refused` → `Application run failed`。与「007 缺列」的表现不同：DB **连不上** = 起不来；DB 连上了但**缺列** = 能起来、登录才 500。排查时要先分清是哪一种。⚠️ 本机无 PostgreSQL，启动验证只能走到「配置加载 + `BASE_URL` 注入成功」这一步（日志已确认 `沙盒服务地址 BASE_URL = http://127.0.0.1:8000` 来自配置文件） |
| 2026-10-03 | **修：带图片 / 带文档聊天直接 500**（`ChatMessageConverter` 附件字段 NPE） | `ChatMessageConverter` 抽出 `requireMetadata()`：缺字段时抛 `ValidationException`（→400），报错里写清「消息类型 + 缺的字段名 + 当前传了哪些字段 + 正确写法」；新增 `resolveExtension()`：`extension` 没传时**从 fileUrl 推断**（剥掉 query 再取扩展名），推断不出才报错；`FILE_NAME` 改为可选（缺省「未命名文件」）。`docs/前端开发指南.md` §对话请求体修正 metadata 字段名。新增 `ChatMessageConverterTest`（7） | ❗**根因是我写的文档错了**：前端指南里把附件的字段名写成 `url`，而后端 `MetadataKeyContent.FILE_URL` 是 **`fileUrl`（驼峰）** —— 前端照文档传 `url`，`metadata.get("fileUrl")` 恒为 null → `NullPointerException` → 500，日志只有 `Cannot invoke "Object.toString()" because the return value of "Map.get(Object)" is null`，完全看不出是哪个字段。❗ **代码侧的教训**：对**外部传入的 Map** 直接 `.get(k).toString()` 等于把「缺字段」变成 500 + 无信息 NPE；必须显式校验并给出可读报错。❗ 元数据 key 是驼峰不是下划线（`fileUrl`/`fileName`/`fileSize`/`extension`/`id`），文档与前端都要按这个来 |
| 2026-10-03 | **修：图片消息改用真正的 `ImageContent`，多模态模型能看到图了** | `ChatMessageConverter` IMAGE 分支从「URL 包 TextContent」改为 `ImageContent.from(url)`；新增 `model/MultimodalTokenCountEstimator`（文本委托 `OpenAiTokenCountEstimator`，`ImageContent` 按常数估算，`nexus.agent.memory.image-tokens` 默认 1024）；`ChatContextFactory` 换用它；`ChatMemoryServiceImpl` 新增 `stripLegacyWrappers()` 剥存量数据里的包裹标记（兼容两括号带 id / 三括号无 id 两种格式）；`AgentProperties.Memory` 加 `imageTokens`。新增 `MultimodalTokenCountEstimatorTest`（6）；`ChatMessageConverterTest` 图片用例改为断言 `ImageContent` | ❗**三个连环问题的根因**：① 模型"看不到图" —— 原实现把 URL 包在 TextContent 里，模型收到的只是一串 URL 文字，支持视觉的模型也说"我没看到图"；② 改 `ImageContent` 后 token 估算直接炸 —— **实测复现**：`OpenAiTokenCountEstimator.estimateTokenCountInMessage()` 遇到 ImageContent 抛 `IllegalArgumentException: Unknown content type`，`TokenWindowChatMemory` 裁剪窗口即炸（这就是当年图片只发 URL 文本的原因）；③ 历史刷新显示奇怪 —— 存量数据里包裹标记（`<<<IMAGE_START>>>...`）被当成正文存进了 chat_memory，读取时 `isFileOrImageWrapper` 只认三括号精确格式 + `hasSingleText()` 分支根本不过滤。❗ **经验**：估算器这类库对多模态内容的支持要**实测**（一个 10 行的探针测试就能定方案）；外部输入的文本在入库前就要设计好「机器可识别 + 读取可剥离」的边界。⚠️ **遗留**：不支持视觉的模型**不再**会收到 ImageContent —— 由模型元数据 `vision` 决定（默认 false，降级为 URL 文本），见下一条 |
| 2026-10-03 | **模型元数据：视觉 / 上下文窗口 / 最大输出**（jsonb 加字段，**不改表**） | `domain/Model` 加 `vision`(Boolean, 默认 false) / `contextWindow`(Integer, 默认 256000) / `maxOutputTokens`(Integer, 默认 32000)；新增 `model/ModelCapabilities`（`of(Model)` 兜底 null、`memoryWindow(globalMax)` = min(全局, 窗口−输出)，输出比窗口大时保底 2048）；`ChatContextFactory` 抽出 `matchModel()` + 新增 `resolveCapabilities()`（createModel 与 ChatServiceImpl 复用同一套匹配逻辑）、记忆窗口改为动态、模型构建加 `.maxTokens()`；`ChatMessageConverter.toContents(messages, vision)` 两参重载（vision=false 时图片降级为 URL 文本并 WARN）；`ChatServiceImpl` 转换消息前先解析能力。新增 `docs/模型能力配置（前端）.md`；新增 `ModelCapabilitiesTest`（6）、`ChatMessageConverterTest` 加降级用例 | ❗**用户判断正确**：模型元数据存在 `user_config.llm_api_token`（**jsonb**）里，加字段**不需要改表**，老数据反序列化为 null 由 `ModelCapabilities.of()` 兜底。❗ **关键设计取舍**：`vision` 默认 **false** —— 不支持视觉的模型收到 `image_url` 会被上游直接拒绝（400），而降级成 URL 文本最坏只是看不到图，**宁可降级不要报错**。❗ 记忆窗口公式：从「全局写死 100000」改为 `min(全局上限, contextWindow − maxOutputTokens)` —— 256k 窗口不再浪费，8k 窗口也不会被上游拒。全量 **269** 测试 |
| 2026-10-03 | **SSE 超时不再杀任务 + 心跳保活** | `AgentProperties.Sse.timeout` 默认 120s → **1800s**；`SseResponseConverter` 新增 `disconnected` 标志与 `disconnect(String)`（只停发送，不终止任务），新增 15s 一次的心跳注释帧（共享 daemon 调度池，`:ping`，对 EventSource 透明）；`ChatServiceImpl` 的 `onTimeout` 从 `writer.onError(...)` 改为 `writer.disconnect("SSE 连接超时")`；`dispatch` 发送失败改为 `disconnect` 而不是 `completeWithError`；`finish()` 加 finally 取消心跳、`completeWithError` 吞掉 emitter 关闭异常。新增 `SseResponseConverterTest`（5） | ❗**原实现是最坏的组合**：`onTimeout → onError → isFinished=true` —— 而 `TokenStream` **并没有被取消**，还在继续跑（继续烧钱、继续调工具），但 `writeThinking/writeContent` 因 isFinished 全部直接 return，**产出被丢弃**，标题也不生成。前端表现为 pending 2 分钟后静默失败。❗ **修正后的语义**：`disconnected`（传输通道没了）与 `isFinished`（任务结束）是两个独立标志 —— 连接断了任务照跑完，消息经 ChatMemoryStore 落库、标题照常生成，用户**刷新页面就能看到完整回复**。❗ **心跳的另一个必要性**：agent 干活期间可能几分钟没有任何事件，Nginx 等反代默认 60s 读超时会掐断连接（前端表现为"不动了"），心跳注释帧可保活且不影响事件契约。⚠️ 不需要"进程守卫"：TokenStream 本就独立于连接，缺的只是别让断开去终止任务。全量 **274** 测试 |

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
| `ChatContextFactory` 把记忆工具绑在 `enableRag` 上 | ✅ 已修 | 长期记忆与知识库无关；`MemoryTool` 改为常驻注册，`enableRag` 只控制 `RagTool`。**行为变更**：不勾选知识库时也会多出 2 个记忆工具。⚠️ 2026-10-04 起 `enableRag` 与 `RagTool` 均已下线，记忆工具仍常驻 |
| **全库无主键/唯一约束/外键/索引** | ✅ 已修 | 库被清空过、灾后 schema 缺约束。旧数据已确认可舍弃，故按代码需求**重新设计**基线：12 表全部补主键、4 个外键、9 个索引、`users.email` 唯一约束。详见 §9 |
| **`sys_file` 表在库中不存在** | ✅ 已修 | 该表曾丢失，导致文件上传必报 `relation does not exist`。依据 `SysFile` 实体 + `FileMapper.xml` 的 resultMap 推导出 DDL 并建表 |
| **`knowledge_base_file` 缺 `file_name` 列** | ✅ 已修 | `insertKnowledge` 会写入它、`resultMap` 也映射它，缺列导致**知识库入库与详情查询双双报错**。基线已含该列。⚠️ 2026-10-04 表与代码一并下线 |
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
| `nexus.agent.sse.timeout` | `1800s` | SSE 连接超时。**必须大于最慢一次模型调用**，否则复杂任务被掐断。⚠️ **不要在任何 profile yml 里写这个键**——写了会盖掉这里的默认（2026-10-05 的「2 分钟中断」就是 prod 残留 120s 干的） |
| `nexus.agent.sse.flush-max-chars` | `200` | 流式增量合并：攒够这么多字符就推一帧（P2-12，见 §6.14）。调大→帧更少更省但到达略慢 |
| `nexus.agent.sse.flush-interval` | `60ms` | 流式增量合并的兜底时间阈值（≈16 帧/秒，与屏幕刷新率相当） |
| `nexus.agent.memory.max-tokens` | `300000` | 对话记忆窗口的**全局天花板**。只影响送给模型的上下文，**不影响已入库的消息**。实际生效值 `= min(本值, 模型的 contextWindow − maxOutputTokens)`。⚠️ **不要在任何 profile yml 里写这个键**——写了会盖掉代码默认（2026-10-06 晚的「1M 模型只剩 2.4 万窗口」就是 prod 残留 24000 干的），有 `MemoryWindowDriftTest` 盯着 |
| `nexus.agent.memory.max-history-messages` | `200` | 单次对话最多从库里取回多少条历史（SQL 层截断，与上面的 token 窗口是**两套独立机制**） |
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
| `nexus.agent.tools.hidden-tools` | `create_box, delete_box, search_user_memory, save_user_data, record_log` | 对**前端**隐藏的工具名（见 §6.21）。照常执行、照常进模型上下文，只是不推 SSE 事件、不进历史。⚠️ **是 List：yml 里声明会整份取代默认清单而非合并**，故任何 profile yml 都不要写这个键（护栏 `ToolVisibilityDriftTest`） |
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
| `spring.servlet.multipart.max-file-size` | `8MB` | 单个上传文件上限。⚠️ Spring Boot **默认只有 1MB**，技能包常要带模板/参考资料会被 413 拒掉。`SkillController` 里再叠一层同样的 8MB 应用层校验 |
| `spring.servlet.multipart.max-request-size` | `10MB` | 单次请求总上限 |

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

**④ 怎么做（需要你操作，AI 代劳不了）**：E2B 基础镜像**不含** `python-docx`/`openpyxl`/`python-pptx`，
也不含 `dotnet`。少了这些，AI 只能每次在沙盒里现场 `pip install`（慢、依赖网络、可能失败），
或者像 2026-10-05 那次一样**改用 python-docx 手工拼 OpenXML 并自写校验脚本绕过去** ——
能出文档，但技能自带的 `scripts/dotnet` CLI 全程用不上。

**推荐**：在 E2B 控制台基于基础模板构建**自定义模板**，预装下面这些，
然后创建沙盒时指定该 template。`e2b/Dockerfile.template` 已备好，可直接改：

```dockerfile
# e2b/Dockerfile.template —— E2B 自定义模板（构建前需先在 E2B 控制台建 code-interpreter-v1）
FROM e2b/code-interpreter:1.0.0

# ① Office 文档三件套：AI 生成 docx/xlsx/pptx 的主力工具链
RUN pip install --no-cache-dir python-docx openpyxl python-pptx matplotlib

# ② dotnet SDK：技能里的 scripts/*.csx 靠它跑（minimax-docx 等技能的 CLI 全部依赖）
#    .NET 8 LTS，装到 /usr/local/dotnet 并进 PATH
ENV DOTNET_ROOT=/usr/local/dotnet \
    PATH=/usr/local/dotnet:$PATH \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1
RUN curl -fsSL https://dot.net/v1/dotnet-install.sh \
      | bash -s -- --channel 8.0 --install-dir /usr/local/dotnet \
 && ln -sf /usr/local/dotnet/dotnet /usr/local/bin/dotnet \
 && dotnet --version

# ③ 中文字体：不装的话 matplotlib 出图的中文全是豆腐块
RUN apt-get update && apt-get install -y --no-install-recommends fonts-noto-cjk \
 && rm -rf /var/lib/apt/lists/*
```

构建与启用（两条命令，请你在本机执行）：

```bash
cd nexus_agent_box/e2b
e2b template build <your-template-id> --dockerfile Dockerfile.template
```

建好后，把 `app/routers/box.py::create_box` 的 `Sandbox.create()` 改成
`Sandbox(template=<your-template-id>)` —— 这是**唯一**需要改代码的地方，
`box.py` 里其余逻辑不用动。⚠️ 模板 id 要写进 `.env`（新增变量 `E2B_TEMPLATE_ID`），
**不要硬编码在 Java/Python 里**，否则换模板要改代码重新发版。

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
