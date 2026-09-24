# 前端对接指南

> **给谁看**：前端开发。
> **目标**：不读后端源码，也能把界面接起来，并且知道**这次后端改了什么、你需要跟着改什么**。
> **后端版本**：2026-09-24（P2 批次完成后，`ds` 分支）。

---

## 0. 一分钟速览

| 项 | 结论 |
|---|---|
| 基址 | `/api`（下文路径都省略这个前缀） |
| 鉴权 | **HTTP header `token: <jwt>`**（注意：不是 `Authorization`） |
| 对话 | `POST /api/chat/stream`，**SSE 流式**，`Content-Type: text/event-stream` |
| ⚠️ 关键坑 | **不能用 `EventSource`** —— 它不支持自定义请求头，传不了 `token`。用 `fetch` + `ReadableStream` 手写解析，或用 `@microsoft/fetch-event-source` |
| 事件类型 | 7 种：`message` / `TOOL_EXECUTION` / `TOOL_EXECUTION_RESULT` / `session_id` / `finish` / `error` / `artifact` |
| ⚠️ 事件名大小写 | **不统一**（历史遗留）：小写 `message`、`session_id`、`finish`、`error`、`artifact`；**全大写** `TOOL_EXECUTION`、`TOOL_EXECUTION_RESULT` |
| 跨域 | 后端已放开（`allowedOriginPatterns: *` + credentials） |
| 流式流畅度 | 后端已做**增量合并**（一次推送攒 200 字符或 60ms），前端**不要再逐事件 setState**（见 §5） |

---

## 1. 认证

```http
POST /api/user/login
Content-Type: application/json

{ "email": "a@b.com", "password": "..." }
```

响应（统一信封）：

```json
{ "code": 0, "msg": null, "data": "<jwt 字符串>", "total": null }
```

- `code = 0` 表示成功，其他值为失败（**HTTP 状态码可能仍是 200**，务必按 `code` 判断）
- 之后所有请求带上 `token: <jwt>`

**免鉴权白名单**：`/api/user/login`、`/api/user/register`、`/api/user/password`、`/api/common/email`。

**未登录/无效 token**：HTTP **401**，body 是纯文本 `NOT_LOGIN`（不是 JSON 信封），前端可据此跳登录页。

---

## 2. 一次完整对话（推荐流程）

```
1) 用户发消息
2) POST /api/chat/stream  →  建立 SSE 流
3) 持续接收事件：
   session_id（仅新会话）→ message（思考/正文）→ TOOL_EXECUTION/RESULT（工具）
   → artifact（产物）→ finish（结束）
4) 收到 finish 后关闭连接；若中途收到 error，按 §4.4 展示并保留 runId
```

### 请求体

```json
{
  "messages": [
    { "type": "TEXT", "content": "帮我把这份数据做成周报", "metadata": null }
  ],
  "sessionId": "8b1e...",          // 首轮传 null 或省略，后端会生成并下发
  "skills": ["data-report"],       // 可选：本次要启用的技能名；不传=全部启用
  "MCPs": [12],                    // 可选：本次要启用的 MCP 配置 id
  "model": {                       // 可选：不传则用系统默认模型
    "id": "cfg-1", "modelName": "deepseek-v4-flash", "isThinking": true
  },
  "enableRag": false               // 可选：是否启用知识库检索
}
```

`messages[].type` 取值：`TEXT` / `FILE` / `IMAGE`
（`FILE`/`IMAGE` 的 `metadata` 里带文件信息，格式与文件上传接口返回一致）。

### 前端调用（原生，框架无关）

