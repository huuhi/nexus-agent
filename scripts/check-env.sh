#!/usr/bin/env bash
# ============================================================================
#  check-env.sh —— 部署前自查 .env（Java 应用）与 nexus_agent_box/.env（沙盒服务）
# ----------------------------------------------------------------------------
#  用法（在服务器上，项目根目录执行）：
#      bash scripts/check-env.sh
#      bash scripts/check-env.sh /path/to/.env /path/to/box.env [path/to/application-prod.yml]
#
#  它会告诉你：
#    · 必需项缺了哪些（缺了应用起不来 —— yml 里的 ${XXX} 没有默认值，
#      Spring 会抛 Could not resolve placeholder）
#    · 哪些还留着 <<...>> 占位符没替换
#    · 写法问题（"KEY = value" 带空格、写了 export 前缀、CRLF 行尾）
#
#  「哪些算必需」是**从 application-prod.yml 里现抽 ${} 占位符**得来的
#  （外加代码里直接读的 API_KEY_SECRET），所以 yml 改了变量名不会漏查。
#
#  退出码：0 = 必需项齐全；1 = 有必需项缺失
# ============================================================================

set -uo pipefail

APP_ENV="${1:-.env}"
BOX_ENV="${2:-nexus_agent_box/.env}"
PROD_YML="${3:-nexus-agent-web/src/main/resources/application-prod.yml}"

if [ -t 1 ]; then
    RED=$'\033[31m'; YEL=$'\033[33m'; GRN=$'\033[32m'; DIM=$'\033[2m'; RST=$'\033[0m'
else
    RED=''; YEL=''; GRN=''; DIM=''; RST=''
fi

fail=0

# 取某个 key 的值：只认严格的 KEY= 写法，去掉行尾注释与两侧引号
get_value() {
    local file="$1" key="$2"
    [ -f "$file" ] || return 0
    grep -E "^${key}=" "$file" | head -1 \
        | sed -E "s/^${key}=//" \
        | sed -E 's/[[:space:]]+#.*$//' \
        | sed -E 's/^"(.*)"$/\1/' \
        | sed -E "s/^'(.*)'$/\1/"
}

# 打码：不要把真实密钥整段打印到终端/日志里
mask() {
    local v="$1" n=${#1}
    if [ "$n" -le 8 ]; then printf '****'; else printf '%s****(%s 字符)' "${v:0:4}" "$n"; fi
}

# check <文件> <key> <required|recommended>
check() {
    local file="$1" key="$2" level="$3" val
    if [ ! -f "$file" ]; then
        printf "${RED}[必需]${RST} %-26s 文件不存在：%s\n" "$key" "$file"
        fail=1
        return
    fi
    val="$(get_value "$file" "$key")"
    if [ -z "$val" ]; then
        if [ "$level" = required ]; then
            printf "${RED}[必需]${RST} %-26s 没填（或整行被注释掉了）\n" "$key"
            fail=1
        else
            printf "${YEL}[建议]${RST} %-26s 没填\n" "$key"
        fi
    elif [[ "$val" == "<<"* ]]; then
        if [ "$level" = required ]; then
            printf "${RED}[必需]${RST} %-26s 还是占位符 %s\n" "$key" "$val"
            fail=1
        else
            printf "${YEL}[建议]${RST} %-26s 还是占位符 %s\n" "$key" "$val"
        fi
    else
        # 不是密钥的项（主机地址 / URL）直接显示，方便你确认填对了没
        case "$key" in
            SERVICE_IP|BASE_URL) printf "${GRN}[ok]${RST}   %-26s %s\n" "$key" "$val" ;;
            *)                   printf "${GRN}[ok]${RST}   %-26s %s\n" "$key" "$(mask "$val")" ;;
        esac
    fi
}

