# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : execute.py
# @Time    : 2026/4/24 15:44
from pydantic import BaseModel


class CodeRequest(BaseModel):
    code:str
    box_id:str

class CmdRequest(BaseModel):
    cmd:str
    box_id:str