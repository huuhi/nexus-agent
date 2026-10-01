# 后端变更 review 指南（2026-10-01）

> **这份文档是给你自己逐条核代码用的。**
> 每一节都按「改之前的实际代码 → 改之后的实际代码 → 好在哪 → review 时盯哪里 → 怎么验证」组织，
> 引用的代码都是从 git 里取出来的真实内容，不是复述。

---

## 0. 三条命令拿到全部 diff

```bash
# 1) 本轮三个 commit（起点是 P2-5 的 847e28c）
git log --oneline 847e28c..HEAD

# 2) 按 commit 逐个看（推荐，一次只看一个主题）
git show 7feb5fb     # P2-8 遗留：配额周期重置 + 用量查询
git show 93cd2f9     # P3-4：API 文档
git show 8b12fb4     # P3-3：部署形态

# 3) 只看某一个文件的变化
git diff 847e28c..HEAD -- nexus-agent-service/src/main/java/com/huzhijian/nexusagentweb/service/impl/QuotaServiceImpl.java
```

本轮总量：**38 个文件，+1496 / −29**。

| commit | 任务 | 规模 |
|---|---|---|
| `7feb5fb` | P2-8 遗留：配额周期重置 + `GET /api/user/quota` | 16 文件，+724 / −16 |
| `93cd2f9` | P3-4：API 文档（SpringDoc + 手写 SSE 契约） | 18 文件，+341 / −7 |
| `8b12fb4` | P3-3：部署形态固化 | 13 文件，+435 / −10 |

---

## 1. 配额周期重置（P2-8 遗留）—— 本轮最大的一处逻辑变更

### 1.0 先回答：为什么要有配额这个东西

一句话：**它是成本护栏，防的是「失控」，不是「坏人」。**

token = 钱，而且是从**你的** API Key 里扣的（商户在页面上说一句话 → 你的后端拿你的 Key
调 Moonshot / DeepSeek → 扣你的钱）。可能出现的失控包括：模型死循环、超长上下文、
有人拿脚本反复刷、某个商户一天用掉别人一个月的量。没有配额时，这些场景的账单**没有上限**。

配额就是给每个商户一个额度，用完就拦住 —— 单次失控最多烧掉这一个额度，不会拖垮其他人和你的账单。

**那为什么还必须能"周期重置"？** 因为只减不增的额度 = 一次性额度：
用完就是永久封禁，于是这个数字你根本不敢设小（设小了等于随时把客户锁死），
设大了又失去护栏意义。只有能按日/按月清零，"配额"才真正变成
「每月 100 万 token」这种套餐语义 —— 也就是将来做免费/付费档位的基础。

> ⚠️ **你现在其实没有被任何人限制**：`default-quota: 0`（新用户额度 0 = 不限制）
> 且 `period: NONE`（不重置）。也就是说校验逻辑装着、**默认对所有人不生效**，
> 唯一在发生的事是 `token_used` 一直在默默累加记账。
> 想限谁就改库一行：`UPDATE users SET token_quota=1000000, token_period='MONTHLY' WHERE id=?`。
> 如果你觉得这功能当前多余，把 `nexus.agent.quota.enabled` 设成 `false` 即可，代码留着不影响任何东西。

### 1.1 改之前是什么样

`QuotaServiceImpl.assertWithinQuota` 直接拿库里的累计值判定，**没有任何重置逻辑**：

```java
// 旧代码（847e28c）
Decision decision = evaluate(user.getTokenQuota(), user.getTokenUsed());
if (!decision.allowed()) {
    throw new QuotaExceededException(
            "您的 token 配额已用完（已用 %d / 上限 %d）。请联系管理员调整配额后再试。"
                    .formatted(decision.used(), decision.quota()));
}
```

`UserMapper.xml` 只有一条"只增不减"的累加语句：

```xml
<!-- 旧代码：只有 addTokenUsage，没有任何清零入口 -->
<update id="addTokenUsage">
    UPDATE "public"."users"
    SET token_used = COALESCE(token_used, 0) + #{delta}
    WHERE id = #{userId}
</update>
```

