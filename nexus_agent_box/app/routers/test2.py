# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : test2.py
# @Time    : 2026/4/24 16:08
from urllib.request import urlopen

from dotenv import load_dotenv
from e2b_code_interpreter import Sandbox
load_dotenv()

sbx=Sandbox.create()

def run_code(code:str):
    execution= sbx.run_code(code)
    print(execution.logs.stdout[0])

def upload_file(url:str):
    with urlopen(url) as response:
        content=response.read()
        sbx.files.write("/tmp/test.md",content)
        print(content)


# 下载文件到本地
def download(file_path:str):
    content=sbx.files.read(file_path)
    with open("D:/Python/develop/nexus_agent_box/app/file/test.md",'w') as file:
        name=file.name
        file.write(content)

upload_file("https://nexus-agent-file.oss-cn-guangzhou.aliyuncs.com/user/file/user_1/359a2f5f-dff3-4220-94db-4e8a7924c125.md")
result= sbx.files.list('/tmp')
print(result)

download("/tmp/test.md")



sbx.kill()