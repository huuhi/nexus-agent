# @Version : 1.0
# @Auothor : 胡志坚
# @File    : oss_utils.py
# @Time    : 2026/4/29 20:27
# -*- coding: utf-8 -*-
import datetime
import mimetypes
import posixpath
from urllib.parse import quote

import oss2
from oss2.credentials import EnvironmentVariableCredentialsProvider

endpoint = "https://oss-cn-guangzhou.aliyuncs.com"
region = "cn-guangzhou"
bucket_name = "nexus-agent-file"

# mimetypes 的全局表在部分精简镜像里不全，这里补齐 AI 产物最常见的几个。
# 补的不是"能不能下载"，而是"浏览器/Office 认不认得这是什么东西"。
_EXTRA_MIME_TYPES = {
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".doc": "application/msword",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".xls": "application/vnd.ms-excel",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".ppt": "application/vnd.ms-powerpoint",
    ".md": "text/markdown; charset=utf-8",
    ".csv": "text/csv; charset=utf-8",
    ".json": "application/json",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".pdf": "application/pdf",
    ".zip": "application/zip",
    ".txt": "text/plain; charset=utf-8",
}


def object_prefix(user_id=None):
    """统一的对象前缀：``user/{userId}/artifact/{date}/``（P2-10）。

    原来是写死的 ``prefix = 'test/'`` —— 所有用户、所有文件都堆在同一个目录里，
    既无法按用户区分，也无法按日期清理。

    user_id 拿不到时落到 ``user/unknown/``：仍然上传成功（不让交付失败），
    但目录一眼能看出是异常数据，便于排查。
    """
    day = datetime.date.today().isoformat()
    uid = user_id if user_id not in (None, "", "None") else "unknown"
    return f"user/{uid}/artifact/{day}/"


def guess_content_type(file_name: str) -> str:
    """按扩展名推断 Content-Type，取不到就用 ``application/octet-stream``。

    ⚠️ 为什么必须显式给（2026-10-05）：不给的话 OSS 一律按二进制流返回，
    浏览器只能把它当"下载"。用户点开链接时看到的是「下载 report.docx」而不是文档预览；
    更糟的是某些内网预览/转换服务会先按 ``Content-Type`` 猜格式，
    猜成 ``application/octet-stream`` 就直接拒绝转换，表现为"文件打不开"。

    注意：**Content-Type 错了不会让文件内容损坏**。字节损坏的真凶在
    :func:`app.routers.file.download_file`（曾经用 ``files.read()`` 的文本模式读二进制）。
    两者要分开排查，不要混为一谈。
    """
    ext = posixpath.splitext(file_name or "")[1].lower()
    if not ext:
        return "application/octet-stream"
    if ext in _EXTRA_MIME_TYPES:
        return _EXTRA_MIME_TYPES[ext]
    guessed = mimetypes.guess_type("x" + ext)[0]
    return guessed or "application/octet-stream"


def upload_bytes(file_name: str, content: bytes, user_id=None) -> str:
    """把**原始字节**上传到 OSS，返回可访问的 URL。

    🔴 ``content`` 必须是未经任何文本编解码的字节。曾经这个函数名叫
    ``str_upload_file``，签名却是 ``bytes`` —— 名字把调用方误导成"这里要传字符串"，
    于是上游把 E2B 的 ``files.read()``（默认返回 str）直接接过来，
    二进制在**进 OSS 之前**就已经被 UTF-8 解码损坏（非法字节 → U+FFFD）。
    名字本身不产生 bug，但它是一路误导到根因的帮凶，故改名为 :func:`upload_bytes`。

    参数类型也收紧：``bytes`` / ``bytearray`` / ``memoryview`` 收，
    ``str`` 直接抛错 —— 让"又把文本当二进制传"这种事在第一行就炸掉，
    而不是静默产生一个打不开的文件。
    """
    if not isinstance(content, (bytes, bytearray, memoryview)):
        raise TypeError(
            "upload_bytes 只接受字节流（bytes/bytearray/memoryview），收到 "
            f"{type(content).__name__}。二进制文件请传原始字节；"
            "若确实是文本，请自行 encode('utf-8') 后再传。"
        )
    content = bytes(content)
    auth = oss2.ProviderAuthV4(EnvironmentVariableCredentialsProvider())
    bucket = oss2.Bucket(auth, endpoint, bucket_name, region=region)
    object_name = object_prefix(user_id) + file_name
    content_type = guess_content_type(file_name)
    # put_object 不会因为多传一个 header 就变慢，但能决定浏览器/Office
    # 是"直接预览"还是"当下载"，以及预览服务认不认得这个格式。
    bucket.put_object(
        object_name,
        content,
        headers={"Content-Type": content_type},
    )
    # ⚠️ URL 必须做 percent-encoding：中文/空格文件名直接拼进 URL 会得到非法链接
    # （对象名里的 '/' 要保留，故 safe='/'）。老记录里的 URL 不带编码，但仍然可用，
    # 新老并存不互相影响。
    encoded_name = quote(object_name, safe="/")
    url = f"https://{bucket_name}.{endpoint.replace('https://', '')}/{encoded_name}"

    return url
