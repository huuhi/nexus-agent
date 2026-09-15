# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : file.py
# @Time    : 2026/4/24 17:33
from pathlib import Path
from urllib.request import urlopen

from app.schema.file import FileUpload, FileDownload, CreateFile
from app.utils.oss_utils import str_upload_file
from dotenv import load_dotenv
from e2b_code_interpreter import Sandbox
from fastapi import APIRouter

router=APIRouter(prefix="/file")
load_dotenv()

# 判断文件/路径是否存在
@router.get("/exists")
def is_exists(box_id:str,file_path:str):
    box=Sandbox.connect(box_id)
    return {
        'exist':box.files.exists(path=file_path)
    }
# 查看路径文件
@router.get("/list")
def list_file(box_id:str,dir_path:str):
    try:
        box= Sandbox.connect(box_id)
        # 判断文件是否存在
        result= box.files.list(dir_path)
        return result
    except Exception as e:
        return {
            "error":str(e)
        }


# 创建文件并且写入内容。
@router.post("/create")
def create_file(file:CreateFile):
    try:
        box= Sandbox.connect(file.box_id)
        content=file.content
        print(type(content))
        print(content)
        byte_array=content.encode()
        box.files.write(file.path,byte_array)
        return {
            'path':file.path
        }
    except Exception as e:
        return {
            'error':str(e)
        }


@router.post("")
def upload_file(file:FileUpload):
    try:
        sbx=Sandbox.connect(file.box_id)
        with urlopen(file.file_url) as response:
            sbx.files.write(file.file_path,response.read())
        return {
            "path":file.file_path
        }
    except Exception as e:
        return {
            "error":str(e)
        }

# def download(file_path:str):
#     content=sbx.files.read(file_path)
#     with open("D:/Python/develop/nexus_agent_box/app/file/test.md",'w') as file:
#         name=file.name
#         file.write(content)
@router.get("")
def download_file(box_id:str,file_path:str):
    try:
        file_path= file_path
        sbx=Sandbox.connect(box_id)
        content= sbx.files.read(file_path)
        byte_array=content.encode()
        # 创建一个临时文件
        result=str_upload_file(get_file_name(file_path),byte_array)
        return {
            'url':result
        }
    except Exception as e:
        return {
            "error":str(e)
        }

# 获取文件名（包含扩展名）
def get_file_name(path:str):
    p=Path(path)
    return p.name