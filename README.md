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
| PostgreSQL | **16** + **pgvector 扩展**（+ `pg_trgm`，可选） | `vector`：RAG 向量检索必需，`001_baseline.sql` 会 `CREATE EXTENSION vector`；`pg_trgm`：长期记忆模糊检索（`006`），**不装也能跑**，只是检索质量降级为纯字面匹配 |
| Redis | 任意版本 | 缓存用户配置、验证码、会话归属 |
| 外部服务 | E2B API Key、阿里云 OSS、LLM API Key | 见下方配置 |

### 1. 建库并初始化表结构

```bash
# 建库（扩展由脚本创建，但要确保服务端已安装 pgvector / pg_trgm 这两个 contrib 模块）
createdb -h <PG_HOST> -U postgres nexus_agent

# 1) 执行基线（12 张表 + 2 枚举 + 4 外键 + 9 索引）
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/001_baseline.sql

# 2) 按序号执行后续增量（顺序不能颠倒）
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/002_drop_skill_mcp_information.sql
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/003_add_user_token_quota.sql
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/004_add_sys_file_session_id.sql
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/005_add_sys_file_session_index.sql
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/006_add_user_memory_trgm_index.sql
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/007_add_user_token_quota_period.sql
```

> 也可以用 Navicat：右键库 → 运行 SQL 文件，**按 001 → 002 → … → 007 的顺序**各跑一次。
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

### 3. 还需要 5 个环境变量（不在 yml 里）

| 变量 | 是否必需 | 说明 |
|---|---|---|
| `JWT_SECRET` | 建议设置 | Base64 的 HMAC-SHA256 密钥。**不设置会随机生成 → 每次重启后所有 token 失效**。生成：`openssl rand -base64 32` |
| `API_KEY_SECRET` | **必需** | 用户 API Key 的加密主密钥。不设置则首次保存配置时报错；**设置后不要更改**，否则已加密的密钥无法解密 |
| `BASE_URL` | 可选 | 沙盒服务地址，默认 `http://localhost:8000` |
| `OSS_ACCESS_KEY_ID` | 建议设置 | 阿里云 OSS。不设置启动不报错，但**一上传头像/文件就失败** |
| `OSS_ACCESS_KEY_SECRET` | 建议设置 | 同上 |

> Java 侧用的是阿里云 SDK 的 `EnvironmentVariableCredentialsProvider`，它**只认 `OSS_` 前缀**这两个名字；
> 沙盒服务（Python / oss2）用的是 `ALIBABA_CLOUD_` 前缀 —— 两套名字不一样，别填反。
> 完整清单与说明见 [`.env.example`](./.env.example)。

### 4. 启动沙盒服务（独立进程，8000 端口）

```bash
cd nexus_agent_box
uv run main.py          # 开发模式
# 或
docker compose up -d    # 容器模式（改完代码要 docker compose up -d --build）
```

> `docker-compose.yml` 里现在**不再挂载源码**（镜像为准）；
> 开发期热重载由自动合并的 `docker-compose.override.yml` 提供。详见下节。

### 5. 启动应用（8080 端口）

```bash
./mvnw spring-boot:run -pl nexus-agent-web
# 或
mvn clean package -DskipTests && java -jar nexus-agent-web/target/nexus-agent-web-*.jar
```

---

## 部署（P3-3）

### 0. 先把环境变量填好（跑之前先自查一次）

所有环境变量的清单、默认值、填法都在 [`.env.example`](./.env.example) 里，复制后替换占位符即可：

```bash
cp .env.example .env                                   # 应用（Java）
cp nexus_agent_box/.env.example nexus_agent_box/.env   # 沙盒服务（Python）
vim .env                                               # 把所有 <<...>> 换成真实值
bash scripts/check-env.sh                              # 自查：缺什么、有没有漏改的占位符
```

`scripts/check-env.sh` 会检查必需项是否填了、占位符有没有漏改、写法有没有问题
（`export` 前缀、`KEY = value` 带空格、CRLF 行尾 —— 后两个会让变量**静默失效**，最难查），
密钥只打码显示，不会整段打出来。退出码 `0` = 可以启动，`1` = 有必需项缺失。

