# WebSocket 接入 · 前端对接文档（2026-10-04 安全修复）

> 面向前端。**本次是破坏性变更**：WebSocket 从"连上就能用"改成"必须带 token"。
> 不改前端的话，标题推送会**直接连不上**（不是降级，是握手就失败）。
>
> 关联：`docs/前端开发指南.md`（通用约定）。
> 后端实现：`WebSocketConfiguration` / `WebSocketAuthInterceptor` / `WebSocketService`。
>
> **本文所有字段与路径都来自真实代码，不是猜的。发现不一致以代码为准并回来改本文。**

---

## 0. 四件必须先知道的事

| # | 事项 | 结论 |
|---|---|---|
| 1 | **路径变了** | 现在是 `/api/ws/{userId}`（**带 `/api` 前缀**）。旧文档里写的 `/ws/{userId}` 是错的 |
| 2 | **必须带 token** | 握手阶段校验 JWT，**没有 token 直接拒绝**。2026-10-04 之前是零鉴权（ Anyone 可连） |
| 3 | **URL 里的 userId 必须等于 token 里的 user_id** | 不一致会被拒。只校验"token 有效"是不够的 —— 那等于任何登录用户都能订阅别人 |
| 4 | **只有一种推送：`title`** | 消息形如 `{"type":"title","data":"..."}`。别的 `type` 还没实现，但**请按 `type` 分发**，别硬编码只认 title |

---

## 1. 为什么突然要传 token（背景，理解即可）

修复前端点用 `@ServerEndpoint` + `ServerEndpointExporter` 注册。这是 **JSR-356 原生端点**，
由 Servlet 容器直接创建和处理，**不经过 Spring MVC 的 DispatcherServlet**，所以项目里的
`LoginCheckInterceptor` 对它 **100% 无效**。而当时的 `onOpen` 是这样的：

```java
public void onOpen(Session session, @PathParam("userId") String userId) {
    CLIENTS.put(userId, session);   // 直接信任 URL 里的 userId
}
```

后果有两条，都很严重：

1. `userId` 是自增整数 → **任何人都能枚举并订阅别人的消息推送**
2. WebSocket **不受浏览器同源策略约束**（CORS 管不到它）→ 任意网站发起的连接都能建立

现在后端改成 `@EnableWebSocket` + `WebSocketConfigurer`，端点由 Spring 托管，
握手阶段用 `WebSocketAuthInterceptor` 校验 token，并限制来源域名。

> ⚠️ 这意味着**你的前端域名必须出现在后端的 `nexus.agent.cors.allowed-origins` 里**
> （和 REST 接口的跨域配置是同一个配置项），否则浏览器连不上。
> 详见 §5。

---

## 2. 连接怎么建（三选一，推荐 A）

### 2.1 方式 A：子协议传 token（**浏览器唯一推荐**）

浏览器的 `new WebSocket(url)` **不能自定义请求头**，所以不能像 REST 那样用 `token` 头。
但可以通过**子协议**把 token 带过去，而且它**不会出现在 URL / nginx 访问日志里**。

```ts
// src/ws/titleSocket.ts
const token = localStorage.getItem('token');
if (!token) throw new Error('未登录');

// ⚠️ token 是**独立的第二项子协议**，不是拼在 "nexus-token" 后面
const ws = new WebSocket(
  `ws://localhost:8080/api/ws/${userId}`,
  ['nexus-token', token],       // ← 浏览器会发成 Sec-WebSocket-Protocol: nexus-token, eyJ...
);
```

> ❗ **最容易写错的一处**：写成 `['nexus-token' + token]`（拼接）是**少数手写客户端**的形态，
> 后端兼容，但浏览器的标准写法是**数组两项**。两种后端都认，你按数组写就对了。

### 2.2 方式 B：查询参数（兼容性最好，但会进日志）

```
ws://localhost:8080/api/ws/1?token=eyJhbGciOi...
```

```ts
const ws = new WebSocket(`ws://localhost:8080/api/ws/${userId}?token=${encodeURIComponent(token)}`);
```

⚠️ **token 会进 nginx access log**。用这个方式的前提是你能确认日志已脱敏，
或者在 nginx 里对 `/api/ws` 关闭/脱敏日志。**优先用方式 A。**

### 2.3 方式 C：请求头（仅原生客户端）

```bash
websocat -H="token: eyJhbGciOi..." ws://localhost:8080/api/ws/1
```

Python `websockets` 库：

```python
import json, websockets
async with websockets.connect(
    "ws://localhost:8080/api/ws/1",
    additional_headers={"token": token},      # websockets ≥ 12；旧版叫 extra_headers
) as ws:
    async for msg in ws:
        print(msg)      # {"type":"title","data":"..."}
```

浏览器做不到这条路 —— 不用试。

---

## 3. userId 从哪来

后端从 token 的 `user_id` claim 里取，前端只是**把它写进 URL**（后端会比对，一致才放行）。

登录/注册接口返回的 JWT 里就有：

```jsonc
// JWT payload（base64url 解码即可看到，不需要验签）
{
  "user_id": 1,
  "image_url": "https://...",
  "user_name": "张三"
}
```

前端两种拿法：

```ts
// 方案 1（推荐）：登录时把 userId 一起存下来
const res = await login({ email, code, type: 'CODE' });
localStorage.setItem('token', res.data);
localStorage.setItem('userId', decodeJwt(res.data).user_id);   // 见下

