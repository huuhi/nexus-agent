# SSE 事件契约 v2

> **本文件是 `POST /api/chat/stream` 事件流的唯一权威说明。**
> `AGENTS.md §6.2` 只留索引，两处冲突时**以本文件为准**。
>
> 落地版本：**P2-5（2026-09-30）**。后端实现见
> `converter/SseResponseConverter.java`、`em/SseEventType.java`、`vo/SseEvent.java`；
> 契约由 `SseContractTest`（10 个单测）固定，改契约前先改测试。

---

## 0. 一分钟速览

```text
event: run                    ← 第一帧：拿到 runId 与 sessionId
id: 1
data: {"seq":1,"runId":"a1b2…","event":"run","data":{"sessionId":"…","isNewSession":true}}

event: message                ← 思考/正文增量（可能一帧含多个 token）
id: 2
data: {"seq":2,"runId":"a1b2…","event":"message","data":{"type":"CONTENT","content":"你好"}}

event: tool_execution         ← 工具调用请求（arguments 是流式片段，会拆成多帧）
event: tool_execution_result  ← 工具执行结果
event: artifact               ← AI 产出的交付物（下载卡片）
event: finish                 ← 正常结束
event: stopped                ← 用户主动叫停「停止生成」（不是失败）
event: error                  ← 运行失败（带 runId，可查服务端日志）
```

**三件必须知道的事**：

1. **事件名全部小写**（v1 的 `TOOL_EXECUTION` / `TOOL_EXECUTION_RESULT` 已改为小写）。
2. **每帧都套同一个信封**：`{seq, runId, event, data}`，原载荷在 `data` 里。
3. **`seq` 同时也写进 SSE 原生 `id:` 字段** —— 浏览器 `EventSource` 会自动记住它，
   断线重连时作为 `Last-Event-ID` 回传。

---

## 1. 统一信封

每个事件的 `data` 都是同一个结构：

| 字段 | 类型 | 说明 |
|---|---|---|
| `seq` | number | 本次 Run 内**从 1 开始单调递增**。前端可用它判断丢帧：出现跳号说明中间断了 |
| `runId` | string | 本次 Run 的 trace_id。可在服务端日志里 `grep "RUN runId=<值>"` 拿到完整的 token/费用/工具序列 |
| `event` | string | 事件名，与 SSE 的 `event:` 同名（冗余下发，便于从裸流解析与打日志） |
| `data` | any | 事件载荷，形状见 §3 |

同时设置 SSE 原生字段 **`id: <seq>`**，用于浏览器自动重连时回传 `Last-Event-ID`。

### ⚠️ 数字形态：`Long` 一律是**字符串**，但计量字段不是

后端有一条全局 JSON 序列化规则（`config/JacksonConfig`，2026-10-05 立）：
**所有 `Long` / `long` 字段序列化成带引号的字符串。**

这不是风格偏好，是修一个必然发生的故障 —— 雪花主键是 **19 位**十进制数
（如 `2107069112529358849`），而 JS 的 `Number` 精确表示上限只有 **16 位**
（`Number.MAX_SAFE_INTEGER = 9007199254740991`）。裸数字过一遍 `JSON.parse`
低位会被直接抹掉（实测抽样 4000 个雪花 ID，**99.6% 被改写**），
于是「拿 id 去删 / 改」的接口全部落空 —— 那就是 2026-10-05 那个
「每一行点删除都报文件不存在」的真凶。

**所以看契约时请按字段分辨形态**：

| 形态 | 哪些字段 | 怎么用 |
|---|---|---|
| **string** | 各类雪花主键：`id`、`artifact.id`、`attachedFile.id`、`userId`、`supersededBy` | 原样透传，**不要做算术**，也不要与数字混用作 key |
| **number** | `seq`、`ttfbMs`、`contextWindow` / `contextUsed` / `contextMsgs` / `contextRatio`、`partial`、`index`、`fileSize`、`size`、`total`、`quota` / `used` / `remaining`、`fileQuota` / `fileUsed` / `fileRemaining` | 可以正常参与算术与比较 |

🔴 **这两类混过两次，都是 2026-10-08 修的**：

