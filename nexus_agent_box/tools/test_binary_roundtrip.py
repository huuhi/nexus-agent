"""产物下载链路的**二进制保真**自测（不依赖外网、不依赖 OSS、不依赖真实沙盒）。

运行：python tools/test_binary_roundtrip.py

⚠️ 这个测试存在的理由（2026-10-05 P0）

用户交付出去的 PNG / docx / xlsx **全部打不开**，报的是"文件已损坏"。
根因在 :func:`app.routers.file.download_file`：

    content = sbx.files.read(file_path)      # ← E2B SDK 的 format 默认是 "text"
    byte_array = content.encode()

E2B SDK 的 ``Filesystem.read`` 默认 ``format="text"``，实现是 ``return r.text`` ——
httpx 会按 charset 解码，**非法字节一律替换成 U+FFFD**。
二进制在进 OSS 之前就已经被文本化了：

    原始 PNG 头：  89 50 4E 47 0D 0A 1A 0A
    损坏后：        EF BF BD 50 4E 47 0D 0A      （EF BF BD = U+FFFD 的 UTF-8 编码）

后果有两个，缺一不可：
  ① 首字节不再是魔数 → 任何按魔数/格式识别的工具都拒绝打开；
  ② 每个非法字节 1 字节变 3 字节 → **体积凭空膨胀**（实测 94367 → 171196），
     这正是当时"上报 171196、实际 94367"的原因。

docx / xlsx / zip 同理 —— 它们都是 zip 容器，任何字节被替换都会让解包直接失败。

🧪 **本测试必须自带元测试**（第 3 组）

这是本仓库的硬规矩：模拟外部语义的测试，必须先证明"这个模拟确实会出错"，
否则一个写错的模拟会全绿放行，等于什么都没测。2026-10-05 在 Java 侧就踩过：
用 ``BigDecimal.longValueExact`` 模拟 ``JSON.parse``，那是精确算术，
裸数字走它也"无损"，测试全绿而 bug 还在。

所以这里第 3 组先断言「**用旧写法（text 模式）确实会损坏 PNG**」，
只有这条真的红了，才说明第 1 组的修复有效 —— 而不是因为模拟本身失效了。
"""

import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

passed = failed = 0


def ok(label):
    global passed
    passed += 1
    print(f"  \u2713 {label}")


def bad(label, detail):
    global failed
    failed += 1
    print(f"  \u2717 {label} —— {detail}")


def check(label, fn):
    try:
        fn()
        ok(label)
    except AssertionError as e:
        bad(label, str(e) or "断言失败")
    except Exception as e:  # noqa: BLE001
        bad(label, f"{type(e).__name__}: {e}")


# ----------------------------------------------------------------------
# 桩：模拟 E2B SDK 的 Filesystem
# ----------------------------------------------------------------------

PNG_MAGIC = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
# 用户报告里的真实文件：94367 字节的 matplotlib PNG。
# 这里用「带 0x89 头 + 一堆非法字节的二进制」构造等价物，不依赖 matplotlib。
BINARY_PAYLOAD = PNG_MAGIC + bytes(range(256)) * 8 + bytes([0xFF, 0xFE, 0x80, 0x00])


class FakeFiles:
    """只实现 read/write 的最小桩，行为对齐 E2B SDK 的真实语义。

    真实源码（e2b/sandbox_sync/filesystem/filesystem.py）：

        def read(self, path, format="text", ...):
            ...
            if format == "text":
                return r.text          # ← httpx 按 charset 解码，非法字节 → U+FFFD
            elif format == "bytes":
                return bytearray(r.content)

    ``r.text`` 的替换行为在 httpx 里由 ``r.encoding`` 决定；charset 猜不中时兜底
    ``utf-8`` 且 ``errors="replace"``。这里用 ``errors="replace"`` 复刻同一语义。
    """

    def __init__(self, content: bytes):
        self._content = content

    def read(self, path, format="text", **kwargs):  # noqa: A002 - 对齐 SDK 参数名
        if format == "bytes":
            # SDK 返回的是 bytearray，不是 bytes —— 调用方必须自己收口
            return bytearray(self._content)
        # 默认路径：文本解码，非 UTF-8 字节被替换成 U+FFFD
        return self._content.decode("utf-8", errors="replace")

    def write(self, path, data):
        self._content = bytes(data)
        return None


class FakeSandbox:
    def __init__(self, content: bytes):
        self.files = FakeFiles(content)


def patch_sandbox(module, content: bytes):
    """把 download_file 依赖的 Sandbox 换成桩，并拦截 upload_bytes 记录上传内容。

    upload_bytes 真调会连 OSS（需要凭据 + 出网），所以换成只记录的假实现。

    ⚠️ 业务代码调的是 ``Sandbox.connect(box_id)``（**类方法**，不是实例方法），
    所以桩必须是「带 connect 方法的对象」，直接把 module.Sandbox 换成一个函数会报
    ``'function' object has no attribute 'connect'``。
    """
    uploaded = {}

    def fake_upload_bytes(file_name, content, user_id=None):
        uploaded["name"] = file_name
        uploaded["content"] = content
        return "https://example.invalid/fake"

    class SandboxStub:
        @staticmethod
        def connect(box_id):
            return FakeSandbox(content)

    module.Sandbox = SandboxStub
    module.upload_bytes = fake_upload_bytes
    return uploaded