⚠️ **Spring Boot 不会自动读 `.env`**：走 docker compose 时由 `env_file` 注入没问题；
但**直接 `java -jar` 必须自己 export**，否则所有变量为空、启动自检会 fail-fast 拒绝启动：

```bash
set -a; . ./.env; set +a
exec java $JAVA_OPTS -jar nexus-agent-web.jar
# systemd 的话用 EnvironmentFile=/path/to/.env，别写成 Environment=
```

### 一键起全套（PG + Redis + 沙盒服务 + 应用）

```bash
cp .env.example .env                                   # 填好占位符
cp nexus_agent_box/.env.example nexus_agent_box/.env   # 沙盒服务也要一份
docker compose up -d --build
docker compose ps     # 四个服务都 healthy 才算起来
```

| 服务 | 端口 | 说明 |
|---|---|---|
| `postgres` | 5432 | `pgvector/pgvector:pg16`（`001` 需要 vector 扩展）。数据卷首次创建时自动按序执行 `docs/sql/001…007` |
| `redis` | 6379 | 密码 `redis`（`application-prod.yml` 里写死了，改一处要改两处） |
| `box` | 8000 | FastAPI 沙盒服务 |
| `app` | 8080 | Java 应用（`SPRING_PROFILES_ACTIVE=prod`，镜像里没有 dev 配置） |

⚠️ **用外部已有的 PG / Redis 时，光改 `.env` 不够**：`docker-compose.yml` 的 app 服务里
硬写了 `SERVICE_IP: postgres` / `BASE_URL: http://box:8000`，而 **`environment:` 优先级高于
`env_file:`** —— 它会**覆盖**你在 `.env` 里填的 IP。必须做三件事：删掉 `postgres` / `redis`
两个 service、删掉 app 的 `depends_on`、删掉 app.environment 里那两行。
不报错，只表现为「明明填了 IP 却连不上（或连到了空库）」，极难查。

⚠️ **别**把有真实数据的目录挂到 `pgdata` 卷上 —— 初始化会执行 `001_baseline.sql`，它**会 DROP 全表**。

### 服务器部署：app + box 两个容器（数据库在外部）

用 **`docker-compose.server.yml`**，别用根目录那个 `docker-compose.yml` ——
后者会新建一个空 PG，并在首次启动时执行 `001_baseline.sql`（**它会 DROP 全表**）。

```bash
cp .env.example .env                                  # 填真实值
cp nexus_agent_box/.env.example nexus_agent_box/.env  # 沙盒服务也要一份
docker compose -f docker-compose.server.yml up -d --build
docker compose -f docker-compose.server.yml ps         # box / app 都要 healthy
docker compose -f docker-compose.server.yml logs -f app
```

| 服务 | 端口 | 说明 |
|---|---|---|
| `box` | 8000（**仅内部网络**） | FastAPI 沙盒服务，只给后端调用，不映射到宿主机 |
| `app` | 8080 → 宿主 | Java 应用；`BASE_URL=http://box:8000`（容器网络用服务名互访） |

✅ 服务器**不用装 JDK 21**：构建阶段自带 Maven + JDK，运行阶段自带 JRE。
⚠️ 两个前提：外部 PG / Redis 放行这台服务器，且库上先跑过 `docs/sql/007`。
⚠️ 需要跨域就往 `.env` 加 `NEXUS_AGENT_CORS_ENABLED` / `NEXUS_AGENT_CORS_ALLOWED_ORIGINS`
两行，**别写进 compose** —— `environment:` 会覆盖 `env_file:` 里的同名变量。

### 只跑应用镜像

```bash
docker build -t nexus-agent:local .
docker run --rm -p 8080:8080 --env-file .env nexus-agent:local
```

数据库在外部时，用 `docker run` 单跑（**别**用 compose 全套，它假定 PG/Redis 也在里面）：

```bash
docker build -t nexus-agent:local .
docker run -d --name nexus-agent -p 8080:8080 \
  --env-file .env \
  -e NEXUS_AGENT_CORS_ENABLED=true \
  -e NEXUS_AGENT_CORS_ALLOWED_ORIGINS=http://120.235.30.202:5173 \
  nexus-agent:local
```

