# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : oss_utils.py
# @Time    : 2026/4/29 20:27
# -*- coding: utf-8 -*-
import oss2
from oss2.credentials import EnvironmentVariableCredentialsProvider

endpoint = "https://oss-cn-guangzhou.aliyuncs.com"
region = "cn-guangzhou"
prefix='test/'

def str_upload_file(file_name:str,content:bytes):
    auth = oss2.ProviderAuthV4(EnvironmentVariableCredentialsProvider())
    bucket_name="nexus-agent-file"
    bucket = oss2.Bucket(auth, endpoint, bucket_name, region=region)
    object_name=prefix+file_name
    result = bucket.put_object(object_name, content)
    print(result)
    url = f"https://{bucket_name}.{endpoint.replace('https://', '')}/{object_name}"

    return url
