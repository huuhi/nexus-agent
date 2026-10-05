# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : box.py
# @Time    : 2026/4/24 17:39
import os

from dotenv import load_dotenv
from e2b_code_interpreter import Sandbox
from fastapi import APIRouter

router=APIRouter()
load_dotenv()



@router.get("/box")
def create_box():
    """创建沙盒。

    ⚠️ 2026-10-05：支持 ``E2B_TEMPLATE_ID`` —— 自定义模板里预装了
    python-docx / openpyxl / python-pptx / dotnet / 中文字体，
    少一次现场 pip install，技能自带的 scripts/*.csx 也能跑
    （构建方式见 e2b/Dockerfile.template）。

    **留空就用 E2B 基础模板，行为与之前完全一致** —— 变量没配时不会启动失败。
    模板 id 刻意走环境变量：换模板要改代码重新发版，不值得。
    """
    try:
        template = os.getenv("E2B_TEMPLATE_ID", "").strip()
        box = Sandbox.create(template=template) if template else Sandbox.create()
        # 20分钟后过期。
        box.set_timeout(600)
        return {
            "message":"沙盒创建成功！",
            "box_id":box.sandbox_id,
            # 回传实际用的模板：排查「AI 说没有 dotnet」这类问题时，
            # 第一眼就能看出这次沙盒到底带没带自定义模板。
            "template":template or "(基础模板)"
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