# 写法问题（这些不会让启动失败，但会让变量静默失效，非常难查）
check_syntax() {
    local file="$1" label="$2"
    [ -f "$file" ] || return 0
    if grep -qE '^[[:space:]]*export[[:space:]]' "$file"; then
        printf "${YEL}[写法]${RST} %s 里有 export 前缀 —— docker compose 的 env_file 不认，请去掉\n" "$label"
    fi
    if grep -qE '^[A-Za-z_][A-Za-z0-9_]*[[:space:]]+=' "$file"; then
        printf "${YEL}[写法]${RST} %s 里有 \"KEY = value\"（等号前有空格）—— 会被当成带空格的变量名，请改成 KEY=value\n" "$label"
    fi
    # -U/--binary：不让 grep 做 CRLF 转换，否则在部分环境下 CR 会被吃掉、永远检测不到
    if grep -qU $'\r' "$file"; then
        printf '%s[写法]%s %s 是 CRLF 行尾 —— 每个值末尾会多一个回车符\n' "$YEL" "$RST" "$label"
        printf '%s       ->%s 修：sed -i %s %s   （或 dos2unix %s）\n' \
               "$DIM" "$RST" "'s/\r$//'" "$file" "$file"
    fi
}

# 从 application-prod.yml 里抽出所有 ${XXX} 占位符 —— 这些**缺一个应用就起不来**。
# 为什么要动态抽：写死清单会和 yml 漂移（改个变量名就漏查），而这里正是唯一的事实来源。
#（yml 里写成 ${XXX:默认值} 的**不算**必需，有默认值兜底。）
extract_required_from_yml() {
    sed -n 's/.*\${\([A-Za-z_][A-Za-z0-9_]*\)}.*/\1/p' "$PROD_YML" | sort -u
}

echo "=============================================================="
echo " 环境变量自查"
echo "=============================================================="
echo

# 必备清单 = yml 占位符 + 代码里直接 System.getenv 的那些（yml 里看不到）
reqs=(API_KEY_SECRET)
if [ -f "$PROD_YML" ]; then
    while IFS= read -r k; do [ -n "$k" ] && reqs+=("$k"); done < <(extract_required_from_yml)
    src="${PROD_YML} 的 \${} 占位符 + 代码里的 API_KEY_SECRET"
else
    reqs+=(SERVICE_IP DATABASE DB_USERNAME REDIS_PWD DEEPSEEK MOONSHOT ALI_AI_KEY MAIL_USERNAME MAIL_PASSWORD)
    src="内置清单（没找到 ${PROD_YML}，路径不对？）"
fi

if [ ! -f "$APP_ENV" ]; then
    printf "${RED}%s 不存在${RST} —— 先 cp .env.example %s 再填真实值\n" "$APP_ENV" "$APP_ENV"
    exit 1
fi

echo "── 应用（${APP_ENV}）── 必需项（取自 ${src}）──"
for k in "${reqs[@]}"; do check "$APP_ENV" "$k" required; done
echo
echo "── 应用（${APP_ENV}）── 建议项 ──"
for k in JWT_SECRET OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_SECRET; do check "$APP_ENV" "$k" recommended; done
echo
echo "── 沙盒服务（${BOX_ENV}）── 必需项 ──"
for k in E2B_API_KEY ALIBABA_CLOUD_ACCESS_KEY_ID ALIBABA_CLOUD_ACCESS_KEY_SECRET; do
    check "$BOX_ENV" "$k" required
done
echo

check_syntax "$APP_ENV" "$(basename "$APP_ENV")"
check_syntax "$BOX_ENV" "$(basename "$BOX_ENV")"

echo
echo "── 两个 OSS 的变量名不一样，别填反 ──"
printf "${DIM}  应用(Java, AliOssUtil)  : OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET${RST}\n"
printf "${DIM}  沙盒(Python, oss2)      : ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET${RST}\n"
echo

echo "── 别忘了 ──"
echo "  1. 数据库必须先跑过 docs/sql/007（缺 token_period 列 → 登录直接 500）"
echo "  2. 不打容器直接跑 jar 时，Spring Boot 不会自动读 .env："
echo "       set -a; . ./.env; set +a; exec java \$JAVA_OPTS -jar app.jar"
echo

if [ "$fail" -eq 0 ]; then
    printf "${GRN}必需项齐全，可以启动。${RST}\n"
    exit 0
fi
printf "${RED}有必需项缺失 —— 应用会启动失败。${RST}\n"
printf "${DIM}yml 里的 \${XXX} 没有默认值：解析不到会让 Spring 抛 Could not resolve placeholder；${RST}\n"
printf "${DIM}API_KEY_SECRET 等代码里读的则会被引擎自检拦下（fail-fast）。${RST}\n"
exit 1
