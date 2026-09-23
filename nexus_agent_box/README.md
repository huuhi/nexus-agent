# nexus-agent-box

**沙盒与 MCP 请求服务** —— 独立的 Python / FastAPI 应用，由 Java 端通过 HTTP 调用。

Java 侧对应代码：`nexus-agent-service/.../tools/BoxTool.java`（把这里的接口包装成 AI 工具）。
整体说明见仓库根目录的 [AGENTS.md](../AGENTS.md) 与 [README.md](../README.md)。

## 它负责什么

| 能力 | 路由 |
|---|---|
| 创建 / 销毁沙盒 | `GET /box`、`DELETE /box/{box_id}` |
| 沙盒内文件操作 | `POST /file`（上传）、`GET /file`（下载并回传 OSS）、`GET /file/list`、`GET /file/exists`、`POST /file/create` |
| 执行代码 | `POST /execute/code`，body `{code, box_id}` |
| 执行命令 | `POST /execute/cmd`，body `{cmd, box_id}` |
| 代理 MCP 列表 | `GET /mcp?token=`（转发 ModelScope openapi） |

> ⚠️ **沙盒用的是 E2B 云沙盒**（`e2b-code-interpreter`），不是本地 Docker。
> 代码实际跑在 E2B 云端容器里，因此 **AI 无法直接读写你本机的文件** ——
> 本地文件必须走「上传 → 处理 → 下载产物」流转。详见 AGENTS.md §15.4。

## 运行

```bash
# 1) 配置
cp .env.example .env      # 填入 E2B_API_KEY（以及回传 OSS 用的阿里云凭证）

# 2) 启动（开发模式，8000 端口）
uv run main.py

# 或容器模式
docker compose up -d
```

依赖由 **uv** 管理（`pyproject.toml` + `uv.lock`），Python **>= 3.12**。

## 环境变量

| 变量 | 必需 | 用途 |
|---|---|---|
| `E2B_API_KEY` | ✅ | E2B 云沙盒鉴权 |
| `ALIBABA_CLOUD_ACCESS_KEY_ID` | 下载文件时需要 | `app/utils/oss_utils.py` 用 oss2 的 `EnvironmentVariableCredentialsProvider`，**变量名固定不能改** |
| `ALIBABA_CLOUD_ACCESS_KEY_SECRET` | 同上 | 同上 |

## 注意

- 沙盒创建后由 E2B 侧超时回收（`Sandbox.set_timeout`，当前 600 秒），
  **Java 端目前没有主动回收机制** → 属重构计划 P1-7，会持续产生按量费用。
- `app/utils/oss_utils.py` 里 OSS 路径前缀 `test/` 是硬编码的，
  应改为按用户隔离（`user/{userId}/artifact/...`）→ 属重构计划 P2-10。
