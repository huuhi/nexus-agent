# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : oss_utils.py
# @Time    : 2026/4/29 20:27
# -*- coding: utf-8 -*-
import datetime
from urllib.parse import quote

import oss2
from oss2.credentials import EnvironmentVariableCredentialsProvider

endpoint = "https://oss-cn-guangzhou.aliyuncs.com"
region = "cn-guangzhou"
bucket_name = "nexus-agent-file"


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


def str_upload_file(file_name: str, content: bytes, user_id=None):
    auth = oss2.ProviderAuthV4(EnvironmentVariableCredentialsProvider())
    bucket = oss2.Bucket(auth, endpoint, bucket_name, region=region)
    object_name = object_prefix(user_id) + file_name
    result = bucket.put_object(object_name, content)
    print(result)
    # ⚠️ URL 必须做 percent-encoding：中文/空格文件名直接拼进 URL 会得到非法链接
    # （对象名里的 '/' 要保留，故 safe='/'）。老记录里的 URL 不带编码，但仍然可用，
    # 新老并存不互相影响。
    encoded_name = quote(object_name, safe="/")
    url = f"https://{bucket_name}.{endpoint.replace('https://', '')}/{encoded_name}"

    return url