# ----------------------------------------------------------------------
print("【0】前置：桩本身能复现损坏（若这组不成立，后面全都不可信）")
# ----------------------------------------------------------------------
_probe = FakeFiles(BINARY_PAYLOAD).read("/tmp/collatz.png")   # 默认 text 模式
if _probe.startswith("\ufffd"):
    ok(f"桩的 text 模式确实损坏首字节（{_probe[:3].encode('unicode_escape').decode()}）")
else:
    bad("桩的 text 模式没有损坏首字节 —— 模拟失效，第 1 组的通过毫无意义", repr(_probe[:3]))

if len(_probe.encode("utf-8")) > len(BINARY_PAYLOAD):
    ok(f"桩的 text 模式确实让体积膨胀（{len(BINARY_PAYLOAD)} → {len(_probe.encode('utf-8'))}）")
else:
    bad("桩的 text 模式没有体积膨胀 —— 模拟失效", f"{len(_probe.encode('utf-8'))} <= {len(BINARY_PAYLOAD)}")

# 真实 PNG 头必须被破坏。这是用户报告里最直接的证据。
if _probe.encode("utf-8")[:3] == b"\xef\xbf\xbd":
    ok("首字节 0x89 被替换成 EF BF BD（= U+FFFD 的 UTF-8 编码）")
else:
    bad("首字节替换形态与报告不符", _probe.encode("utf-8")[:3].hex())


# ----------------------------------------------------------------------
print("\n【1】download_file 修复后：二进制必须逐字节保真")
# ----------------------------------------------------------------------
from app.routers import file as file_mod  # noqa: E402

uploaded = patch_sandbox(file_mod, BINARY_PAYLOAD)
result = file_mod.download_file("box-x", "/tmp/collatz.png", "42")


def delivered():
    """取「真正交给 upload_bytes 的那份字节」。

    走函数而不是直接 ``uploaded["content"]``：如果 download_file 在到达上传前就报错
    （比如修复被回退），直接下标取会抛一个毫无信息量的 KeyError，
    让人误以为是测试写错了。真正的 download_file 返回值里已经有 error 字段，
    那个才是该被读的诊断信息。
    """
    if "content" not in uploaded:
        raise AssertionError(
            f"download_file 根本没走到 upload_bytes，return={result}。"
            "若是 'string argument without an encoding'，说明有人把 "
            "files.read(path, format='bytes') 改回了 files.read(path)。"
        )
    return uploaded["content"]


def t1_url():
    assert result.get("url") == "https://example.invalid/fake", f"未返回 url：{result}"


def t2_size():
    # size 必须是「上传给 OSS 的字节数」，与用户下载到的文件大小一致
    assert result.get("size") == len(BINARY_PAYLOAD), \
        f"size={result.get('size')}，期望 {len(BINARY_PAYLOAD)}"


def t3_no_error():
    assert "error" not in result, f"返回了错误：{result.get('error')}"


def t4_magic():
    got = delivered()[:8]
    assert got == PNG_MAGIC, f"PNG 魔数被破坏：{got.hex()}"


def t5_roundtrip():
    got = delivered()
    assert got == BINARY_PAYLOAD, \
        f"内容不一致：上传 {len(got)} 字节，原始 {len(BINARY_PAYLOAD)} 字节"


def t6_type():
    # upload_bytes 明确只收字节流；万一有人又传了 str 进来，第一行就该抛错
    got = delivered()
    assert isinstance(got, bytes), \
        f"上传的不是 bytes 而是 {type(got).__name__}"


def t7_filename():
    assert uploaded["name"] == "collatz.png", f"文件名取错：{uploaded['name']}"


for label, fn in [
    ("返回 url", t1_url),
    ("size 等于原始字节数（不再膨胀）", t2_size),
    ("没有 error 字段", t3_no_error),
    ("PNG 魔数完好", t4_magic),
    ("上传内容与沙盒内逐字节一致", t5_roundtrip),
    ("上传的是 bytes 而非 bytearray/str", t6_type),
    ("文件名从路径中正确取出", t7_filename),
]:
    check(label, fn)


# ----------------------------------------------------------------------
print("\n【2】元测试：旧写法（默认 text 模式）必须被证明是坏的")
#     这一组反过来验证「本测试确实测到了东西」。
#     如果哪天有人把 download_file 改回 files.read(path)，这里会红 —— 那才是回归。
# ----------------------------------------------------------------------
# 直接复刻旧实现：read() 不传 format，再 encode 回去
_stale_bytes = FakeFiles(BINARY_PAYLOAD).read("/tmp/collatz.png").encode()