⚠️ **容器里的 `localhost` 是容器自己**，跟直接跑 jar 不一样 —— 这是换 docker 最容易踩的一处：

- `BASE_URL` 默认 `http://localhost:8000`：沙盒服务跑在**宿主机**时要改成
  `http://host.docker.internal:8000`（Linux 还需 `--add-host=host.docker.internal:host-gateway`），
  或直接填宿主机 IP；跑在另一个容器里就用服务名（`http://box:8000`，需同一网络）。
- 数据库同理：填外部 IP 没问题；DB 在宿主机上就不能写 `localhost`。
- ⬜ 镜像**未实跑验证过**（开发环境无 Docker），首次请先单独 `docker build` 看能不能构建。

`.env` 按上一节填好（里面已含 `SPRING_PROFILES_ACTIVE=prod`，容器里没有 dev 配置）。
不打容器、直接 `java -jar` 的话记得先 `set -a; . ./.env; set +a`。

镜像内：非 root 用户、`-XX:MaxRAMPercentage=75`、`HEALTHCHECK` 打 `/actuator/health`、
`ENTRYPOINT` 用 `sh -c exec java` 保证 java 是 PID 1（**否则收不到 SIGTERM，优雅停机失效**）。

### 不打容器：直接在服务器上跑 jar

适合「PG / Redis 在外部」的场景（compose 那套假定它们也在同一个 compose 里）。

```bash
# 1) 本机打包
/d/dev_utils/maven/bin/mvn -pl nexus-agent-web -am clean package -DskipTests
# 产物：nexus-agent-web/target/nexus-agent-web-0.0.1-SNAPSHOT.jar

# 2) 上传（jar + 环境变量；沙盒服务要单独部署就再传整个 nexus_agent_box/）
scp nexus-agent-web/target/nexus-agent-web-0.0.1-SNAPSHOT.jar root@<服务器>:/opt/nexus-agent/app.jar
scp .env                                                     root@<服务器>:/opt/nexus-agent/.env
```

服务器上三个前置条件：

- **JDK 21**
- 沙盒服务跑在**同一台机器的 8000 端口**（`BASE_URL` 默认 `http://localhost:8000`；
  这里不是容器，`localhost` 就是指这台服务器本身，是对的）
- 外部 PG / Redis **放行这台服务器的 IP**（`SERVICE_IP` 指向的是外部地址）

```ini
# /etc/systemd/system/nexus-agent.service
[Unit]
Description=nexus-agent
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/nexus-agent
EnvironmentFile=/opt/nexus-agent/.env          # 用 EnvironmentFile=，不是 Environment=
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/nexus-agent/app.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now nexus-agent
journalctl -u nexus-agent -f                   # 看启动日志
curl -s localhost:8080/actuator/health         # 应返回 {"status":"UP"}
```

⚠️ systemd 的 `EnvironmentFile=` **不做 shell 解析**：文件里不要写 `export ` 前缀，
也不要写成 `Environment=`（后者只接受单行的 `KEY=value`）。值含 `#` 或空格时加引号。
改完 `.env` 要 `systemctl restart nexus-agent` 才生效。

**用运维面板（只能填一行启动命令）时**，靠 `set -a; . ./.env` 是不行的（那要 shell）。
好在 `.env` 的 `KEY=value` **恰好是合法的 properties 语法**，让 Spring Boot 直接把它当配置文件读：

```bash
java -Xms256M -Xmx1024M -jar app.jar --spring.profiles.active=prod --spring.config.additional-location=optional:file:./.env
```

四个要点：

1. ⚠️ `-Xmx` 这类 **JVM 参数必须放在 `-jar` 之前**（常见面板示例把参数写在后面，是无效的）。
2. `--spring.profiles.active=prod` **必须显式给**。`.env` 里写的 `SPRING_PROFILES_ACTIVE=prod`
   是环境变量的命名（大写下划线），而 profile 激活读的是字面键 `spring.profiles.active` ——
   从配置文件加载的属性**不会**做大小写反向映射（只有真正的系统环境变量才会）。
   不加这行会按默认的 dev 启动，然后因为镜像/目录里没有 `application-dev.yml` 直接失败。