**后果**：`token_used` 单调递增，任何人一旦用完配额就**永久被拒**，只能靠管理员手工改库清零。
配额度这个功能实际上是不可用的 —— 要么不敢开，要么开了就得定期人工擦屁股。

### 1.2 改之后

新增 `resetQuotaPeriod`（一条带 WHERE 的原子 UPDATE）：

```xml
<!-- 新代码：UserMapper.xml -->
<update id="resetQuotaPeriod">
    UPDATE "public"."users"
    SET token_used = 0,
        token_period_start = #{periodStart}
    WHERE id = #{userId}
      AND token_period IS NOT NULL
      AND token_period &lt;&gt; 'NONE'
      AND (token_period_start IS NULL OR token_period_start &lt; #{periodStart})
</update>
```

调用点抽成两个方法，`assertWithinQuota` 与 `getQuota` 共用：

```java
// 新代码：QuotaServiceImpl
long used = resetPeriodIfDue(userId, user);          // 返回"重置之后"的真实用量
Decision decision = evaluate(user.getTokenQuota(), used);
...
throw new QuotaExceededException(
        "您的 token 配额已用完（已用 %d / 上限 %d）。请联系管理员调整配额，或等待下一周期重置（当前周期：%s）。"
                .formatted(decision.used(), decision.quota(), describePeriod(periodOf(user))));
```

### 1.3 好在哪

| 维度 | 之前 | 之后 |
|---|---|---|
| 配额能不能真的用 | 用完即永久封禁 | 支持 `DAILY` / `MONTHLY` 自动恢复 |
| 重置的故障模式 | （没有重置） | **惰性重置，不跑 `@Scheduled`**：定时任务挂了会导致全员配额不刷新，多实例还要抢锁；惰性重置把这个故障模式整个消掉 |
| 并发安全 | — | 一条 UPDATE + WHERE 判定，并发的两个请求只有一个命中，天然幂等 |
| 迁移漏跑 | 缺列 → 直接放行 | 缺列 → **按累计用量判定**（保守，不放行）；非法周期值 → `NONE` |
| 报错信息 | "请联系管理员调整配额后再试" | 追加"或等待下一周期重置（当前周期：每月）"，用户知道什么时候能自己恢复 |

### 1.4 review 时请重点盯这三点

1. **WHERE 里的 `token_period <> 'NONE'`**：没有这一句，会把"累计不重置"的存量用户一起清掉 —— 那是**静默的数据损坏**，比功能失效更糟。
2. **`token_period_start IS NULL` 也算"该重置"**：这是刻意的。老用户在 003 时代攒下的 `token_used`，一旦被管理员切成 `DAILY`，第一次访问就从零开始算（`007` 注释第 3 条）。如果你希望"切周期前的用量继续算"，这里要改。
3. **失败时是"不放行"而不是"放行"**（`resetPeriodIfDue` 的 catch 分支返回 `used` 而非 `0`）：配额失效的方向宁可误拦也不能漏放，这是我选的保守方向。

### 1.5 怎么验证

