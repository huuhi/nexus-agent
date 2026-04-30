# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : mcp.py
# @Time    : 2026/4/26 20:00

from app.routers.file import router
from fastapi import APIRouter
from requests import request

route=APIRouter()

@router.get("/mcp")
def get_mcp_list(token:str):
    response=request(
        headers={
            'Authorization':'Bearer '+token
        },
        url="https://modelscope.cn/openapi/v1/mcp/servers/operational",
        method='get'
    )
    text= response.text
    result=response.json()
    data= result.get('data')
    # print(text+'\n')
    print(data.get('total_count'))
    print(result.get('success'))
    list= data.get('mcp_server_list')

    # list=json.loads(list)
    print(type(list))
    print(len(list))