3. `--spring.config.additional-location=optional:file:./.env`：把 `.env` 读进配置，
   所有 `${XXX}` 占位符都能解析。`optional:` = 文件不存在不报错（但那样就全空了，别删错）。
4. `./` 是**进程工作目录**：面板一般就是 jar 所在目录；不放心就写绝对路径
   `optional:file:/opt/nexus-agent/.env`。

⚠️ 这种读法下 `.env` 要按 properties 规则写：值**不要加引号**（引号会进值）、值里别用 `\`（是转义符）、
`#` 开头的行仍是注释。

✅ **这条命令已实测**（2026-10-02）：profile 正确激活为 prod、`.env` 全部占位符解析成功、
10 份 mapper XML 解析通过；唯一的失败点是故意指向不存在数据库的连接报错 ——
也就是说在数据库可达的服务器上，这条命令就是完整的启动命令。

❗ **容器里用不了这条** —— **容器里没有 `.env`**：`.dockerignore` 明确排除了它
（密钥不该进镜像）。`optional:` 前缀会让读不到文件时**不报错**，
结果所有 `${XXX}` 静默为空，表现为：

```
Caused by: PlaceholderResolutionException: Could not resolve placeholder 'MOONSHOT' in value "${MOONSHOT}"
```

（`MOONSHOT` 只是第一个被解析到的占位符，实际是所有变量都没进去。）

❗ **`.env` 这个文件名本身也不能直接用**（实测 2026-10-02）：Spring Boot 的配置加载器
**只认 `.properties` / `.yml` / `.yaml` 扩展名**，`.env` 会被拒：

```
Unable to load config data from 'file:./.env'
Caused by: File extension is not known to any PropertySourceLoader.
```

✅ **正确做法：复制一份改名为 `.env.properties`**（内容和 `.env` 一模一样即可，
`KEY=value` 本来就是合法 properties 语法），然后用：

```bash
java -Xms256M -Xmx1024M -jar app.jar --spring.profiles.active=prod --spring.config.additional-location=file:./.env.properties
```

✅ **这条已实测**：profile 激活 prod、全部占位符解析成功、走到数据库连接阶段
（已实测 2026-10-02，`.env` 与 jar 同级目录、工作目录即 jar 目录）。
`.env` 与 `.env.properties` 的差别只是扩展名 —— 前者给 shell/docker 用，后者给 Spring Boot 用。

容器场景另外两种等价做法：

- `--env-file .env`（`docker run`）/ `env_file: - .env`（compose）—— **推荐**。
  环境变量由 systemEnvironment 属性源提供，此时 `.env` 里的 `SPRING_PROFILES_ACTIVE=prod`
  **能**正常激活 profile（环境变量有大小写反向映射，配置文件没有），
  所以不需要再写 `--spring.profiles.active=prod`。
- `-v /opt/nexus-agent/.env:/app/.env:ro` 把文件挂进容器，再配合上面的 `.env.properties` 方案。

排查命令（看变量到底有没有进容器）：

```bash
docker inspect A_Agent --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E 'MOONSHOT|DEEPSEEK|SERVICE_IP'
```

同类问题也出现在 **1Panel 运行环境**之类的"面板帮你起容器"的场景里：
那个容器有自己的文件系统，你放在宿主机上的 `.env` 它根本看不到。

**两个兜底（不依赖文件、不依赖 shell，任何运行环境都生效）**

① 去掉 `optional:`，让路径不对时**直接报错**而不是静默为空：

```bash
java -jar app.jar --spring.profiles.active=prod --spring.config.additional-location=file:./.env
```

找不到文件会明确报 `Config data location 'file:./.env' does not exist`，
比"变量全空"好查得多。把 `.env` 放到面板的运行目录里（或用绝对路径 `file:/实际路径/.env`）。