```bash
# 单测（22 个用例覆盖了上面全部分支，含"跨周期清零 / 未跨周期仍按累计 / 重置失败降级"）
mvn -pl nexus-agent-web -am -Dtest='QuotaPeriodTest,QuotaServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

需要在库里执行（**不执行也不影响启动**，只是没有周期重置能力）：

```bash
psql -h <PG_HOST> -U postgres -d nexus_agent -f docs/sql/007_add_user_token_quota_period.sql
# 给某个用户开月度配额：
# UPDATE users SET token_period='MONTHLY', token_quota=1000000 WHERE id=<id>;
```

---

## 2. `GET /api/user/quota`（新增接口）

### 2.1 接口形状

```
GET /api/user/quota      →  Result{data: QuotaVO}
```

`QuotaVO`：`quota` / `used` / `remaining` / `unlimited` / `period` / `periodStart` / `degraded`。

### 2.2 三个刻意的决定（这是我最想让你过目的地方）

**① 不接受任何入参，身份只从 `UserContextHolder` 取**

```java
// UserController
@GetMapping("/quota")
public Result getQuota(){
    Long userId = UserContextHolder.getUserId();
    QuotaVO quota = quotaService.getQuota(userId);
    return Result.ok(quota);
}
```

如果做成 `GET /api/user/quota?userId=42`，就成了"传个 userId 就能看别人用量"的越权口子。
（项目里已有 IDOR 教训，见 `AGENTS.md §12.1`。）

**② 查询失败返回 `degraded=true`，不抛异常**

```java
} catch (Exception e) {
    log.warn("查询配额失败：userId={} 原因={}。若提示缺列，请执行 docs/sql/003 与 007", userId, e.getMessage());
    return QuotaVO.degraded();
}
```

配额是**附加信息**，不该让一个没跑迁移的环境连设置页都打不开。
前端必须先看 `degraded`，为 `true` 时提示"配额信息暂不可用"，**不能把 null 显示成 0**。

**③ 这个 GET 会写库（触发惰性重置）** —— 这条我犹豫过

```java
// QuotaServiceImpl#getQuota
// 这里也走一次惰性重置：否则前端看到的会是"上一周期遗留的用量"，
// 而周期起点却显示成当前周期 —— 数字自相矛盾，比不显示更糟。
long used = resetPeriodIfDue(userId, user);
```

理由是"不重置就会显示出自相矛盾的数字"；该 UPDATE 幂等，重复调用无副作用。
但**它确实让一个 GET 带了写操作** —— 如果你不接受，替代方案是"不重置、直接展示库里的原始值 + 库里的 `period_start`"，代价是数字会陈旧。这是个可以推翻的决定。

---

## 3. API 文档（P3-4）

### 3.1 改之前

没有任何 API 文档：没有 SpringDoc 依赖、没有 `@Operation`、没有 Swagger UI。
前端对接只能靠 `AGENTS.md §7` 的手写表格 + 抓包。

### 3.2 改之后

| 地址 | 内容 |
|---|---|
| `/swagger-ui.html` | Swagger UI（可直接试接口） |
| `/v3/api-docs` | OpenAPI JSON（可导入 Apifox/Postman） |

**分工是刻意的**：普通 REST 由 SpringDoc 扫 `@RestController` 自动生成；
**SSE 的帧结构不进 OpenAPI** —— OpenAPI 描述不了"同一条连接里按序到达的多种事件"，
那部分契约保持在 `docs/sse-contract.md`（手写、权威），只在 `@Operation` 里把人引过去：

```java
@Operation(summary = "发起对话（SSE 流式）", description = """
        返回 `text/event-stream`。浏览器原生 `EventSource` **用不了**
        （它不能自定义请求头，而本项目鉴权靠 `token` 头）…
        事件序列：`run`（首帧）→ `message` / `tool_execution` / … → `finish` 或 `error`。
        """)
