# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : test.py
# @Time    : 2026/4/24

from app.schema.code import CodeRequest
from e2b_code_interpreter import Sandbox
from fastapi import APIRouter

from nexus_agent_box.app.schema.code import CmdRequest

router = APIRouter(prefix="/execute")


# 创建环境，执行命令
@router.post("/cmd")
def execute_cmd(req:CmdRequest):
    try:
        print("执行的命令："+req.cmd)
        box= Sandbox.connect(req.box_id)
        result=box.commands.run(req.cmd)
        return {
            "success": result.exit_code==0,
            "stdout":result.stdout,
            "error":result.error,
            "stderr":result.stderr
        }
    except Exception as e:
        return {
                'error': str(e)
        }
@router.post("/code")
def execute_code(req: CodeRequest):
    sbx = None
    try:
        print("执行的代码："+req.code)
        # 2️⃣ 换回你原本正确的 Sandbox.create()
        # 此时它会自动读取全局环境变量中的 E2B_API_KEY
        sbx = Sandbox.connect(req.box_id)
        # sbx=Sandbox.create()
        # 执行代码
        execution = sbx.run_code(req.code)
        # 取结果
        result = {
            "text": execution.text,
            "stdout": execution.logs.stdout,
            "stderr": execution.logs.stderr,
            "error": str(execution.error) if execution.error else None
        }

        return result

    except Exception as e:
        return {
            "error": str(e)
        }
