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

---

## 2. 事件一览

| # | `event` | 何时出现 | 载荷 `data` |
|---|---|---|---|
| 1 | `run` | **第一帧**（幂等，只发一次） | `{sessionId, isNewSession}` |
| 2 | `message` | 思考/正文增量，多次 | `MessageVO{type:"THINK"\|"CONTENT", thinking?, content?}` |
| 3 | `tool_execution` | 模型发起工具调用，可能多次 | `MessageVO{type:"TOOL_EXECUTION", toolRequestList:[{id,toolName,arguments}]}` |
| 4 | `tool_execution_result` | 工具返回，每个调用一次 | `MessageVO{type:"TOOL_EXECUTION_RESULT", toolResultVO:{id,toolName,result,isError}}` |
| 5 | `artifact` | AI 交付文件（P2-10） | `MessageVO{type:"ARTIFACT", artifact:{id,name,url,size,extension,sourcePath}}` |
| 6 | `finish` | 正常结束，一次 | `{status:"DONE"}` |
| 7 | `stopped` | **用户主动叫停**，一次（2026-10-06 新增） | `{reason:"用户停止了本次生成", partial:1234}` |
| 8 | `error` | 运行失败，一次 | `{type:"ERROR", message, hint}` |

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
（`{"cmd":` → `{"cmd":"ls` → …）。要展示参数，请**按 `id` 累积拼接**，
不要每次都当成完整 JSON 去解析（早期帧是非法 JSON）。

### 3.4 `tool_execution_result`

```json
{"seq":9,"runId":"…","event":"tool_execution_result",
 "data":{"type":"TOOL_EXECUTION_RESULT","toolResultVO":{"id":"call_1","toolName":"execute_cmd","result":"…","isError":false}}}
```

用 `id` 与 `tool_execution` 配对。`isError=true` 时 `result` 是结构化错误
（`{success,errorCode,message,hint}` 的 JSON 文本，见 `AGENTS.md §6.5`）。

### 3.5 `artifact`（交付物）

```json
{"seq":12,"runId":"…","event":"artifact",
 "data":{"type":"ARTIFACT","artifact":{"id":91,"name":"报告.docx","url":"https://…","size":12345,"extension":"docx","sourcePath":"/tmp/报告.docx"}}}
```

`id` 是 `sys_file` 主键，可用来去重与追溯（`GET /api/artifact?sessionId=` 会返回同一批）。

### 3.6 `finish`

```json
{"seq":13,"runId":"…","event":"finish","data":{"status":"DONE"}}
```

v1 是裸字符串 `"DONE"`，v2 改成对象以便携带信封字段。

### 3.7 `stopped`（2026-10-06 新增）

```json
{"seq":9,"runId":"a1b2…","event":"stopped",
 "data":{"reason":"用户停止了本次生成","partial":1234}}
```

用户在界面上点了「停止生成」，由 `POST /api/chat/stop` 触发。

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
 "data":{"type":"ERROR","message":"Connection timeout","hint":"请把 runId 提供给开发者，可在服务端日志中检索 \"RUN runId=a1b2…\" 定位本次运行"}}
```

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
