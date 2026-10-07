# 使用官方 Python 3.12 镜像
FROM python:3.12-slim

# 设置工作目录
WORKDIR /app

# ---------------------------------------------------------------------------
# 依赖源（2026-10-07 加）
# ---------------------------------------------------------------------------
# 默认走**清华镜像**：国内服务器直连 deb.debian.org / pypi.org 极慢 ——
# 实测 `apt-get update` 拉 9.6MB 的包索引要 11 分钟还没完，构建直接卡死。
# 海外服务器想用官方源，构建时覆盖即可：
#   docker compose build --build-arg UV_INDEX_URL=https://pypi.org/simple
ARG UV_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ENV UV_DEFAULT_INDEX=${UV_INDEX_URL}
# ⚠️ 实测国内服务器下载速度约 100KB/s（cryptography 4.5MB + e2b SDK 十几个包）。
# uv 默认的 HTTP 超时较短，慢速连接会被判失败后重试 —— 重试一次就白等一遍，
# 表现为「卡住不动」。放宽到 300s，让它老实等下载完。
# 同时提高并发数：带宽小的时候，并发比单线程更能吃满带宽。
ENV UV_HTTP_TIMEOUT=300 \
    UV_CONCURRENT_DOWNLOADS=16

# 安装 uv（快速包管理器）
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

# ⚠️ 这里曾有一层 `apt-get update && apt-get install curl`，
#    **只为 docker-compose 的 healthcheck 装 curl**（slim 镜像默认不带）。
#    代价是每次构建都要拉几 MB 的 apt 索引 —— 国内服务器实测 688s 还没拉完，
#    而它唯一的用途只是探一下 /docs，完全不值。
#    现在改用镜像自带的 python 做同样的探活（见 compose 的 healthcheck），
#    这一层整个删掉：构建更快、镜像更小、也不再依赖 apt 源。
#
#    另外别用业务接口做健康检查：E2B Key 配错时接口拿不到 200，
#    容器会被判成不健康反复重启，反而把真正的原因盖住 —— 探 /docs 就好。

# 先复制依赖文件（利用 Docker 缓存）
COPY pyproject.toml uv.lock ./

# 安装依赖（用 uv sync 会同步 lock 文件的确切版本）
# ⚠️ 这一步在国内服务器上是最慢的一环：e2b SDK 依赖树里有 cryptography(4.5MB)、
#    pydantic-core(2MB) 等十几个包，100KB/s 的带宽下要几分钟。
#    ⚠️ 看到 "Downloading xxx" 是在**下载 wheel**，不是在编译源码 ——
#    编译 cryptography 会显示 Building 且需要 Rust 工具链，那是另一种卡法。
#    如果超过 10 分钟仍未结束，多半是镜像站同步不全（拿不到预编译 wheel
#    就退回源码构建），换官方源重建：
#      docker compose -f docker-compose.yml build \
#        --build-arg UV_INDEX_URL=https://pypi.org/simple
RUN uv sync --frozen --no-dev --no-install-project

# 复制项目代码
COPY . .

# 确保在容器内运行，.env 会被 uvicorn 加载
EXPOSE 8000

# 启动命令：监听 0.0.0.0（容器内必须绑定所有接口）
CMD ["uv", "run", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]