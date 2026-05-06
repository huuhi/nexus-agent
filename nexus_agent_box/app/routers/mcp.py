# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : mcp.py
# @Time    : 2026/4/26 20:00

from fastapi import APIRouter
from requests import request

router = APIRouter()


@router.get("/mcp")
def get_mcp_list(token: str) -> list[dict]:
    response = request(
        headers={"Authorization": "Bearer " + token},
        url="https://modelscope.cn/openapi/v1/mcp/servers/operational",
        method="get",
    )
    result = response.json()
    data = result.get("data", {})
    server_list = data.get("mcp_server_list", [])

    output = []
    for server in server_list:
        operational_urls = server.get("operational_urls", [])
        # 只取 streamable_http 类型的 url
        for entry in operational_urls:
            if entry.get("transport_type") == "streamable_http":
                output.append({
                    "url": entry.get("url", ""),
                    "description": server.get("description", ""),
                    "name": server.get("chinese_name") or server.get("name", ""),
                    "logoUrl": server.get("logo_url", ""),
                    "type": entry.get("transport_type", ""),
                })
                break  # 每个服务器只取一个 streamable_http url

    return output

if __name__ == '__main__':
    result= get_mcp_list('ms-cee96ba8-0e50-463d-b063-86e620931db2')
    print(result)