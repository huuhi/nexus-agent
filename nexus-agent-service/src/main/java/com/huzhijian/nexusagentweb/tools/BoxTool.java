package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.dto.CreateFileDTO;
import com.huzhijian.nexusagentweb.dto.UploadFileDTO;
import com.huzhijian.nexusagentweb.utils.HttpUtils;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/30
 * 说明: 沙盒 工具
 */
@Component
@Slf4j
public class BoxTool {
    private final HttpUtils httpUtils;

    public BoxTool(HttpUtils httpUtils) {
        this.httpUtils = httpUtils;
    }


    //    创建沙盒
    @Tool(name = "create_box",value = "创建沙盒，将返回沙盒ID")
    public Map<String,Object> createBox() {
        return httpUtils.get("/box").block();
    }
//    删除沙盒
    @Tool(name = "delete_box",value = "删除沙盒")
    public Map<String, Object> deleteBox(@P("沙盒ID") String boxId){
        return httpUtils.delete("/box/{box_id}", boxId).block();
    }

//    文件相关工具
    @Tool(name="upload_file",value = "上传文件")
    public Map<String,Object> uploadFile(@P("上传文件请求体，fileUrl为网络文件，filePath为存储路径+文件名比如:/tmp/abc.md,boxId为沙盒ID") UploadFileDTO uploadFile){
        return httpUtils.post("/file", uploadFile).block();
    }
//    下载文件
    @Tool(name = "download_file",value = "从沙盒中下载文件,将返回文件url")
    public Map<String,Object> download(@P("文件路径")String path,@P("沙盒ID")String boxId){
        return httpUtils.get("/file",Map.of("box_id",boxId,"file_path",path)).block();
    }
//    查看文件夹下的文件
    @Tool(name = "list_dir",value = "查看目录下的文件")
    public List<Map<String,Object>>  listDir(@P("目录路径") String dirPath,@P("沙盒ID")String boxId){
        return httpUtils.getWithList("/file/list",Map.of("box_id",boxId,"dir_path",dirPath)).block();
    }
//    查看文件是否存在
    @Tool(name = "check_file_exist",value = "查看文件/文件夹是否存在")
    public Map<String,Object> existFile(@P("沙盒ID")String boxId,@P("路径")String path){
        return httpUtils.get("/file/exists",Map.of("box_id",boxId,"file_path",path)).block();
    }

//    创建并写入内容
    @Tool(name = "create_write_file",value = "创建并且写入内容")
    public Map<String,Object> createWithWriteFile(@P("请求体，注意内容的换行符！")CreateFileDTO dto){
        return httpUtils.post("/file/create",dto).block();
    }

//    执行代码
    @Tool(name="execute_code",value = "执行代码，可执行Python,JS/TS代码")
    public Map<String,Object> executeCode(@P("沙盒ID")String boxId,@P("代码")String code){
        return httpUtils.post("/execute/code",Map.of("box_id",boxId,"code",code)).block();
    }
//    执行命令
    @Tool(name = "execute_cmd",value = "执行命令(Linux系统)")
    public Map<String,Object> executeCmd(@P("沙盒ID")String boxId,@P("命令")String cmd){
        return httpUtils.post("/execute/cmd",Map.of("box_id",boxId,"cmd",cmd)).block();
    }
}