```ts
async function chat(body: unknown, onEvent: (event: string, data: any) => void) {
  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'token': localStorage.getItem('token')!,   // ⚠️ header 名就是 token
      'Accept': 'text/event-stream',
    },
    body: JSON.stringify(body),
  });

  if (resp.status === 401) throw new Error('未登录');

  const reader = resp.body!.getReader();
  const decoder = new TextDecoder();
  let buf = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });

    // SSE 以空行分隔事件
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const raw = buf.slice(0, idx);
      buf = buf.slice(idx + 2);

      let event = 'message';
      let dataLine = '';
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLine += line.slice(5).trim();
      }
      if (dataLine) onEvent(event, JSON.parse(dataLine));
    }
  }
}
```

---

## 3. REST 接口清单

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/chat/stream` | **SSE 流式对话**（见 §4） |
| POST | `/api/chat/model` | 拉取某个 baseUrl+token 下的可用模型列表（body: `{baseUrl, token}`） |
| GET | `/api/history` | 会话列表 |
| GET | `/api/history/{sessionId}` | 某会话的历史消息 |
| DELETE | `/api/history?sessionId=` | 删除会话 |
| GET | `/api/artifact?sessionId=` | **列出本会话 AI 交付的产物**（P2-10 新增） |
| DELETE | `/api/artifact/{id}` | **删除产物**（P2-10 新增） |
| POST | `/api/file` | 上传文件（`multipart/form-data`，字段 `files[]` + `bizType`） |
| POST/GET | `/api/user/api-config` | 增改 / 查 用户的 LLM 配置（baseUrl、Key、模型列表） |
| POST/GET | `/api/user/mcp-config` | 增改 / 查 MCP Token |
| GET/DELETE | `/api/user/user-memory[/{id}]` | 长期记忆 查 / 删 |
| POST | `/api/user/login` `/register` `/password` | 认证（免 token） |
| POST | `/api/common/email` | 邮箱验证码（免 token） |

---

## 4. SSE 事件契约

### 4.1 事件总表

| event | data | 说明 |
|---|---|---|
| `session_id` | `"8b1e..."` | **仅新会话**时推送一次，前端要记下来用于下一轮 |
| `message` | `{type:"THINK"\|"CONTENT", thinking?, content?}` | 思考 / 正文的**增量**（不是全量！要累加） |
| `TOOL_EXECUTION` | `{type:"TOOL_EXECUTION", toolRequestList:[{id,toolName,arguments}]}` | 工具调用请求。⚠️ **全大写**；`arguments` 是**流式片段**，同一次调用会被拆成多个事件 |
| `TOOL_EXECUTION_RESULT` | `{type:"TOOL_EXECUTION_RESULT", toolResultVO:{id,toolName,result,isError}}` | 工具执行结果。⚠️ **全大写**、只有终态 |
| `artifact` | `{type:"ARTIFACT", artifact:{id,name,url,size,extension}}` | **AI 产出的文件交付**（P2-10 新增）→ 渲染成下载卡片 |
| `error` | `{type:"ERROR", runId, message, hint}` | **运行失败**（P2-6 新增）→ 见 §4.4 |
| `finish` | `"DONE"` | 正常结束，之后连接关闭 |

### 4.2 data 示例

```jsonc
// 思考增量
event:message
data:{"type":"THINK","thinking":"让我先看一下数据"}

// 正文增量（注意是增量，前端要 append 而不是替换）
event:message
data:{"type":"CONTENT","content":"好的，"}

// 工具调用（全大写事件名）
event:TOOL_EXECUTION
data:{"type":"TOOL_EXECUTION","toolRequestList":[{"id":"call_1","toolName":"execute_cmd","arguments":"{\"cmd\":\"ls\"}"}]}

// 工具结果
event:TOOL_EXECUTION_RESULT
data:{"type":"TOOL_EXECUTION_RESULT","toolResultVO":{"id":"call_1","toolName":"execute_cmd","isError":false,"result":"{\"success\":true,\"stdout\":\"a.txt\"}"}}

// 产物交付（P2-10）
event:artifact
data:{"type":"ARTIFACT","artifact":{"id":"1957...","name":"周报.xlsx","url":"https://.../user/1/artifact/2026-09-24/x.xlsx","size":20480,"extension":"xlsx"}}