**第一批（`seq` / `ttfbMs`）**：原本分别声明成 `long` 与 `Long`，被上面那条规则误伤成
`"7"` / `"6667"`，而契约承诺它们是 number。**后果是静默的** —— 前端在真实链路上抓帧才暴露
（`{"status":"DONE","ttfbMs":"6667","contextWindow":300000,…}`，
注意同一帧里 `context*` 是裸数字、`ttfbMs` 带引号）。

> frontend 复核后报告：`seq` 的失效**比预想更早一步** —— 他那边是
> `typeof env.seq === 'number'` 才走数字分支，字符串 seq 根本进不了跳号检测，
> 直接掉进 `id` 兜底分支；`id` 再缺就整个检测形同虚设。
> **不是「算出 NaN」，而是「压根没走到那行」。**

两者已改为 `int`（序号与毫秒数都没有 2^53 精度问题；秒级延迟离 `Integer.MAX_VALUE`
有三个数量级余量，且 `withTtfb` 里另做了封顶）。

**第二批（`fileSize` / `size` / `total` / 配额）**：同一类误伤。这批**没有改 Java 类型**，
而是在 `JacksonConfig` 里用 **mixin 精确挑回来**（默认仍是字符串 = 安全的一侧，
只有显式列出的字段才是数字）：

| 字段 | 位置 | 前端用途 |
|---|---|---|
| `fileSize` | 文件列表 / 附件（`SysFile`、`KnowledgeFileVO`） | 格式化体积、算进度 |
| `size` | `artifact` 事件的 `data.artifact.size` | 同上 |
| `total` | `Result` 信封（分页） | 分页算术 |
| `quota` / `used` / `remaining` | `GET /api/user/quota`（token 配额） | 「已用 X / 上限 Y」 |
| `fileQuota` / `fileUsed` / `fileRemaining` | 同上 + 上传被拒的 error data | 同上 |

⚠️ `artifact.size` 额外做了一次**运行时归一**：它塞在 `Map<String,Object>` 里，
而 **Map 的值按运行时类型挑序列化器，类级 mixin 管不到** —— 沙盒返回的 JSON number
小值时是 `Integer`（没事）、大值时是 `Long`（变字符串），同一字段形态会随文件大小漂移。
现在统一成「能放进 `int` 就用 `int`，否则用 `BigInteger`」，两者都序列化成裸数字。

护栏：`NumberFieldSerializationTest` —— **真的跑一遍 ObjectMapper**
（且用的是与运行时同源的 `JacksonConfig` customizer）来断言形态，
而不是断言 Map 里的 Java 对象（那正是第一批能躲过原测试的原因：
原断言 `assertEquals(1820L, data.get("ttfbMs"))` 时，序列化**还没发生**）。
它还额外校验「mixin 声明的 getter 在目标类上真实存在」——
mixin 按方法名匹配，**写错名不会报错，只会静默失效**。
两批都做了反向验证（去掉修复 → 断言立刻变红，报错原文与实测形态一致）。

---

## 2. 事件一览

| # | `event` | 何时出现 | 载荷 `data` |
|---|---|---|---|
| 1 | `run` | **第一帧**（幂等，只发一次） | `{sessionId, isNewSession}` |
| 2 | `message` | 思考/正文增量，多次 | `MessageVO{type:"THINK"\|"CONTENT", thinking?, content?}` |
| 3 | `tool_execution` | 模型发起工具调用，可能多次 | `MessageVO{type:"TOOL_EXECUTION", toolRequestList:[{id,toolName,arguments}]}` |
| 4 | `tool_execution_result` | 工具返回，每个调用一次 | `MessageVO{type:"TOOL_EXECUTION_RESULT", toolResultVO:{id,toolName,result,isError}}` |
| 5 | `artifact` | AI 交付文件（P2-10） | `MessageVO{type:"ARTIFACT", artifact:{id,name,url,size,extension,sourcePath}}` |
| 6 | `finish` | 正常结束，一次 | `{status:"DONE"}` **＋ 收尾公共字段**（见 §3.6） |
| 7 | `stopped` | **用户主动叫停**，一次（2026-10-06 新增） | `{reason, partial}` **＋ 收尾公共字段**（见 §3.6） |
| 8 | `error` | 运行失败，一次 | `{type:"ERROR", message, hint}` **＋ 收尾公共字段**（见 §3.6） |

