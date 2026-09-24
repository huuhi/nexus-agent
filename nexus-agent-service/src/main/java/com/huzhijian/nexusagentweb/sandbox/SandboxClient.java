package com.huzhijian.nexusagentweb.sandbox;

import com.huzhijian.nexusagentweb.dto.UploadFileDTO;
import com.huzhijian.nexusagentweb.utils.HttpUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 沙盒服务的传输层（FastAPI，默认 8000 端口）。
 * <p>
 * 把「怎么调沙盒」与「暴露给 AI 的工具」分开：本类只负责 HTTP，
 * {@code BoxTool} 只负责工具定义，{@link SandboxSessionRegistry} 负责生命周期。
 * 这样会话回收任务也能直接调用本类销毁沙盒，而不会与工具层互相依赖。
 * <p>
 * ⚠️ 路由与字段名必须与 {@code nexus_agent_box/app/routers/} 保持一致：
 * 执行命令用的是 {@code /execute/cmd} + {@code cmd} 字段（不是 {@code /execute/code} + {@code code}）。
 */
@Component
@RequiredArgsConstructor
public class SandboxClient {

    private final HttpUtils httpUtils;

    public Map<String, Object> createBox() {
        return httpUtils.get("/box").block();
    }

    public Map<String, Object> deleteBox(String boxId) {
        return httpUtils.delete("/box/{box_id}", boxId).block();
    }

    public Map<String, Object> uploadFile(UploadFileDTO uploadFile) {
        return httpUtils.post("/file", uploadFile).block();
    }

    /**
     * 从沙盒下载文件（沙盒 → OSS，返回可访问 URL）。
     *
     * @param userId 可选（P2-10）：传给沙盒服务用于把产物放到
     *               {@code user/{userId}/artifact/{date}/} 下；
     *               取不到时传 null，服务端会落到 {@code user/unknown/...}（不会失败）
     */
    public Map<String, Object> downloadFile(String path, String boxId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("box_id", boxId);
        params.put("file_path", path);
        if (userId != null) {
            params.put("user_id", String.valueOf(userId));
        }
        return httpUtils.get("/file", params).block();
    }

    public List<Map<String, Object>> listDir(String dirPath, String boxId) {
        return httpUtils.getWithList("/file/list", Map.of("box_id", boxId, "dir_path", dirPath)).block();
    }

    public Map<String, Object> checkFileExist(String boxId, String path) {
        return httpUtils.get("/file/exists", Map.of("box_id", boxId, "file_path", path)).block();
    }

    public Map<String, Object> createAndWriteFile(String path, String boxId, String content) {
        return httpUtils.post("/file/create", Map.of("path", path, "box_id", boxId, "content", content)).block();
    }

    public Map<String, Object> executeCode(String boxId, String code) {
        HashMap<String, Object> req = new HashMap<>();
        req.put("box_id", boxId);
        req.put("code", code);
        return httpUtils.post("/execute/code", req).block();
    }

    public Map<String, Object> executeCmd(String boxId, String cmd) {
        HashMap<String, Object> req = new HashMap<>();
        req.put("box_id", boxId);
        // 沙盒侧 CmdRequest 的字段名是 cmd；传 code 会把命令当 Python 源码执行
        req.put("cmd", cmd);
        return httpUtils.post("/execute/cmd", req).block();
    }
}
