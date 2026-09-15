# @Version : 1.0
# @Auothor  : 胡志坚
# @File    : main.py
# @Time    : 2026/4/24 15:58
import uvicorn
from fastapi import FastAPI

from app.routers import box
from app.routers import execute
from app.routers import file
from app.routers import mcp

app = FastAPI()

app.include_router(execute.router)
app.include_router(file.router)
app.include_router(box.router)
app.include_router(mcp.router)

if __name__ == '__main__':
    uvicorn.run(app,host="127.0.0.1",port=8000,env_file='.env')