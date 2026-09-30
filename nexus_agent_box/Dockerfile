# 使用官方 Python 3.12 镜像
FROM python:3.12-slim

# 设置工作目录
WORKDIR /app

# 安装 uv（快速包管理器）
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

# curl 只服务于 docker-compose.yml 里的 healthcheck（slim 镜像默认不带）。
# 别用业务接口做健康检查：E2B Key 配错时接口拿不到 200，
# 容器会被判成不健康反复重启，反而把真正的原因盖住 —— 探 /docs 就好。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 先复制依赖文件（利用 Docker 缓存）
COPY pyproject.toml uv.lock ./

# 安装依赖（用 uv sync 会同步 lock 文件的确切版本）
RUN uv sync --frozen --no-dev --no-install-project

# 复制项目代码
COPY . .

# 确保在容器内运行，.env 会被 uvicorn 加载
EXPOSE 8000

# 启动命令：监听 0.0.0.0（容器内必须绑定所有接口）
CMD ["uv", "run", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]