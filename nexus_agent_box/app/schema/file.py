# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : file.py
# @Time    : 2026/4/24 17:35
from pydantic import BaseModel


# 创建文件
class CreateFile(BaseModel):
    path:str
    box_id:str
    content:str
# 上传文件
class FileUpload(BaseModel):
    file_url:str
    file_path:str
    box_id:str
# 返回文件url
class FileDownload(BaseModel):
    file_path:str
    box_id:str


