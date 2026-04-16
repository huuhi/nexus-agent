# nexus-agent

基于LangChain4j开发的AIAgent


### 4.16

昨天花了点时间去设计数据库，今天把目前需要的数据都设计出来了
完成第一个接口，之后就慢慢添加工具和知识库的功能！以及沙盒执行代码的功能。
这些完成了P0就OK了，核心功能搞定之后，就去做一些不核心功能，比如弄用户配置
用户如果有自己的APIKEY，那就用用户的！还有就是MCP只支持Streamable，这是官方支持的类型，并且优点也很多
具体请看：[官方文档](https://mcp-docs.cn/specification/2025-11-25/basic/transports#streamable-http)