**顺序保证**：正文缓冲会在「工具事件 / 产物事件 / finish / stopped / error」之前**强制冲刷**，
所以前端不会看到"正文插到工具卡片后面"的顺序错乱，回复尾部也不会丢。

---

## 3. 各事件载荷细节

### 3.1 `run`（首帧）

```json
{"seq":1,"runId":"a1b2c3d4e5f6","event":"run","data":{"sessionId":"8f2c…","isNewSession":true}}
```

- **为什么要有它**：v1 里 `sessionId` 只在**新会话**、且**流结束时**才给 ——
  前端在整段回答结束前不知道该往哪个会话发下一条消息。现在提前到第一帧。
- `runId` 从第一帧就能拿到，方便前端立刻建「本次运行」的日志上下文。

### 3.2 `message`（思考 / 正文增量）

```json
{"seq":2,"runId":"…","event":"message","data":{"type":"THINK","thinking":"先理一下"}}
{"seq":3,"runId":"…","event":"message","data":{"type":"CONTENT","content":"你好，"}}
```

⚠️ **是"批量增量"不是"逐 token"**（P2-12）：后端攒够 200 字符或 60ms 才推一帧，
一帧可能包含多个 token。前端**必须保持 append 语义**（`text += chunk`），不能整块替换。

`type` 取值：`THINK`（思考）、`CONTENT`（正文）。两者**分开累积**，
不要拼进同一个字符串。

### 3.3 `tool_execution`（工具调用请求）

```json
{"seq":4,"runId":"…","event":"tool_execution",
 "data":{"type":"TOOL_EXECUTION","toolRequestList":[{"id":"call_1","toolName":"execute_cmd","arguments":"{\"cmd\":"}]}}
```

⚠️ **`arguments` 是流式片段**：同一个工具调用的 `arguments` 会被拆成多帧逐步补全
（`{"cmd":` → `{"cmd":"ls` → …）。要展示参数，请**按 `id`（或 `index`）累积拼接**，
不要每次都当成完整 JSON 去解析（早期帧是非法 JSON）。

> 🔴 **`id` 可能缺失，也可能重复 —— 不要用 `id` 做列表 key**（2026-10-07，frontend 踩坑后钉死）。
> `id` 由模型/供应商给出，后端原样透传、不做加工，因此：
> - 流式帧里**除首帧外 `id` 常常不带**（为 `null`）；
> - 某些供应商的兼容层**压根不回传 tool_call id**，同一批里多条都是 `null`；
> - 历史接口同样可能拿到 `null`（后端会兜底，见下）。
>
> frontend 曾直接用 `call.id` 做 `v-for` 的 key，id 撞车时 Vue patch 拿到 null el，
> 抛 `Cannot set properties of null (setting '__vnode')`，**整个应用渲染停摆**。
>
> **正确做法**：用 **`index`** 做 key（同一批并行调用里 `0,1,2…` 稳定唯一，首帧就有、不会为 null）；
> 需要跨消息全局唯一时用 `消息id + index`。`id` 只用来和 `tool_execution_result` 配对，
> 而配对本身也要能接受配不上（见 §3.4）。

**`index` 字段（2026-10-07 新增，可选）**：`toolRequestList[]` 的每项带 `index`（整数）。
仅 `tool_execution` 事件有；历史接口没有（历史消息里 langchain4j 不带 index，后端改为兜底合成 id）。

### 3.4 `tool_execution_result`

```json
{"seq":9,"runId":"…","event":"tool_execution_result",
 "data":{"type":"TOOL_EXECUTION_RESULT","toolResultVO":{"id":"call_1","toolName":"execute_cmd","result":"…","isError":false}}}
```

用 `id` 与 `tool_execution` 配对。`isError=true` 时 `result` 是结构化错误
（`{success,errorCode,message,hint}` 的 JSON 文本，见 `AGENTS.md §6.5`）。