// 运行失败（P2-6）
event:error
data:{"type":"ERROR","runId":"9f2c8a1b3d4e5f60","message":"Connection reset","hint":"请把 runId 提供给开发者，可在服务端日志中检索 \"RUN runId=9f2c8a1b3d4e5f60\" 定位本次运行"}
```

### 4.3 ⚠️ 事件名大小写不统一（历史遗留）

代码里**小写**是写死的字面量，而两个工具事件用的是枚举值（**全大写**）。
按 `event: tool_execution` 监听会永远收不到工具事件。

```ts
// ✅ 正确
switch (event) {
  case 'message': break;
  case 'TOOL_EXECUTION': break;
  case 'TOOL_EXECUTION_RESULT': break;
  case 'session_id': break;
  case 'finish': break;
  case 'error': break;
  case 'artifact': break;
}
```

> 这个不统一**会在 P2-5（SSE 契约版本化）里统一成小写**。
> ⚠️ 那是**破坏性变更**，做之前会先和前端确认；现在实现时请**按上面这张表**来，
> 并把事件名的判断集中在一处（一个常量表 / 一个 switch），将来改动只需动一处。

### 4.4 关于 `error` 事件

- 出错时后端**先推 `error` 再关闭连接**（在此之前前端只会看到"连接莫名其妙断了"，拿不到任何线索）
- `runId` 是本次运行的 trace_id：**把它展示在错误提示里**（或上报），
  服务端日志里搜 `RUN runId=<值>` 就能定位这次运行的模型、token、工具序列、错误类型
- 收到 `error` 后不要再等 `finish`（不会有）

---

## 5. 流式渲染：怎么让它不卡

### 5.1 后端做了什么（P2-12）

原来模型每吐一个 token（通常几个字符）就推一个 SSE 事件 —— 一次千字回复就是**上千个帧**，
前后端都被高频小包拖慢，用户感受就是"字一个一个蹦、还卡"。

现在后端会**合并增量**：攒够 **200 字符** 或距上次推送超过 **60ms** 才推一帧
（配置项 `nexus.agent.sse.flush-max-chars` / `flush-interval`，可调）。

另外后端在**关键时机强制冲刷**：推工具事件前、推产物事件前、结束前 ——
所以**顺序是有保证的**：正文一定在它之后的工具卡片之前到达，回复的尾部不会丢。

**→ 前端不需要自己再做"防抖/节流"来减轻网络压力了。**

### 5.2 前端仍然要做的（否则还是卡）

后端减少的是**事件数量**；前端如果**每个事件都触发一次整棵消息列表的重渲染**，照样卡。

推荐做法：

```ts
// ❌ 反例：每个事件都改响应式状态 → 每帧一次 diff + patch
onEvent('message', d => { text.value += d.content; });

// ✅ 推荐：先把增量写进普通变量，按动画帧批量提交
let pendingText = '';
let rafId = 0;