// 方案 2：已登录的页面里解 token
function decodeJwt(token: string) {
  const [, payload] = token.split('.');
  return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
}
const userId = decodeJwt(localStorage.getItem('token')!).user_id;
```

⚠️ **不需要（也不该）用 `jwt-decode` 之类的库**，payload 只是 base64，5 行代码够了。
真要装包，`atob` 的 URL-safe 字符替换别漏（JWT 用的是 `-` / `_`）。

---

## 4. 消息格式与推荐实现

### 4.1 收到的消息

目前**只有一种**推送 —— 标题生成完成：

```jsonc
{
  "type": "title",
  "data": "帮我分析这段代码的性能瓶颈"
}
```

来源：`ChatHistoryListServiceImpl.createTitle()` 生成标题后调
`webSocketService.sendToClient(userId, json)`。

> 📌 生成标题是 `@Async` 的，通常在流式回答结束后的**几秒内**才到。所以
> "会话列表里的标题会在稍后自己跳一下"是正常的，别当成 bug。

### 4.2 完整实现（复制即用）

```ts
// src/ws/titleSocket.ts
type TitleMsg = { type: 'title'; data: string };

export function connectTitleSocket(onTitle: (t: string) => void) {
  const token = localStorage.getItem('token');
  const userId = localStorage.getItem('userId');
  if (!token || !userId) return null;

  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/api/ws/${userId}`, ['nexus-token', token]);

  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data) as TitleMsg;
      // ⚠️ 按 type 分发，不要写死只处理 title —— 后续加新类型时你不会漏
      if (msg.type === 'title') onTitle(msg.data);
    } catch { /* 忽略非法帧，别让一条脏数据打断整条连接 */ }
  };

  // 连接失败最多重试 3 次，退避；token 失效时不要再重连（重连也没用）
  let retries = 0;
  ws.onclose = () => {
    if (retries < 3 && localStorage.getItem('token')) {
      retries += 1;
      setTimeout(() => connectTitleSocket(onTitle), 1000 * retries);
    }
  };
  return ws;
}
```

### 4.3 三个必踩的坑

| 坑 | 现象 | 对策 |
|---|---|---|
| **同一用户重复连接会被踢** | 开了两个标签页，第二个把第一个踢下线（后端只保留最新一条连接） | 做成全局单例，别在每个组件里各连一次 |
| **子协议写错** | `onclose` 秒触发，F12 里 `Sec-WebSocket-Protocol` 只有一项 | 必须是 `['nexus-token', token]` 两项 |
| **来源没配** | 连不上，F12 显示握手 **403** | 把你的前端域名加进 `nexus.agent.cors.allowed-origins`（§5） |

> 第 1 条是后端**刻意**的行为：修复前是无条件覆盖，攻击者用同一 userId 连上就能
> 把受害者挤下线并接管他的推送。**单例是前端该配合的，不是 bug。**

---

## 5. 服务端配置（你要告诉运维/后端的一行）

```bash
# 你的前端在 https://ai.example.com，后端在 https://api.example.com
--nexus.agent.cors.allowed-origins=https://ai.example.com
```

- **和 REST 接口的跨域是同一个配置项**，不用另加。
- 多个域名用英文逗号分隔：`-Dnexus.agent.cors.allowed-origins=https://a.com,https://b.com`
- **留空 = 只接受同源连接**（不是"放行所有"）。
- 本地默认已放行 `http://localhost:5173`。你换端口就要改。

启动日志里会明确告诉你配成什么样：

```
WebSocket 已注册（路径 /api/ws/{userId}），跨域来源=[https://ai.example.com]
```

⚠️ **prod 环境要看 `application-prod.yml`** —— 它优先级高于 `application.yml`，
在后者的 `nexus.agent.*` 段里改会被**静默覆盖**。用启动参数或
`conf/nexus-override.yml`（见 `docs/部署指南（改配置不重打包）.md`）。

---

## 6. 联调自检清单

```
□ F12 Network 里筛选 WS，能看到 ws://.../api/ws/1，状态 101 Switching Protocols
□ 连接的 Request Headers 里 Sec-WebSocket-Protocol: nexus-token, eyJhbGciOi...
□ 发一条消息，等流式回答结束 2~5 秒，会话列表标题自动刷新
□ 把 URL 里的 1 改成 2 → 连接被拒（F12 里握手失败），说明身份比对生效
□ 去掉子协议只留 ['nexus-token'] → 连接被拒
□ 把 token 改错 → 连接被拒
□ 前端域名不在 allowed-origins → 握手 403，且后端日志有对应记录
```

---

## 7. 我不做的事（省得你找）

| 事项 | 现状 |
|---|---|
| **心跳 / ping-pong** | 后端没发 ping。多数反向代理（nginx `proxy_read_timeout` 默认 60s）会掐掉空闲连接，靠 §4.2 的 `onclose` 重连兜住即可 |
| **消息回放** | 连上之后只会收到新消息。**断线期间产生的标题推送会丢** —— 重连后调 `GET /api/history` 拉一次列表即可 |
| **多端同时在线** | 不支持。同一 userId 只保留最新一条连接（§4.3 第 1 条） |
| **服务端主动心跳帧** | 没有自定义 `ping` 类型。要判活就靠 WebSocket 原生的 `onopen`/`onclose` |
| **SSE 迁到 WS** | 没有。流式对话仍走 `POST /api/chat/stream`（SSE），只有标题走 WS |