⚠️ **配对要能接受"配不上"**：`id` 可能为 `null`（原因同上），
此时既不能拿 `null` 当 key，也不能假设"有 result 就一定找得到对应 request"。
建议按 `index` 归位、按 `id` 只做**尽力**配对。

> 🔴 **2026-10-07 起：部分工具调用不会下发这两个事件**（内部的基建动作，如建沙盒、读写长期记忆）。
> 清单是**配置驱动**的（`nexus.agent.tools.hidden-tools`，默认
> `create_box` / `delete_box` / `search_user_memory` / `save_user_data` / `record_log`），
> 后端随时可能调整，所以前端**不要硬编码工具名**做白/黑名单。
> 契约上的含义：
> - 一个工具调用要么**两个事件都发**，要么**两个都不发**，不会只发一半；
> - 同一批并行调用里，可能只发其中几个工具的；
> - 隐藏工具执行期间**没有任何事件**（画面静默是正常的），连接靠心跳注释帧保活；
> - 历史接口 `GET /api/history/{sessionId}` 同样过滤，刷新页面也不会冒出来（但**数据库里仍有**，
>   模型上下文需要它）。

#### 🔴 `sources` —— 搜索来源（2026-10-08 新增，给「来源卡片」UI 用）

> 用途：用户拍板要做那种 Perplexity 式的展示 —— 回答上方一行「已搜索 N 个来源」+
> 来源横排卡片，正文里遇到 `[1]` 这类角标时映射成可点链接。

**只有 `toolName === "web_search"` 且 `isError === false` 的结果才会带这个字段**；
其余工具（含今天的 `web_extract`）**整个没有它** —— 连 `null` 都没有
（后端用了 `@JsonInclude(NON_NULL)`），所以判断请写
`Array.isArray(vo.sources)` 或 `"sources" in vo`，**不要**写 `vo.sources?.length > 0` 之外的自然假设。

| 字段 | 类型 | 含义 |
|---|---|---|
| `index` | number | 从 **1** 开始。与模型看到的编号**严格对齐**：模型在回答里写 `[3]` → 取 `sources[2]` |
| `title` | string | 结果标题，可能为空串（此时用 URL 兜底展示） |
| `url` | string | 来源地址 |
| `snippet` | string | 摘要，**最多 240 字符**，超了会截断并加 `…` |

```json
{"seq":9,"runId":"…","event":"tool_execution_result",
 "data":{"type":"TOOL_EXECUTION_RESULT","toolResultVO":{
   "id":"call_1","toolName":"web_search","isError":false,
   "result":"1. 2026年高考报名人数\n   https://…\n   共 1300 万人…",
   "sources":[
     {"index":1,"title":"2026年高考报名人数","url":"https://example.com/a","snippet":"共 1300 万人，创历史新高。"},
     {"index":2,"title":"第二条标题","url":"https://example.com/b","snippet":"另一段摘要。"}
   ]}}}
```

⚠️ **三条必须接受的边界（做不到就会渲染崩或者显示错乱）**：

1. **历史消息里没有 `sources`。** 该字段只存在于实时流；`GET /api/history/{sessionId}`
   恢复出来的旧消息没有它（数据库只存了结果的文本）。**刷新页面后来源卡片不回来是预期行为**，
   前端必须容忍缺失，不能当作异常。
2. **一次搜索对应一次消费。** 每条 `tool_execution_result` 只会带上它自己那次搜索的来源，
   不会重复下发同一批（同一会话里连续搜两次，两次事件各带各的）。
3. **`isError=true` 的结果一定没有 `sources`**，且不会把来源"攒"给后面的事件。

🔴 **`index` 是 number，不是字符串。** 这项目全局有个规则：所有 `Long`/`long` 会被序列化成字符串
（为雪花 ID 精度）。`index` 特意用了 `int` 才躲开 —— 2026-10-08 的 `ttfbMs` 就是被这条规则
坑成 `"6667"` 的。收到字符串形态即为回归，请立刻反馈。

### 3.5 `artifact`（交付物）

```json
{"seq":12,"runId":"…","event":"artifact",
 "data":{"type":"ARTIFACT","artifact":{"id":91,"name":"报告.docx","url":"https://…","size":12345,"extension":"docx","sourcePath":"/tmp/报告.docx"}}}
```