def t8_stale_corrupts():
    assert _stale_bytes[:8] != PNG_MAGIC, \
        "旧写法竟然没破坏 PNG 魔数 —— 说明它本来就是对的，本测试是无效的"
    assert _stale_bytes[:3] == b"\xef\xbf\xbd", \
        f"旧写法的首字节形态与报告不符：{_stale_bytes[:3].hex()}"


def t9_stale_inflates():
    assert len(_stale_bytes) > len(BINARY_PAYLOAD), \
        "旧写法竟然没让体积膨胀 —— 与报告的 94367 → 171196 不符"


def t10_bytes_mode_untouched():
    # 反向对照：bytes 模式下必须完好，证明修复来自 format 参数而非巧合
    assert FakeFiles(BINARY_PAYLOAD).read("/p", format="bytes") == BINARY_PAYLOAD


for label, fn in [
    ("旧写法确实破坏 PNG 魔数（EF BF BD）", t8_stale_corrupts),
    ("旧写法确实让体积膨胀", t9_stale_inflates),
    ("bytes 模式逐字节无损（反向对照）", t10_bytes_mode_untouched),
]:
    check(label, fn)


# ----------------------------------------------------------------------
print("\n【3】docx 场景：zip 容器必须可解包")
#     docx/xlsx/pptx 都是 zip。任一字节被替换都会让中央目录失效 → Office 报"已损坏"。
# ----------------------------------------------------------------------
docx_buf = io.BytesIO()
with zipfile.ZipFile(docx_buf, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types/>')
    z.writestr("word/document.xml", "<w:document/>" + "中文正文" * 50)
docx_bytes = docx_buf.getvalue()

docx_uploaded = patch_sandbox(file_mod, docx_bytes)
docx_result = file_mod.download_file("box-x", "/tmp/thesis.docx", "42")
# 必须用「真正交给 upload_bytes 的那份字节」做解包验证 —— 那才是用户最终下载到的东西。
if "content" in docx_uploaded:
    docx_delivered = docx_uploaded["content"]
else:
    docx_delivered = b""   # 让下面的用例报出清晰的诊断，而不是 KeyError


def t11_docx_ok():
    assert "error" not in docx_result, f"返回了错误：{docx_result.get('error')}"


def t12_docx_openable():
    assert docx_delivered, f"没有内容被上传，return={docx_result}"
    with zipfile.ZipFile(io.BytesIO(docx_delivered)) as z:
        assert "word/document.xml" in z.namelist(), \
            f"docx 解包失败（这就是用户看到的『已损坏』）：{z.namelist()}"
        # testzip 会逐条校验 CRC
        assert z.testzip() is None, "docx 内部 CRC 校验失败"


def t13_docx_size():
    assert docx_result.get("size") == len(docx_bytes), \
        f"docx size={docx_result.get('size')}，期望 {len(docx_bytes)}"


def t14_docx_text_intact():
    # 中文正文也要完好：文本化不只是丢字节，还会把看似合法的多字节序列截断
    with zipfile.ZipFile(io.BytesIO(docx_delivered)) as z:
        content = z.read("word/document.xml").decode("utf-8")
    assert "中文正文" in content, "docx 中文正文损坏"
    assert "\ufffd" not in content, "docx 正文里出现了 U+FFFD —— 说明走过文本解码"


check("docx 下载无错误", t11_docx_ok)
check("docx 作为 zip 可正常解包且 CRC 通过", t12_docx_openable)
check("docx size 与原始一致", t13_docx_size)
check("docx 中文正文完好且无 U+FFFD", t14_docx_text_intact)


# ----------------------------------------------------------------------
print("\n【4】上传接口的文本契约：upload_bytes 必须拒绝 str")
# ----------------------------------------------------------------------
from app.utils.oss_utils import guess_content_type  # noqa: E402

# 只测不触网的纯函数：Content-Type 推断
for name, expected in [
    ("report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ("a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ("x.png", "image/png"),
    ("d.pdf", "application/pdf"),
    ("n.md", "text/markdown; charset=utf-8"),
    ("noext", "application/octet-stream"),
    ("a.unknownext", "application/octet-stream"),
]:
    def t_ct(n=name, e=expected):
        got = guess_content_type(n)
        assert got == e, f"{n} → {got}，期望 {e}"
    check(f"Content-Type 推断 {name}", t_ct)

# 类型收紧：str 必须抛 TypeError（这是防止同类 bug 复发的第一道闸）
def t_str_rejected():
    try:
        from app.utils import oss_utils
        oss_utils.upload_bytes("a.png", "我是一个字符串")
    except TypeError as e:
        return
    except Exception as e:  # noqa: BLE001
        raise AssertionError(f"抛的不是 TypeError 而是 {type(e).__name__}: {e}")
    raise AssertionError("upload_bytes 居然接受了 str —— 类型闸门失效，同类 bug 会复发")


check("upload_bytes 拒收 str（防止再次把文本当二进制传）", t_str_rejected)


print(f"\n通过 {passed} / 失败 {failed}")
sys.exit(1 if failed else 0)
