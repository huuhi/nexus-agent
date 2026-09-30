# ============================================================================
#  nexus-agent 应用镜像（P3-3 部署形态固化）
# ============================================================================
#  构建（在仓库根目录执行）：
#     docker build -t nexus-agent:local .
#  运行：
#     docker run --rm -p 8080:8080 --env-file .env nexus-agent:local
#
#  ⚠️ 未做依赖分层缓存：改一行代码也会重新下载全量依赖。
#     这样做的理由是「先保证能构建成功」——Maven 的 dependency:go-offline 在
#     多模块项目上经常解析不全（插件也依赖），失败率高于收益；
#     真要加速再加「先 COPY 5 个 pom.xml → 再 COPY 源码」两层。
#
#  ⚠️ 本文件在本机没有 Docker 的环境下**未经构建验证**（见 README「部署」章节）。

# ---------------------------------------------------------------------------
# 阶段 1：构建
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY . .

# -pl nexus-agent-web -am：只打 web，同时把依赖的 4 个模块一起构建
# （不加 -am 会去仓库找兄弟模块的 jar，本地没装过就失败）
RUN mvn -B -pl nexus-agent-web -am -DskipTests package

# ---------------------------------------------------------------------------
# 阶段 2：运行
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# curl 只服务于下面的 HEALTHCHECK（jre 镜像默认不带）
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 只把可执行 jar 从构建阶段带出来，源码与 ~/.m2 都不进运行镜像
COPY --from=build /build/nexus-agent-web/target/nexus-agent-web-*.jar app.jar

# 非 root 运行：镜像里跑的进程不该是 root
RUN useradd -r -s /bin/false appuser && chown -R appuser /app
USER appuser

# 容器内按 cgroup 限额的 75% 设堆上限，避免被 OOM Killer 干掉
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

# 健康检查打到免鉴权的 /actuator/health（见 WebInterceptorConfig 白名单）。
# start-period 给足：Spring Boot 启动 + 自检（StartupConfigValidator）可能要几十秒。
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# 用 sh -c + exec：让 java 成为 PID 1，才能收到 SIGTERM 并走优雅停机
# （写成 CMD java -jar 时 PID 1 是 shell，信号不一定转发给 java）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