`id` 是 `sys_file` 主键，可用来去重与追溯（`GET /api/artifact?sessionId=` 会返回同一批）。

### 3.6 `finish`

```json
{"seq":13,"runId":"…","event":"finish","data":{
  "status":"DONE",
  "ttfbMs":1820,
  "contextWindow":300000,"contextUsed":12480,"contextMsgs":42,"contextRatio":0.0416
}}
```

v1 是裸字符串 `"DONE"`，v2 改成对象以便携带信封字段。

#### 收尾公共字段 —— `finish` / `stopped` / `error` **三个事件都带**

下面 7 个字段在三个收尾事件上**形状完全一致**（实现上是同一个 `withTtfb(withContext(...))`），
且**全部可选**：契约上写「可能不存在」，而不是「存在但为 null」。
所以前端判断用 `"contextRatio" in data`，不要用 `data.contextRatio != null` 之外的多余分支。

| 字段 | 类型 | 含义 |
|---|---|---|
| `ttfbMs` | number? | **服务端首字延迟**（ms）：请求进入后端 → 第一个**内容** token 到达（2026-10-06 新增）。🔴 2026-10-08 前实测抓到过 `"6667"`（字符串），已修 —— 见 §1「数字形态」 |
| `preflightMs` | number? | **后端自己的前置耗时**（ms）：收到请求 → 把请求发给模型之前（配额校验 + 消息转换 + 上下文构建＝模型匹配 / MCP 工具列表 / 技能解析）。2026-10-08 新增。实测典型值：24ms（缓存命中）/ 969ms / 5845ms（MCP + 技能 TTL 过期重建） |
| `firstTokenMs` | number? | **首帧延迟**（ms）：收到请求 → 模型的**第一个任意 token**（思考内容也算）。2026-10-08 新增。 |

#### 🔴 延迟三段归因（`preflightMs` / `firstTokenMs` / `ttfbMs` 三者连用）

这三个字段合起来，能把一次「怎么这么慢」**彻底拆开**，不再需要猜：

```
preflightMs                 = 后端自己的准备（配额 / 上下文 / MCP / 技能）
firstTokenMs − preflightMs  = 供应商排队（连接已建立，但一个字都不来）
ttfbMs      − firstTokenMs  = 模型思考 / 生成（字在往外蹦，只是慢）
```

| 哪一段大 | 意味着 | 该找谁 |
|---|---|---|
| `preflightMs` 大 | 我们自己的准备慢（典型是 MCP / 技能缓存过期重建） | 后端 |
| `firstTokenMs − preflightMs` 大 | 请求发出去了，供应商半天不给第一个字 | 供应商排队 / 限流 |
| `ttfbMs − firstTokenMs` 大 | 模型在慢慢思考（UI 上能看到"思考中"在爬） | 模型本身，只能换模型或等 |
| `contextWindow` | number? | 本次记忆窗口（token）＝ `min(nexus.agent.memory.max-tokens, 模型上下文窗口 − 最大输出)` |
| `contextUsed` | number? | 本次从库里加载的历史**估算 token**（裁剪前）。**`-1` = 没算出来** |
| `contextMsgs` | number? | 本次加载的历史**条数** |
| `contextRatio` | number? | `contextUsed / contextWindow`，保留 4 位小数。**`> 1` 表示本轮已开始丢更早的历史** |

#### 🔴 为什么必须用 `contextRatio`，不能数消息条数（2026-10-08 钉死）

条数与真实占用**完全不成比例** —— 一轮带沙盒输出 / 检索结果的 agent 交互能顶几十轮纯闲聊。

线上实测（`Agent_Back-20261008092751.log`，session `83bd707f`）：

```
CHAT_CONTEXT ... window=300000          ← 记忆窗口 30 万 token
contextUsed ≈ 3.7k, contextRatio ≈ 0.012 ← 实际只用了 1.2%
CHAT_MEMORY msgs=23                      ← 历史 23 条
```