② 实在搞不清文件在哪，就把变量**直接写进启动命令**（Spring Boot 的命令行参数，
键就是占位符里的名字，原样匹配）：

```bash
java -jar app.jar --spring.profiles.active=prod \
  --SERVICE_IP=106.52.234.62 --DATABASE=xxx --REDIS_PWD=xxx --DEEPSEEK=xxx \
  --MOONSHOT=xxx --ALI_AI_KEY=xxx --MAIL_USERNAME=xxx --MAIL_PASSWORD=xxx \
  --API_KEY_SECRET=xxx --JWT_SECRET=xxx \
  --OSS_ACCESS_KEY_ID=xxx --OSS_ACCESS_KEY_SECRET=xxx
```

缺点：密钥明文出现在面板上。能跑通之后，还是建议换回 `.env` 或面板的环境变量配置。

### 改配置要重新打包吗？—— 不用

jar 里的 `application-prod.yml` 只是**默认值**。Spring Boot 的配置是运行时解析的，
可以**逐属性覆盖**（不是整文件替换），三种方式：

| 方式 | 直接跑 jar | docker | 适用场景 |
|---|---|---|---|
| 环境变量 `NEXUS_AGENT_CORS_ALLOWED_ORIGINS=...` | ✅ | ✅ `-e` / `--env-file` | **推荐**，两种跑法通用 |
| 命令行参数 `--nexus.agent.cors.allowed-origins=...` | ✅ | ❌（见下） | 临时试一下最快 |
| jar 同级 `config/application-prod.yml` | ✅ | 需 `-v` 挂载 | 要改的东西比较多时 |

第 3 种的具体用法 —— 在 jar 旁边建 `config/application-prod.yml`，**只写要覆盖的几行**，
其余仍用 jar 里的默认值（Spring Boot 会自动读它，且逐属性优先于 jar 内的同名配置）：

```yaml
# /opt/nexus-agent/config/application-prod.yml   （jar 同级的 config/ 目录）
nexus:
  agent:
    cors:
      enabled: true
      allowed-origins: http://120.235.30.202:5173
```

前提同样是**进程工作目录 = jar 所在目录**（运维面板默认就是；systemd 靠 `WorkingDirectory=`）。
改完**重启进程**即可，不用重新打包。

所以改 CORS 白名单只需要往 `.env` 里加一行、**重启**，不用回到 Maven：

```bash
NEXUS_AGENT_CORS_ENABLED=true
NEXUS_AGENT_CORS_ALLOWED_ORIGINS=http://120.235.30.202:5173
```

⚠️ 环境变量名是**全大写 + 点变下划线**：`nexus.agent.cors.allowed-origins`
→ `NEXUS_AGENT_CORS_ALLOWED_ORIGINS`。这靠的是 `SystemEnvironmentPropertySource`
把属性名反向映射成环境变量名（`CorsConfigTest` 里有两个用例专门锁住它 ——
将来 Spring 改了策略会先炸测试，而不是上线才发现跨域失效）。

❗ docker 的 `ENTRYPOINT` 是 `sh -c "exec java ..."`，`docker run` 后面的参数传不进 java，
所以**容器场景只能用环境变量**这一条路（或者改 Dockerfile）。

### 优雅停机与健康检查

- `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`：
  `docker stop` 发的是 SIGTERM，不配就会把"对话进行到一半"的 SSE 连接直接掐断。
- 只暴露 `/actuator/health` 与 `/actuator/info`，且 `show-details: never`。
  ⚠️ 绝不要加 `env` / `heapdump` —— `/actuator/env` 会把数据库密码和 API Key 原样吐出来。
- 这两个端点在 `LoginCheckInterceptor` 白名单里（容器探针不会带 token），
  所以**对外只返回 `{"status":"UP"}`**，看不到任何组件细节。

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

---

## API 文档（P3-4）

应用启动后：

| 地址 | 内容 |
|---|---|
| <http://localhost:8080/swagger-ui.html> | Swagger UI（可直接在页面上试接口） |
| <http://localhost:8080/v3/api-docs> | 原始 OpenAPI JSON（可导入 Apifox / Postman） |

