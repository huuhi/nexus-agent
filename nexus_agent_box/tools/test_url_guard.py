"""UrlGuard / download_bytes 的本地自测（不依赖外网）。

起一个本地 HTTP server，然后逐个断言各种攻击向量都被拦住。
运行：python tools/test_url_guard.py
"""
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# 关键：必须在 import 之前设成"允许内网"，否则本机 127.0.0.1 一开始就被拒，
# 测不到「协议/重定向/体积」这几条了。内网拦截另有单独一组用例（见文末）。
os.environ["BOX_ALLOW_PRIVATE"] = "1"
os.environ["BOX_MAX_DOWNLOAD_BYTES"] = "1024"

from app.utils.url_guard import (  # noqa: E402
    UrlNotAllowed,
    download_bytes,
    validate_url,
)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/redirect":
            # 302 到"内网"，用来验证"禁重定向"
            self.send_response(302)
            self.send_header("Location", "http://169.254.169.254/latest/meta-data/")
            self.end_headers()
        else:
            self.send_response(200)
            self.send_header("Content-Length", "5")
            self.end_headers()
            self.wfile.write(b"hello")


srv = HTTPServer(("127.0.0.1", 0), H)
port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{port}"


def start_raw_server(mode):
    """起一个裸 socket 服务器，用 ``mode`` 决定它怎么"使坏"。

    为什么不用 ``http.server``：它按声明长度截断响应，根本构造不出
    "实际发得比声明多" 或 "根本不声明长度" 的情况。

    两种模式：
    * ``honest``  —— Content-Length: 5000，实发 5000（应被上限拦住）
    * ``chunked`` —— Transfer-Encoding: chunked，**不声明总长度**，
      实发 5000。这才是真正能把内存吃光的路径：客户端无法预知总量，
      只能一边读一边判。
    """
    import socket as _s

    ls = _s.socket(_s.AF_INET, _s.SOCK_STREAM)
    ls.setsockopt(_s.SOL_SOCKET, _s.SO_REUSEADDR, 1)
    ls.bind(("127.0.0.1", 0))
    ls.listen(8)
    port = ls.getsockname()[1]

    def serve():
        while True:
            try:
                conn, _ = ls.accept()
            except OSError:
                return
            try:
                conn.recv(4096)
                if mode == "chunked":
                    conn.sendall(b"HTTP/1.1 200 OK\r\n"
                                 b"Transfer-Encoding: chunked\r\n\r\n")
                    conn.sendall(b"%x\r\n" % 5000 + b"x" * 5000 + b"\r\n")
                    conn.sendall(b"0\r\n\r\n")
                else:
                    conn.sendall(b"HTTP/1.1 200 OK\r\n"
                                 b"Content-Length: 5000\r\n\r\n" + b"x" * 5000)
            except OSError:
                pass
            finally:
                conn.close()

    threading.Thread(target=serve, daemon=True).start()
    return port


HONEST = f"http://127.0.0.1:{start_raw_server('honest')}"
CHUNKED = f"http://127.0.0.1:{start_raw_server('chunked')}"

passed = failed = 0


def expect_block(label, fn):
    global passed, failed
    try:
        fn()
        print(f"  ✗ {label} —— 本该被拒，却成功了")
        failed += 1
    except UrlNotAllowed as e:
        print(f"  ✓ {label} —— {e}")
        passed += 1
    except Exception as e:  # noqa: BLE001
        print(f"  ✗ {label} —— 报的不是 UrlNotAllowed，而是 {type(e).__name__}: {e}")
        failed += 1


def expect_ok(label, fn):
    global passed, failed
    try:
        fn()
        print(f"  ✓ {label}")
        passed += 1
    except Exception as e:  # noqa: BLE001
        print(f"  ✗ {label} —— {type(e).__name__}: {e}")
        failed += 1


print("【1】协议白名单")
expect_block("file:///etc/passwd", lambda: validate_url("file:///etc/passwd"))
expect_block("gopher://127.0.0.1:6379/_INFO", lambda: validate_url("gopher://127.0.0.1:6379/_INFO"))
expect_block("ftp://example.com/a", lambda: validate_url("ftp://example.com/a"))
expect_block("空 URL", lambda: validate_url(""))
expect_block("带控制字符", lambda: validate_url("http://a.com/\r\nHost: evil"))
expect_block("没有 host", lambda: validate_url("http:///etc/passwd"))

print("\n【2】重定向绕过")
expect_block("302 → 云元数据", lambda: download_bytes(f"{BASE}/redirect"))

print("\n【3】体积上限（上限 1024）")
# Content-Length 谎报这条**测不了**：urllib 的 read() 尊重 Content-Length，
# 声明 10 就只读 10 字节，谎报者反而下不到内存。真正无界的路径是 chunked
# （不声明总长，只能边读边判）—— 下面第二条才是关键用例。
expect_block("Content-Length 诚实报 5000", lambda: download_bytes(f"{HONEST}/f"))
expect_block("chunked 无总长度、实发 5000", lambda: download_bytes(f"{CHUNKED}/f"))

print("\n【4】正常下载不能被误伤")
expect_ok("小文件", lambda: download_bytes(f"{BASE}/ok"))
expect_ok("合法 http URL 通过 validate_url",
          lambda: validate_url("https://oss-cn-guangzhou.aliyuncs.com/a.pdf"))

srv.shutdown()

# ------------------------------------------------------------------
# 【5】内网拦截必须在 BOX_ALLOW_PRIVATE=0（默认）下验证。
# 上面几组为了能连本机 server 把开关打开了，所以这一组用子进程单独跑 ——
# 同一进程里改 os.environ 没用，模块加载时就把值读进去了。
# ------------------------------------------------------------------
print("\n【5】内网/保留地址拦截（子进程，BOX_ALLOW_PRIVATE=0）")
import subprocess  # noqa: E402

SNIPPET = """
import sys
sys.path.insert(0, %r)
from app.utils.url_guard import validate_url, UrlNotAllowed
bad = ["http://127.0.0.1/x", "http://169.254.169.254/latest/meta-data/",
       "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.1/",
       "http://[::1]/", "http://0.0.0.0/", "http://100.64.0.1/",
       "http://[fc00::1]/", "http://localhost:8080/"]
fail = 0
for u in bad:
    try:
        validate_url(u)
        print("LEAK", u); fail += 1
    except UrlNotAllowed:
        pass
sys.exit(1 if fail else 0)
""" % os.path.join(os.path.dirname(__file__), "..")

env = dict(os.environ, BOX_ALLOW_PRIVATE="0")
proc = subprocess.run([sys.executable, "-c", SNIPPET], env=env,
                      capture_output=True, text=True)
if proc.returncode == 0:
    print("  ✓ 10 个内网/保留地址目标全部被拒")
    passed += 1
else:
    print(f"  ✗ 有内网地址没拦住，stdout={proc.stdout.strip()} stderr={proc.stderr.strip()}")
    failed += 1

print(f"\n通过 {passed} / 失败 {failed}")
sys.exit(1 if failed else 0)
