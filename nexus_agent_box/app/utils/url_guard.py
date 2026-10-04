# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : url_guard.py
# @Time    : 2026/10/4
# -*- coding: utf-8 -*-
"""出网 URL 校验（SSRF 防护）。

⚠️ **为什么必须有这个模块（2026-10-04 安全修复 P0）**

``POST /file`` 的 ``file_url`` 来自 LLM 工具调用（``BoxTool.upload_file``），
而 LLM 的这个参数可能直接抄自用户消息。也就是说**用户一句话就能让沙盒服务
替他去访问任意地址**。修复前这里是裸的 ``urlopen(...)``，后果：

* ``file://`` → 直接读本机文件（``/etc/passwd``、``.env`` 里带 Key 的配置），
  读到就写进沙盒 —— LLM 随后能把内容原样吐给用户，等于**任意文件读取**；
* ``http://127.0.0.1:6379/`` → 打进内网其他服务（Redis 未授权、数据库、
  云厂商元数据 ``169.254.169.254``）；
* 无 **timeout** → 指向一个不响应的地址能把 worker 线程永久占住（DoS）；
* 无 **大小上限** → ``response.read()`` 一次性进内存，一个几 GB 的文件直接 OOM；
* **默认跟重定向** → 上面所有检查都会被「先给个 302 到内网」轻松绕过。

这与 Java 侧 ``UrlGuard``（MCP 出网）是**同一类问题的两处出口**，
两边判定规则刻意保持一致，避免出现「Java 拦了 Python 放行」的漏洞。
"""

import ipaddress
import os
import socket
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

# 允许下载的最大体积（字节）。默认 50MB：够放绝大多数数据集/文档，
# 又不至于把 worker 内存打爆。0 或负数 = 不限制（不推荐）。
MAX_DOWNLOAD_BYTES = int(os.getenv("BOX_MAX_DOWNLOAD_BYTES", str(50 * 1024 * 1024)))

# 单次下载超时（秒）。拆成 connect/read 更容易定位问题，这里用同一个值。
DOWNLOAD_TIMEOUT = float(os.getenv("BOX_DOWNLOAD_TIMEOUT", "30"))

# 是否允许访问内网地址。仅用于本地联调（把沙盒与 OSS 跑在同一台机器的内网时），
# 生产环境务必保持 "0"/"false"。
ALLOW_PRIVATE = os.getenv("BOX_ALLOW_PRIVATE", "0").strip().lower() in ("1", "true", "yes", "on")

ALLOWED_SCHEMES = ("http", "https")


class UrlNotAllowed(Exception):
    """URL 不合法/指向内网。调用方应把它当成「用户输入有问题」而不是服务端故障。"""


def _ip_is_forbidden(ip: "ipaddress._BaseAddress") -> bool:
    """判断单个 IP 是否属于「不该让服务端主动访问」的目标。

    除了常规的私网/回环/链路本地，还显式排掉 CGN（100.64.0.0/10）与
    未指定地址 —— 它们不在 ``is_private`` 的判定里（Python 认为 CGN 是"公网"），
    但在云环境里 CGN 段常被用于内部服务，等于留了个后门。
    """
    return bool(
        ip.is_loopback            # 127.0.0.0/8, ::1
        or ip.is_private           # 10/8, 172.16/12, 192.168/16, fc00::/7
        or ip.is_link_local        # 169.254/16（含云元数据）, fe80::/10
        or ip.is_unspecified       # 0.0.0.0, ::
        or ip.is_multicast
        or ip.is_reserved
        or ip in ipaddress.ip_network("100.64.0.0/10")   # CGN
    )


def validate_url(raw: str) -> str:
    """校验出网 URL，通过则原样返回，失败抛 :class:`UrlNotAllowed`。

    校验顺序刻意是「便宜的先做」：空值 → 协议 → host → 解析 IP。
    DNS 解析是唯一有网络开销的一步，放在最后。
    """
    if not raw or not raw.strip():
        raise UrlNotAllowed("file_url 为空")

    # 控制字符：能污染日志，也能被某些下游 HTTP 库当成请求头分隔符
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in raw):
        raise UrlNotAllowed("file_url 含有控制字符")

    try:
        parsed = urlparse(raw)
    except ValueError as e:
        raise UrlNotAllowed(f"file_url 解析失败: {e}") from e

    # 只放 http/https：挡掉 file:// gopher:// ftp:// dict:// 这些能被 urlopen 处理的协议
    if parsed.scheme.lower() not in ALLOWED_SCHEMES:
        raise UrlNotAllowed(
            f"只允许 http/https，实际是 '{parsed.scheme}'"
        )

    if not parsed.hostname:
        raise UrlNotAllowed("file_url 缺少 host")

    if ALLOW_PRIVATE:
        return raw

    # ⚠️ 解析 host 后逐个检查返回的 IP —— 挡「域名指向内网」这一类
    # （直接看字符串是 private 的写法没用，攻击者有域名）
    try:
        infos = socket.getaddrinfo(parsed.hostname, None)
    except socket.gaierror as e:
        raise UrlNotAllowed(f"域名解析失败: {parsed.hostname}") from e

    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if _ip_is_forbidden(ip):
            raise UrlNotAllowed(
                f"禁止访问内网/保留地址: {parsed.hostname} -> {ip}"
            )
    return raw


class _NoRedirect(HTTPRedirectHandler):
    """禁止自动跟随 3xx。

    ⚠️ 不禁重定向，前面所有校验都能被一句「302 到 127.0.0.1」轻松绕过。
    改为主动报错，让调用方（LLM）明确知道下载失败，而不是拿到内网响应。
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise UrlNotAllowed(
            f"下载地址返回了重定向（{code}），为避免绕过内网校验已拒绝：{newurl}"
        )


# 只装 _NoRedirect，不装默认的 HTTPRedirectHandler —— 刻意覆盖掉自动跟随
_opener = build_opener(_NoRedirect)


def download_bytes(url: str) -> bytes:
    """带全套防护地下载一个文件。

    四道防线缺一不可：协议白名单 + 内网判定（:func:`validate_url`）、
    禁重定向、超时、体积上限。
    """
    validate_url(url)
    req = Request(url, headers={"User-Agent": "nexus-agent-box/1.0"})
    with _opener.open(req, timeout=DOWNLOAD_TIMEOUT) as response:
        # Content-Length 只是提示，不能全信 —— 真正兜底的是下面按块读时的累计上限
        declared = response.headers.get("Content-Length")
        if (declared is not None and declared.isdigit()
                and MAX_DOWNLOAD_BYTES > 0 and int(declared) > MAX_DOWNLOAD_BYTES):
            raise UrlNotAllowed(
                f"文件过大: {declared} 字节 > 上限 {MAX_DOWNLOAD_BYTES} 字节"
            )

        chunks = []
        total = 0
        while True:
            chunk = response.read(64 * 1024)
            if not chunk:
                break
            total += len(chunk)
            # 按实际读到的字节判，而不是只信 Content-Length（可以伪造）
            if MAX_DOWNLOAD_BYTES > 0 and total > MAX_DOWNLOAD_BYTES:
                raise UrlNotAllowed(
                    f"文件过大: 已读 {total} 字节 > 上限 {MAX_DOWNLOAD_BYTES} 字节"
                )
            chunks.append(chunk)
        return b"".join(chunks)