function appendText(chunk: string) {
  pendingText += chunk;
  if (rafId) return;
  rafId = requestAnimationFrame(() => {
    rafId = 0;
    text.value += pendingText;   // 一帧最多更新一次
    pendingText = '';
  });
}
```

其他要点：

- **思考区与正文区分渲染**：`type` 为 `THINK` 的增量进思考区（可折叠），`CONTENT` 进正文；
  两者是**交错**到达的，不要混进同一个字符串
- **自动滚动要节流**：不要每帧 `scrollIntoView`；判断"用户是否在底部附近"再自动滚，
  否则用户往上翻历史时会被强行拽回底部
- **长回复用 `v-once` / 虚拟滚动**：几千字的内容反复重渲染同样会卡
- **Markdown 渲染放到"帧批量提交"之后**，不要每个 chunk 都重新解析整段（那是 O(n²) 的开销）；
  流式过程中可以先用纯文本 + 结束后再整体渲染 Markdown
- **工具事件**：`arguments` 是流式片段，展示时按 `id` 聚合、拼起来再显示（或只显示工具名 + loading）

---

## 6. 本次改了什么（前端要跟着改的清单）

| 变化 | 影响 | 前端动作 |
|---|---|---|
| **新增 `artifact` 事件** | AI 生成的文件会主动交付 | 新增下载卡片渲染；或改用 `GET /api/artifact?sessionId=` 拉本会话产物列表 |
| **新增 `error` 事件** | 出错时不再只是"连接断了" | 监听 `error`，展示 `message` 并保留 `runId`（便于报障） |
| **新增产物接口** | 可以列出/删除本次会话的 AI 产物 | 可做"本会话文件"面板 |
| **删除产物接口的删除语义** | 先删记录、再尽力删 OSS 对象；返回 `false` 时统一提示"不存在或无权删除" | 删除成功后从列表移除即可 |
| **增量合并** | 事件变少、单帧内容变多 | 确保是 **append 语义**（不是替换）；按 §5.2 批量渲染 |
| **配额超限** | 对话开始即失败 | 返回的是**业务错误信封**（`code != 0`，HTTP 200），`msg` 形如「您的 token 配额已用完（已用 x / 上限 y）」→ 直接用 `msg` 提示用户 |
| **技能 / MCP 可传参** | `skills`、`MCPs` 字段生效 | 模型详情页可做"本次启用哪些技能/服务"的多选 |
| **`thinking` 开关** | 部分服务商不支持 | 后端会忽略并打日志，**前端不需要特殊处理**，但建议 UI 上标注"部分模型不支持" |

---

## 7. 已知坑（踩过的）

| 坑 | 说明 |
|---|---|
| `EventSource` 用不了 | 不支持自定义 header；用 `fetch` + `ReadableStream`（见 §2） |
| 事件名大小写 | 两个工具事件是全大写（§4.3） |
| HTTP 200 也可能是失败 | 业务异常返回 HTTP 200 + `code != 0`，**必须按 `code` 判断** |
| `message` 是增量 | 思考与正文都是增量且**交错**，要分别累加 |
| 工具 `arguments` 是片段 | 同一次工具调用会拆成多个事件，按 `id` 聚合 |
| 未登录返回非 JSON | 401 + 纯文本 `NOT_LOGIN` |
| 长任务不要设前端超时太短 | 复杂任务（多轮工具调用）可能跑几十秒；SSE 连接由服务端控制（默认 120s），前端别自己 30s 就断开 |
| 沙盒文件预览有延时 | 产物是上传到 OSS 后才给 URL，拿到即为可访问 |

---

## 8. 调试

**用 curl 直接看事件流**（最快）：

```bash
curl -N -X POST 'http://localhost:8080/api/chat/stream' \
  -H 'Content-Type: application/json' \
  -H 'token: <你的jwt>' \
  -H 'Accept: text/event-stream' \
  -d '{"messages":[{"type":"TEXT","content":"你好"}]}'
```

**定位某次运行**：拿到 `error` 事件的 `runId` 后，在服务端日志里搜：

```bash
grep "runId=9f2c8a1b3d4e5f60" app.log
```

会看到一行汇总：

```
RUN runId=9f2c... model=deepseek-v4-flash cost=7.6s tokens=1234/567/1801(in/out/total) \
    fee=¥0.0071 tools=3 seq=[create_box,execute_cmd] result=OK
```

---

## 9. 相关文档

| 文档 | 内容 |
|---|---|
| `AGENTS.md §6.2` | SSE 事件契约（后端视角，与本文 §4 对应） |
| `AGENTS.md §6.12` | 可观测性：`RUN` 汇总日志字段含义 |
| `AGENTS.md §6.13` | token 配额语义（什么时候会被拒） |
| `AGENTS.md §16` | 文件与产物能力：产物从产生到交付的完整链路 |
| `README.md` | 环境搭建、启动方式 |