- 接口清单由 **SpringDoc 自动扫描 `@RestController`** 生成，改代码即同步，**不需要**手工维护。
- 鉴权：点 Swagger UI 右上角 **Authorize**，填 `POST /api/user/login` 返回的 JWT
  （本项目登录态在**请求头 `token`**，不是 `Authorization: Bearer`）。
- ⚠️ **`/api/chat/stream` 是 SSE**，Swagger UI 试不出效果（它不会渲染事件流）。
  帧结构看手写契约 [`docs/sse-contract.md`](./docs/sse-contract.md) —— 那是权威版本。
- ⚠️ **生产默认关闭**（`application-prod.yml` 里 `springdoc.*.enabled=false`）：
  接口清单等于把后端攻击面画成地图，需要时手动打开这两个开关。

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
| `Could not resolve placeholder 'XXX'` | **prod 下环境变量没设全**。`application-prod.yml` 里的 `${XXX}` **没有默认值**，缺一个就起不来。跑 `bash scripts/check-env.sh` 看缺哪项 |
| 直接 `java -jar` 后所有配置都报缺失 | Spring Boot **不会**自动读 `.env`，先 `set -a; . ./.env; set +a` |
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
| `nexus.agent.memory.max-results` | `20` | 长期记忆单次检索最多返回多少条（太多会塞爆提示词） |
| `nexus.agent.cors.enabled` | `true`（dev）/ `false`（prod） | 前后端分离部署（前端在别的域名/端口）时设为 `true`。**prod 默认关闭**，同源部署不用开 |
| `nexus.agent.cors.allowed-origins` | `http://localhost:5173,http://127.0.0.1:5173`（dev） | 允许的前端来源，逗号分隔（支持 `http://localhost:*`）。⚠️ 留空 = 不启用（**不会**退化成放行所有）；生产请填**精确域名**，别填 `*` |
| `nexus.agent.memory.fuzzy` | `true` | 字面匹配零命中时用 `pg_trgm` 模糊兜底。**需执行 `docs/sql/006`**；没装扩展会自动降级（仅 WARN 一次） |

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
├── scripts/              部署自查脚本（check-env.sh：查 .env 缺项/占位符/写法）
└── docs/sql/             数据库基线与变更管理
```

依赖链：`web → service → mapper → domain → common`

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [AGENTS.md](./AGENTS.md) | **开发入口**：依赖版本、文件地图、接口一览、核心机制、技术栈、技术债清单、开发规范 |
| [docs/前端开发指南.md](./docs/%E5%89%8D%E7%AB%AF%E5%BC%80%E5%8F%91%E6%8C%87%E5%8D%97.md) | **写前端前先读**：环境准备（⚠️ 后端未配 CORS）、鉴权（`token` 头）、统一响应、全部接口的请求/响应结构、SSE 前端视角、页面清单、**极简黑白色板与组件规范**、联调顺序、上线 Checklist |
| [docs/sse-contract.md](./docs/sse-contract.md) | **SSE 事件契约（v2，权威）**：统一信封、7 个事件的载荷、seq/runId 语义、解析示例、v1→v2 迁移 |
| [重构计划.md](./重构计划.md) | 分阶段重构计划、任务表、验收标准、里程碑 |
| [skills/README.md](./skills/README.md) | 技能（Skill）目录约定、SKILL.md 写法、`scripts/` 与文档资源的区别 |
| [docs/sql/README.md](./docs/sql/README.md) | 数据库基线说明、设计理由、索引清单、变更约定 |
| [docs/后端变更review.md](./docs/%E5%90%8E%E7%AB%AF%E5%8F%98%E6%9B%B4review.md) | **变更 review 指南**：核心文件的 before/after 代码对照、好在哪、review 盯哪里、怎么验证 |
| [开发日志.md](./开发日志.md) | 项目演进历史记录 |
| `Dockerfile` / `docker-compose.yml` / `docker-compose.server.yml` / `.env.example` / `scripts/check-env.sh` | 部署形态（P3-3）：应用镜像、本地全套、**服务器部署（数据库在外部）**、环境变量清单、部署前自查；用法见本文「部署」章节 |