**还有 98.8% 的余量**，前端却按早先的「12 条」阈值弹出了
「这个话题聊了很多轮，模型只记得最近的内容。换话题时建议新建对话」——
用户的原话是「才那么点，就提示我新建对话，这不是有毛病吗？甚至我这个模型上下文是 1M 的」。

**前端判断口径（与 frontend 于 2026-10-08 约定的最终版）**：

| 条件 | 建议动作 |
|---|---|
| `contextRatio >= 0.8` | **软提示**：「这个会话有点长了」 |
| `contextRatio >= 1.0` | **强提示**：「已开始遗忘早期内容，建议开新会话」 |
| `contextRatio` 缺失，**或** `contextUsed == -1` | 🔴 **不显示任何提示**（老后端 / 没算出来时宁可不提示，也不要误导） |

⚠️ `contextMsgs` **只能用于展示**（例如「本会话 42 条」），**不得**作为任何触发条件。

⚠️ **口径是「加载量」而非「发送量」**：`contextUsed` 统计的是裁剪**之前**从库里取回的历史规模，
裁剪后真正发给模型的会更少。对「这个会话积累了多少」这个问题，加载量才是对的
（裁剪后的值永远贴着窗口，看不出趋势）。

⚠️ **`ttfbMs` 是服务端口径**，不含网络往返与反向代理缓冲，与前端自己测的「首字」**不同源**。
两边的数对不上时不要互相怀疑，对齐方式：
`前端 firstTokenMs − data.ttfbMs` = 前端 + 网络段耗时。
另注：纯思考模型（先 thinking 再正文）该值**偏小**，不能拿它冒充体感延迟。

### 3.7 `stopped`（2026-10-06 新增）

```json
{"seq":9,"runId":"a1b2…","event":"stopped",
 "data":{"reason":"用户停止了本次生成","partial":1234,
         "ttfbMs":1820,
         "contextWindow":300000,"contextUsed":12480,"contextMsgs":42,"contextRatio":0.0416}}
```

用户在界面上点了「停止生成」，由 `POST /api/chat/stop` 触发。
**载荷里的 `ttfbMs` 与 `context*` 见 §3.6，与 `finish` 完全一致。**

- **`stopped` 不是 `error`**：这是用户的正常意图，不是失败。
  前端**不要**弹错误提示，只要把「思考中 / 生成中」态收掉即可。
- `partial` 是**已生成正文的字符数**。大于 0 说明回答没写完，
  前端可以酌情提示一句「回答未完成」，也可以只在 UI 上留个标记。
- 停止时缓冲里没发完的尾部正文会**先补发完**再关流，所以不会凭空少一截。
- **已生成的内容照常落库**（服务端按 `runId` 幂等补写），
  用户刷新页面仍能看到这一段，不会凭空消失。
- 与 `finish` 的区别：`finish` 表示模型答完了（可触发"自动滚动到底"等后续动作），
  `stopped` 表示用户叫停了（**不该**触发那些自动动作）。

### 3.8 `error`

```json
{"seq":7,"runId":"a1b2…","event":"error",
 "data":{"type":"ERROR","message":"Connection timeout","hint":"请把 runId 提供给开发者，可在服务端日志中检索 \"RUN runId=a1b2…\" 定位本次运行",
         "ttfbMs":1820,
         "contextWindow":300000,"contextUsed":12480,"contextMsgs":42,"contextRatio":0.0416}}
```

**载荷里的 `ttfbMs` 与 `context*` 见 §3.6，与 `finish` 完全一致。**

- 出错前会**先把已生成的正文冲刷出去**（这些内容是有效的，不该被吞）。
- **不推堆栈**：前端读不懂，也可能泄露内部细节。定位靠 `runId` 查服务端日志。
- `runId` 在信封里（v1 放在 `data` 里）。

---

## 4. 认证与传输

| 项 | 值 |
|---|---|
| 鉴权头 | **`token: <JWT>`**（❗不是 `Authorization: Bearer`） |
| 未登录 | `401` + JSON `{"code":1,"msg":"NOT_LOGIN","data":null,"total":null}`<br>（由 `LoginCheckInterceptor.reject` 写出，`Content-Type: application/json`；**不是纯文本**，早期版本此处写错过） |
| Content-Type | `text/event-stream` |
| CORS | ✅ 已配置（`CorsFilter`）。dev 默认放行 `http://localhost:5173`；**prod 默认关闭**。配置见 `nexus.agent.cors.*`（`docs/前端开发指南.md` §1.2）。放行请求头：`token`、`Content-Type` |