@PostMapping(value="/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

### 3.3 好在哪

- 接口清单**不再手写**（`AGENTS.md §7` 那张表必然随代码漂移），改代码即同步。
- 鉴权方案按 `apiKey / in:header / name:token` 声明 —— 本项目不是 `Authorization: Bearer`，
  这里写错的表现是"每个用 Swagger 试接口的人都 401"，而且很难联想到是文档配置的问题
  （所以 `OpenApiConfigTest` 专门钉了这一条）。
- 免鉴权接口（login/register/password/email）用 `@SecurityRequirements`（空）覆盖掉全局锁，
  否则 Swagger 给它们也加锁，试接口的人会以为必须先登录。

### 3.4 review 时请盯：一处安全取舍

Swagger 相关路径被加进了 `LoginCheckInterceptor` 白名单（页面自身不带 token，不豁免根本打不开），
所以"是否对外暴露"**只由配置决定**：

```yaml
# application-prod.yml（默认关）
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

接口清单 = 后端攻击面的地图，所以 prod 默认关。`application.yml`（dev）默认开。

---

## 4. 部署形态（P3-3）

### 4.1 修掉的坑：build + volumes 混用（这个最值得看一眼）

```yaml
# 旧：nexus_agent_box/docker-compose.yml
services:
  nexus-agent-box:
    build: .                      # ← 镜像里已经有代码
    volumes:
      - ./app:/app/app            # ← 运行时又被宿主机目录盖掉
      - ./main.py:/app/main.py
```

问题：**跑的代码 ≠ 镜像里的代码**。同一个镜像在不同机器上行为不同，
而且会冒出"我明明改了却没生效"这类最难排查的问题 —— 因为你无法从镜像判断到底在跑什么。

改法：拆成两份。

```yaml
# 新：nexus_agent_box/docker-compose.yml（以镜像为准，挂全部去掉 + 加健康检查）
    build: .
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8000/docs"]
```

```yaml
# 新：nexus_agent_box/docker-compose.override.yml（Compose 自动合并，仅开发用）
    volumes:
      - ./app:/app/app
      - ./main.py:/app/main.py
```

`docker compose up` 自动带 override（开发照旧热重载）；
CI/生产显式 `docker compose -f docker-compose.yml up -d --build` 即可跳过。

> 健康检查探的是 `/docs` 而不是业务接口：**业务接口依赖 E2B Key**，
> Key 配错时容器会被判成不健康反复重启，反而把真正的原因盖住。

### 4.2 新增应用镜像：`Dockerfile`

maven 多阶段 → `eclipse-temurin:21-jre`。有三处不是"顺手写的"，请重点看：

```dockerfile
# ① 非 root 运行
RUN useradd -r -s /bin/false appuser && chown -R appuser /app
USER appuser

# ② 健康检查打到免鉴权的 /actuator/health
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# ③ 用 sh -c + exec：让 java 成为 PID 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

**第 ③ 条是隐藏依赖**：`docker stop` / k8s 滚动更新发的是 SIGTERM，
如果 PID 1 是 shell，信号不会转发给 java —— 那 `server.shutdown=graceful` 就白配了。

### 4.3 优雅停机 + 健康检查（`application.yml`）

改之前 `application.yml` 只有 12 行（spring 应用名 / profile / 虚拟线程 / 日志级别），
**没有任何停机与健康检查配置**。改之后：

```yaml
server:
  shutdown: graceful                              # SIGTERM 后先停止接新请求
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s               # 到期强制关（太长会被 docker stop 的 10s 超时 SIGKILL）
management:
  endpoints:
    web:
      exposure:
        include: health,info                      # ⚠️ 绝不能加 env / heapdump
  endpoint:
    health:
      show-details: never                         # 对外只给 {"status":"UP"}
      probes:                                     # /actuator/health/liveness 与 /readiness
        enabled: true
  health:
    livenessstate: { enabled: true }              # 同样的写法，块式亦可
    readinessstate:
      enabled: true
```

**为什么不配就会出问题**：不配 `graceful`，`docker stop` 会把"对话进行到一半"的 SSE 连接直接掐断。

**为什么 `show-details: never`**：`/actuator/health` 必须免鉴权（容器探针不带 token），
所以对外只能给一个 `UP`，组件细节（库名、Redis 地址）一个字都不能漏。

**⚠️ 绝不要开 `/actuator/env`**：它会把数据库密码、各家 API Key **原样**吐出来，连脱敏都没有。

### 4.4 一键起全套：`docker-compose.yml`（根目录）

pgvector / redis / box / app 四个服务，`depends_on` 走健康检查。两处容易踩的：

- **PG 必须用 `pgvector/pgvector:pg16`**，官方 postgres 装不了 `vector` 扩展（`docs/sql/001` 里有 `CREATE EXTENSION vector`）。
- **容器网络内互访用 compose 服务名，不是 localhost**：`SERVICE_IP=postgres`、`BASE_URL=http://box:8000`。
- 镜像里没有 `application-dev.yml`（被 gitignore），所以**容器必须跑 prod**：`SPRING_PROFILES_ACTIVE=prod`。
- 数据卷首次创建时会自动按序执行 `docs/sql/001…007` —— 但 `001` **会 DROP 全表**，
  别把有真实数据的目录挂到 `pgdata` 卷上。

---

## 5. 更早完成、但同样值得回头看的核心改动

这些在本次会话之前就改完了，`AGENTS.md` 里有完整记录。如果你想一并 review，按这个顺序看：

| 改动 | commit | 之前的问题 | 现在 | 详细 |
|---|---|---|---|---|
| **RAG 跨用户越权** | `7f7bcdd` | `EmbeddingSearchRequest` **完全没有 filter** = 全表检索，用户 A 能命中用户 B 的知识库内容 | 加 `user_id` 过滤；拿不到用户上下文时**拒绝检索**而不是退化成全表 | `AGENTS.md §6.16` |
| **知识库向量口径** | `7f7bcdd` | 入库从「用户 API 配置」取 EMBEDDING 模型（没配 Key 直接抛异常 → 建库必失败），检索却用系统模型；即便配上了，不同模型向量空间不可比 → **检索结果完全不相关且不报错** | 统一注入系统模型，"用户自选向量模型"能力移除（本就是伪能力） | `AGENTS.md §6.16` |
| **SSE 契约 v2** | `847e28c` | 事件名大小写混乱（`TOOL_EXECUTION` 全大写但按 `tool_execution` 监听收不到）；`sessionId` 只在流结束时才给 | 统一信封 `{seq, runId, event, data}` + `run` 首帧 | `docs/sse-contract.md` |
| **输出卡顿** | `87122a4` | 每个 token 推一帧（千字回复 = 上千帧），前后端被高频小包拖慢 | 攒 200 字符 / 60ms 推一帧 | `AGENTS.md §6.14` |
| **长期记忆检索** | `7b98e8f` | 向量检索是死代码且 SELECT 了不存在的列，一调用必然报错 | pg_trgm + 多关键词 OR + Java 排序去重 | `AGENTS.md §6.15` |
| **产物交付** | `4b6c99c`/`666096a` | AI 产出的文件没有交付链路 | `publish_artifact` 工具 + 会话级文件列表/删除 | `AGENTS.md §16.2` |

RAG 越权那处的 before/after，一眼就能看懂严重性：

```java
// 旧：没有任何过滤条件
EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
        .query(query)
        .maxResults(3).minScore(0.7)
        .queryEmbedding(embeddingModel.embed(query).content())
        .build();
```

```java
// 新
Long userId = UserContextHolder.getUserId();
if (userId == null) {
    return "知识库检索失败：无法确定当前用户，已按安全策略拒绝检索。";
}
Filter userFilter = metadataKey("user_id").isEqualTo(String.valueOf(userId));
```

---

## 6. 我做的取舍（review 时最值得质疑的几条）

| # | 取舍 | 我选了 | 代价 / 推翻成本 |
|---|---|---|---|
| 1 | 周期重置用定时任务还是惰性 | **惰性** | 沉默用户不会被清零（但他也没在消耗额度）。想改成定时任务要处理多实例抢锁 |
| 2 | 迁移漏跑时放行还是拦截 | **按累计用量判定（不放行）** | 极端情况下会误拦；改成就近放行只要改 `resetPeriodIfDue` 的 catch 分支 |
| 3 | `getQuota` 这个 GET 要不要写库 | **写（触发惰性重置）** | GET 带了副作用；替代方案是回退到"展示原始值"，数字会陈旧 |
| 4 | Swagger 路径要不要免鉴权 | **免鉴权 + prod 默认关** | 一旦在 prod 打开就是完全公开；开关只有 `springdoc.*.enabled` 一处 |
| 5 | SSE 帧结构进不进 OpenAPI | **不进，保持手写契约** | Swagger 上试不出 SSE 效果，必须另开 `docs/sse-contract.md` |
| 6 | Dockerfile 要不要做依赖分层缓存 | **不做**（`dependency:go-offline` 在多模块下经常解析不全） | 改一行代码也要重下全量依赖 |
| 7 | 沙盒服务热重载放哪 | **override 文件** | 开发默认带挂载（跑的 ≠ 镜像内容），需要"与镜像一致"时必须显式 `-f docker-compose.yml` |

---

## 7. 已知未做 / 需要你执行

| 项 | 状态 | 说明 |
|---|---|---|
| `docs/sql/006`（pg_trgm） | ⬜ 待执行 | 不执行也能跑，只是失去长期记忆的模糊兜底 |
| `docs/sql/007`（配额周期列） | ⬜ 待执行 | 不执行也能跑，只是没有周期重置 |
| SpringDoc 实跑 | ⬜ 待验证 | 重启后确认 `/v3/api-docs` 200、`/swagger-ui.html` 可开。若与 Boot 3.5 有兼容问题，设两个 `enabled=false` 即降级 |
| Docker 镜像与 compose | ⬜ **未真机验证** | 本机没有 Docker。首次请先 `docker compose config` + `docker build -t nexus-agent:local .` |
| E2B 模板预装 Office 库 | ⬜ 待执行 | `python-docx` / `openpyxl` / `python-pptx` |
| SSE 服务端回放 | ❌ 不做 | 需事件持久化，收益/成本不划算；断线后按 `sessionId` 重拉历史（契约文档 §5） |

---

## 8. 变更文件全清单（38 个）

**新增**

| 文件 | 作用 |
|---|---|
| `docs/sql/007_add_user_token_quota_period.sql` | 配额周期两列（幂等、有回滚说明） |
| `nexus-agent-common/.../em/QuotaPeriod.java` | 周期枚举（容错解析 + 纯函数 `startOf`） |
| `nexus-agent-domain/.../vo/QuotaVO.java` | 用量查询返回体 |
| `nexus-agent-web/.../config/OpenApiConfig.java` | OpenAPI 元信息 |
| `Dockerfile` / `.dockerignore` / `docker-compose.yml` / `.env.example` | 部署形态 |
| `nexus_agent_box/docker-compose.override.yml` | 开发用热重载 |
| `.../QuotaPeriodTest.java`（7）/ `OpenApiConfigTest.java`（4） | 新增单测 |

**修改**

| 文件 | 改了什么 |
|---|---|
| `QuotaServiceImpl.java` | +123 行：惰性重置、周期判定、用量查询 |
| `QuotaService.java` | 加 `getQuota` |
| `UserMapper.java` / `.xml` | 加 `resetQuotaPeriod`；`Base_Column_List` 补新列 |
| `User.java` | 加 `tokenPeriod` / `tokenPeriodStart` |
| `AgentProperties.java` | `Quota.period` |
| `UserController.java` | `GET /api/user/quota` |
| 8 个 Controller | `@Tag` / `@Operation` / `@SecurityRequirements` |
| `WebInterceptorConfig.java` | 白名单加 swagger + actuator |
| `application.yml` | 优雅停机 + actuator |
| `application-prod.yml` | `quota.period`、springdoc 默认关 |
| `pom.xml`（父 + web） | SpringDoc 依赖与版本管理 |
| `nexus_agent_box/Dockerfile` / `docker-compose.yml` | curl + 健康检查；去掉源码挂载 |
| `.gitignore` | 忽略 `/.env`、`/nexus_agent_box/.env` |
| `AGENTS.md` / `README.md` / `重构计划.md` / `docs/sql/README.md` | 文档同步 |

测试：**194 个（182 通过 + 12 人工跳过），0 失败**。

```bash
mvn -pl nexus-agent-web -am test
```
