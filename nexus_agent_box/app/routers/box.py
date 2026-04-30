# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : box.py
# @Time    : 2026/4/24 17:39
from dotenv import load_dotenv
from e2b_code_interpreter import Sandbox
from fastapi import APIRouter

router=APIRouter()
load_dotenv()



@router.get("/box")
def create_box():
    try:
        box= Sandbox.create()
        # 20分钟后过期。
        box.set_timeout(600)
        return {
            "message":"沙盒创建成功！",
            "box_id":box.sandbox_id
        }
    except Exception as e:
        return{
            'error':str(e)
        }

# 清理沙盒，完成任务之后
@router.delete("/box/{box_id}")
def remove_box(box_id:str):
    try:
        box=Sandbox.connect(box_id)
        box.kill()
        return {
            'success':"true"
        }
    except Exception as e:
        return {
            "error":str(e)
        }