⚠️ **不能用浏览器原生 `EventSource`**：它不支持自定义请求头，带不上 `token`。
请用 `fetch` + `ReadableStream` 自己解析，或用 `@microsoft/fetch-event-source`。

### 解析示例（fetch + ReadableStream）

```js
const res = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', token },
  body: JSON.stringify({ messages, sessionId, enableRag: true }),
  signal,
});

const reader = res.body.getReader();
const decoder = new TextDecoder();
let buf = '';

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buf += decoder.decode(value, { stream: true });

  // SSE 以空行分帧
  let idx;
  while ((idx = buf.indexOf('\n\n')) !== -1) {
    const raw = buf.slice(0, idx);
    buf = buf.slice(idx + 2);
    handleFrame(raw);
  }
}

function handleFrame(raw) {
  let event = 'message';   // 缺省事件名
  let id = null;
  const dataLines = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('id:')) id = line.slice(3).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
  }
  const envelope = JSON.parse(dataLines.join('\n'));
  // 统一解析：{ seq, runId, event, data }
  onEvent(envelope.event, envelope.data, envelope.seq, envelope.runId);
}
```

---

## 5. 断线重连：**当前能做什么、不能做什么**

**能做的**：

- 每帧有 `seq`，前端可以**发现丢帧**（跳号）。
- `seq` 写进了 SSE 原生 `id:`，浏览器 `EventSource` 重连时会带上 `Last-Event-ID`
  （若用 `fetch` 自行实现，需自己记住最后的 `seq`）。
- 每帧有 `runId`，出错后可凭它去服务端日志里查完整现场。

**没做的（诚实说明）**：

- **服务端回放未实现。** 「断线后从 `seq=N` 继续吐帧」需要把事件持久化下来
  （内存/Redis/PG），本次没有做——收益有限（一次对话通常几秒~几十秒），
  成本却是一张新表或一套缓存。
- 现在的正确做法：断线后**用 `sessionId` 重新拉历史**（`GET /api/history/{sessionId}`）
  恢复已完成的消息，并提示用户重发最后一条。

---

## 6. v1 → v2 迁移（**破坏性变更**）

| 变更 | v1 | v2 | 迁移 |
|---|---|---|---|
| 事件名大小写 | `message`/`session_id`/`finish` 小写，但 `TOOL_EXECUTION`/`TOOL_EXECUTION_RESULT` **全大写** | 全部小写 | 监听名改小写 |
| data 结构 | 各事件形状不一（MessageVO / 裸字符串 / `"DONE"`） | 统一信封，原载荷移到 `data` | 解析时多取一层 `.data` |
| `session_id` 事件 | 仅新会话，且在**流结束时** | **取消**，并入首帧 `run` | 监听 `run` 取 `sessionId` |
| `finish` data | 字符串 `"DONE"` | `{"status":"DONE"}` | 改读 `.data.status` |
| `error` 的 `runId` | 在 `data` 里 | 提升到信封层 | 改读 `.runId` |

> 之所以敢一次改干净：**当前没有存量前端**（只有 `static/showHistory.html` 调试页，
> 且它读的是 REST 历史接口，不消费 SSE）。前端由我们自己在 P3-1 写，契约现在定死最省事。

---

## 7. 渲染最佳实践（后端已减帧，前端仍要做）

后端把逐 token 推送改成了批量推送（P2-12），帧数下降一个数量级，但前端仍应：

1. **按 `requestAnimationFrame` 批量提交** DOM 更新，不要每个事件 `setState` 一次。
2. **思考与正文分开累积**（`type` 不同）。
3. **Markdown 延后整体渲染**：流式过程中渲染 Markdown 会反复重排，等 `finish` 后再渲染一次。
4. **自动滚动节流**，且用户手动上滚时停止自动滚动。
5. **工具参数按 `id` 累积拼接**，别对半截 JSON 做 `JSON.parse`。
