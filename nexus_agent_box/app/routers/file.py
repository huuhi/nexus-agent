# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : file.py
# @Time    : 2026/4/24 17:33
from pathlib import Path

from app.schema.file import FileUpload, FileDownload, CreateFile
from app.utils.oss_utils import upload_bytes
from app.utils.url_guard import UrlNotAllowed, download_bytes
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
    """往沙盒写一个**文本**文件。

    ⚠️ 本接口的契约就是「文本」：``CreateFile.content`` 是 ``str``，
    HTTP JSON 本身也只能承载文本，所以这里 ``content.encode()`` 是**正确**的
    （与 :func:`download_file` 的损坏不是一回事，那里的错误是把 bytes 误当 str 用）。

    要往沙盒写二进制（PNG/docx/zip 等），走 ``POST /file``
    （传 ``file_url``，由 :func:`app.utils.url_guard.download_bytes` 取回原始字节）。

    ⚠️ 中文用 UTF-8 显式编码：``.encode()`` 不带参数时默认 UTF-8，
    但显式写出来是为了让「哪个编码」这件事在代码里一眼可见，不依赖默认值。
    """
    try:
        box= Sandbox.connect(file.box_id)
        if file.content is None:
            return {
                'error':'content 为空：不能写入空文件，请让调用方补上内容'
            }
        byte_array=file.content.encode('utf-8')
        box.files.write(file.path,byte_array)
        return {
            'path':file.path,
            'size':len(byte_array)
        }
    except Exception as e:
        return {
            'error':str(e)
        }


@router.post("")
def upload_file(file:FileUpload):
    try:
        sbx=Sandbox.connect(file.box_id)
        # ⚠️ 2026-10-04：这里原本是裸 urlopen(...).read()，可被用来打内网 / 读本机文件 /
        # 挂死线程 / OOM。现在走 download_bytes，内含协议白名单、内网判定、
        # 禁重定向、超时与体积上限五道防线。
        sbx.files.write(file.file_path,download_bytes(file.file_url))
        return {
            "path":file.file_path
        }
    except UrlNotAllowed as e:
        # 输入不合法：明确回话，让 LLM 能把原因讲给用户，而不是笼统的 error
        return {
            "error":f"下载地址被拒绝：{e}"
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
def download_file(box_id:str,file_path:str,user_id:str=None):
    """把沙盒里的文件取出来并转存 OSS，返回可访问的 URL。

    user_id 为可选参数（P2-10）：用于把产物放到 ``user/{userId}/artifact/{date}/`` 下；
    不传则落到 ``user/unknown/...``（不会失败，但目录能看出是异常数据）。

    🔴 **必须按二进制读（2026-10-05 修复 P0）**

    原来这里是 ``sbx.files.read(file_path)``，E2B SDK 的 ``format`` 默认值是 ``"text"``，
    实现是 ``return r.text`` —— httpx 会拿 charset 解码，非法字节一律替换成 U+FFFD。
    于是二进制文件在**上传 OSS 之前就已经被文本化损坏**了：

        PNG 头 89 50 4E 47 0D 0A 1A 0A  →  EF BF BD 50 4E 47 0D 0A
                                           （EF BF BD 就是 U+FFFD 的 UTF-8 编码）

    后果：① 首字节不再是魔数，图片/文档打不开；
    ② 每个非法字节 1 字节变 3 字节，体积凭空膨胀（实测 94367 → 171196）。
    docx/xlsx/zip 同理 —— 它们都是 zip 容器，任何字节被替换都会导致解包失败。

    所以这里显式传 ``format="bytes"``：SDK 走 ``bytearray(r.content)``，不经过任何解码。
    注意返回的是 ``bytearray`` 而非 ``bytes``，且 SDK 版本升级可能改签名，
    故统一用 ``bytes(...)`` 收口，避免把 ``bytearray`` 直接塞给 oss2。
    """
    try:
        sbx=Sandbox.connect(box_id)
        # 一次性全量读进内存：E2B SDK 的 bytes 模式没有流式返回，
        # 上传大文件时受沙盒服务 worker 内存限制，超大文件请走 /file（upload_file）反向链路。
        content= sbx.files.read(file_path, format="bytes")
        byte_array= bytes(content)
        # 创建一个临时文件
        result=upload_bytes(get_file_name(file_path),byte_array,user_id)
        return {
            'url':result,
            # 产物大小（P2-10）：前端下载卡片要展示，Java 侧也据此落库。
            # 必须是「上传给 OSS 的那串字节」的长度，不是解码后的字符数 ——
            # 旧实现报的是 len(已损坏内容)，与用户下载到的文件大小也对不上。
            'size':len(byte_array),
            # 🔴 代码版本标志（2026-10-07）：**只有修复后的代码才会带这个字段**。
            # 背景：10-05 修过「二进制被按文本读」的 P0（format 默认 "text"，非法字节
            # 全部替换成 U+FFFD，png/jpg/docx 全损坏、svg/md/html/csv 却正常 ——
            # 因为文本文件没有非法字节）。但修复在沙盒侧，**线上沙盒跑旧代码时
            # Java 侧无从察觉**（响应形状一模一样），用户只会看到产物又打不开了。
            # 现在响应里若没有 binary_read 字段，Java 侧会直接判定沙盒代码过旧并
            # 明确报错，而不是把坏文件发布给用户。
            'binary_read': True
        }
    except Exception as e:
        return {
            "error":str(e)
        }

# 获取文件名（包含扩展名）
def get_file_name(path:str):
    p=Path(path)
    return p.name