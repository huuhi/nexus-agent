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

## 本地自测（不依赖外网 / 不依赖 OSS / 不依赖真实沙盒）

```bash
python tools/test_url_guard.py          # SSRF 防护（起本地 server，验协议/重定向/体积上限）
python tools/test_binary_roundtrip.py   # 产物二进制保真（验 PNG 魔数、docx 可解包）
```

两个脚本都用桩对象替换 `Sandbox` / `upload_bytes`，**不消耗 E2B 额度、不需要阿里云凭证**。
`test_binary_roundtrip.py` 自带元测试：它先证明「模拟确实会复现损坏」，
再验证修复 —— 否则一个写错的模拟会全绿放行，等于什么都没测。

## 环境变量

| 变量 | 必需 | 用途 |
|---|---|---|
| `E2B_API_KEY` | ✅ | E2B 云沙盒鉴权 |
| `ALIBABA_CLOUD_ACCESS_KEY_ID` | 下载文件时需要 | `app/utils/oss_utils.py` 用 oss2 的 `EnvironmentVariableCredentialsProvider`，**变量名固定不能改** |
| `ALIBABA_CLOUD_ACCESS_KEY_SECRET` | 同上 | 同上 |

## 🔴 二进制文件必须走 bytes 通道（2026-10-05 修复 P0）

用户交付出去的 PNG / docx / xlsx **全部打不开**，报"文件已损坏"。根因在
`app/routers/file.py::download_file`：

```python
# ❌ 修复前：E2B SDK 的 format 默认是 "text"，实现是 return r.text
content = sbx.files.read(file_path)     # httpx 按 charset 解码，非法字节 → U+FFFD
byte_array = content.encode()

# ✅ 修复后：显式走 bytes，不经过任何编解码
content = sbx.files.read(file_path, format="bytes")   # SDK 内部 bytearray(r.content)
byte_array = bytes(content)                           # 统一收口，SDK 返回的是 bytearray
```

损坏形态（PNG 头）：

```
原始：  89 50 4E 47 0D 0A 1A 0A
损坏后：EF BF BD 50 4E 47 0D 0A      ← EF BF BD 就是 U+FFFD 的 UTF-8 编码
```

两个后果，缺一不可：
1. 首字节不再是魔数 → 按格式识别的工具直接拒绝打开；
2. 每个非法字节 1 字节变 3 字节 → **体积凭空膨胀**（实测 94367 → 171196），
   这正是当年"上报 171196、实际 94367"的原因。

docx / xlsx / pptx / zip 同理 —— 都是 zip 容器，任一字节被替换都会让解包失败。

配套的两道防线：
- `oss_utils.upload_bytes`（原名 `str_upload_file`）**拒收 str**，传错类型第一行就抛 `TypeError`；
- 上传时显式给 `Content-Type`（`guess_content_type`），让浏览器/Office 认得这是 docx 而不是裸流。

> ⚠️ 排查这类问题时把两件事分开：**字节损坏**是上面这个（看 hex 头），
> **Content-Type 不对**只是浏览器不预览，两者表现都是"打不开"但根因不同。

## 注意

- 沙盒创建后由 E2B 侧超时回收（`Sandbox.set_timeout`，当前 600 秒），
  **Java 端目前没有主动回收机制** → 属重构计划 P1-7，会持续产生按量费用。
- OSS 路径前缀已按用户隔离为 `user/{userId}/artifact/{date}/`（P2-10 已完成）；
  `user_id` 拿不到时落到 `user/unknown/`（仍上传成功，但目录能看出是异常数据）。
- `download_file` 是**全量读进内存**的（E2B SDK 的 bytes 模式没有流式返回），
  超大文件受 worker 内存限制；反向链路（写二进制进沙盒）走 `POST /file` 传 `file_url`。
