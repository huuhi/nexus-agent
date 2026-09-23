package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.dto.UploadFileDTO;
import com.huzhijian.nexusagentweb.handler.SafeExecuteToolHandler;
import com.huzhijian.nexusagentweb.sandbox.SandboxClient;
import com.huzhijian.nexusagentweb.sandbox.SandboxSessionRegistry;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/30
 * 说明: 沙盒工具（E2B 云沙盒）。
 * <p>
 * 设计要点：
 * <ol>
 *   <li>HTTP 调用下沉到 {@link SandboxClient}，本类只负责「暴露给 AI 的工具」这一层。</li>
 *   <li>用 {@link ToolMemoryId} 拿到会话 ID（LangChain4j 会把 AI Service 的 memoryId 注入进来，
 *       该参数**不会**出现在给模型的工具签名里）。因此除 create_box 外的工具的
 *       {@code boxId} 都是**可选**的：不传就用当前会话的沙盒，模型不必自己记 box_id。</li>
 *   <li>沙盒按会话复用、空闲自动回收，见 {@link SandboxSessionRegistry}（E2B 按量计费）。</li>
 *   <li>所有失败都经 {@link SafeExecuteToolHandler} 转成结构化结果
 *       （success / errorCode / message / hint），让模型能自纠而不是盲试。</li>
 * </ol>
 */
@Component
@Slf4j
public class BoxTool implements AgentToolSet {

    @Override
    public String key() {
        return "box";
    }

    @Override
    public String description() {
        return "沙盒：创建/复用沙盒、读写与列举文件、执行代码与 Linux 命令";
    }

    private final SandboxClient sandboxClient;
    private final SandboxSessionRegistry sandboxSessions;
    private final SafeExecuteToolHandler safeExecuteToolHandler;

    public BoxTool(SandboxClient sandboxClient,
                   SandboxSessionRegistry sandboxSessions,
                   SafeExecuteToolHandler safeExecuteToolHandler) {
        this.sandboxClient = sandboxClient;
        this.sandboxSessions = sandboxSessions;
        this.safeExecuteToolHandler = safeExecuteToolHandler;
    }

    /**
     * 创建（或复用）沙盒，返回沙盒 ID。
     * <p>
     * 同一会话内重复调用会复用已有沙盒，不会重复计费。
     */
    @Tool(name = "create_box", value = "创建或复用沙盒，返回沙盒ID。同一会话内重复调用会复用已有沙盒。")
    public Map<String, Object> createBox(@ToolMemoryId Object memoryId) {
        String sessionKey = sessionKey(memoryId);
        Map<String, Object> result = safeExecuteToolHandler.mapTool("create_box",
                () -> sandboxSessions.acquire(sessionKey));
        // 新建的沙盒需要登记，后续工具才能按会话复用
        if (result != null && !Boolean.TRUE.equals(result.get("reused"))) {
            Object boxId = result.get("box_id");
            if (boxId != null) {
                sandboxSessions.register(sessionKey, boxId.toString());
            }
        }
        return result;
    }

    /**
     * 销毁沙盒。不传 boxId 则销毁当前会话的沙盒。
     */
    @Tool(name = "delete_box", value = "销毁沙盒。不传 boxId 时销毁当前会话的沙盒；任务完成后建议调用以停止计费。")
    public Map<String, Object> deleteBox(@ToolMemoryId Object memoryId,
                                        @P(value = "沙盒ID，可不传", required = false) String boxId) {
        String sessionKey = sessionKey(memoryId);
        if (isBlank(boxId)) {
            return safeExecuteToolHandler.mapTool("delete_box", () -> sandboxSessions.release(sessionKey));
        }
        return safeExecuteToolHandler.mapTool("delete_box", () -> sandboxClient.deleteBox(boxId));
    }

    /**
     * 上传网络文件到沙盒。
     */
    @Tool(name = "upload_file", value = "把网络文件下载到沙盒中。file_url 为网络文件地址，file_path 为沙盒内目标路径（如 /tmp/a.md）。")
    public Map<String, Object> uploadFile(@ToolMemoryId Object memoryId,
                                         @P("上传文件请求体") UploadFileDTO uploadFile) {
        String boxId = resolveBoxId(memoryId, uploadFile == null ? null : uploadFile.box_id());
        if (boxId == null) {
            return noSandbox("upload_file");
        }
        // UploadFileDTO 的字段名是下划线风格，与沙盒侧 FileUpload 的线上格式保持一致
        UploadFileDTO resolved = new UploadFileDTO(uploadFile.file_url(), uploadFile.file_path(), boxId);
        return handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("upload_file", () -> sandboxClient.uploadFile(resolved)));
    }

    /**
     * 从沙盒下载文件（会转存到 OSS 并返回 URL）。
     */
    @Tool(name = "download_file", value = "从沙盒中下载文件，返回可访问的 URL。")
    public Map<String, Object> download(@ToolMemoryId Object memoryId,
                                       @P("沙盒内文件路径") String path,
                                       @P(value = "沙盒ID，可不传", required = false) String boxId) {
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return noSandbox("download_file");
        }
        return handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("download_file", () -> sandboxClient.downloadFile(path, resolved)));
    }

    /**
     * 查看目录下的文件。
     */
    @Tool(name = "list_dir", value = "查看沙盒中某个目录下的文件列表。")
    public List<Map<String, Object>> listDir(@ToolMemoryId Object memoryId,
                                            @P("目录路径") String dirPath,
                                            @P(value = "沙盒ID，可不传", required = false) String boxId) {
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return List.of(noSandbox("list_dir"));
        }
        return safeExecuteToolHandler.listTool("list_dir", () -> sandboxClient.listDir(dirPath, resolved));
    }

    /**
     * 判断文件/目录是否存在。
     */
    @Tool(name = "check_file_exist", value = "判断沙盒中的文件或目录是否存在。")
    public Map<String, Object> existFile(@ToolMemoryId Object memoryId,
                                        @P("沙盒内路径") String path,
                                        @P(value = "沙盒ID，可不传", required = false) String boxId) {
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return noSandbox("check_file_exist");
        }
        return handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("check_file_exist", () -> sandboxClient.checkFileExist(resolved, path)));
    }

    /**
     * 创建文件并写入内容。
     */
    @Tool(name = "create_write_file", value = "在沙盒中创建文件并写入内容。")
    public Map<String, Object> createWithWriteFile(@ToolMemoryId Object memoryId,
                                                  @P("文件路径，比如 /home/abc.py") String path,
                                                  @P("内容") String content,
                                                  @P(value = "沙盒ID，可不传", required = false) String boxId) {
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return noSandbox("create_write_file");
        }
        return handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("create_write_file",
                () -> sandboxClient.createAndWriteFile(path, resolved, content)));
    }

    /**
     * 执行代码（Python / JS / TS）。
     */
    @Tool(name = "execute_code", value = "在沙盒中执行代码，支持 Python、JS/TS。")
    public Map<String, Object> executeCode(@ToolMemoryId Object memoryId,
                                          @P("代码") String code,
                                          @P(value = "沙盒ID，可不传", required = false) String boxId) {
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return noSandbox("execute_code");
        }
        log.debug("execute_code 代码长度={}", code == null ? 0 : code.length());
        return handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("execute_code", () -> sandboxClient.executeCode(resolved, code)));
    }

    /**
     * 执行 Linux 命令。
     */
    @Tool(name = "execute_cmd", value = "在沙盒中执行 Linux 命令。")
    public Map<String, Object> executeCmd(@ToolMemoryId Object memoryId,
                                         @P("命令") String cmd,
                                         @P(value = "沙盒ID，可不传", required = false) String boxId) {
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return noSandbox("execute_cmd");
        }
        log.debug("execute_cmd 命令={}", cmd);
        return handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("execute_cmd", () -> sandboxClient.executeCmd(resolved, cmd)));
    }

    /**
     * 包装「针对某个沙盒」的工具调用结果，并做失效自愈。
     * <p>
     * 背景：E2B 会**自动暂停**空闲沙盒，我们缓存的 boxId 可能已经失效。
     * 此时若只是把错误抛给模型，它多半会反复用同一个死沙盒重试。
     * 所以这里检测到「沙盒已不存在」就作废缓存，并给出「重新 create_box」的明确指引。
     */
    private Map<String, Object> handleBoxResult(Object memoryId, Map<String, Object> result) {
        if (result == null || !isFailure(result)) {
            return result;
        }
        String text = String.valueOf(result).toLowerCase();
        boolean sandboxGone = text.contains("not found") || text.contains("not running")
                || text.contains("paused") || text.contains("expired") || text.contains("killed");
        if (!sandboxGone) {
            return result;
        }
        sandboxSessions.invalidate(sessionKey(memoryId));
        Map<String, Object> withHint = new LinkedHashMap<>(result);
        withHint.put("hint", "当前沙盒已失效（可能被自动回收）。请重新调用 create_box 获取新沙盒后重试。");
        return withHint;
    }

    /**
     * 判断沙盒返回是否算失败。
     * <p>
     * 沙盒服务的成功形状是 {@code {success:true,...}} 或 {@code {message/box_id/url,...}}，
     * 失败形状是 {@code {success:false,...}} 或 {@code {error:...}} —— 后者**没有** success 字段，
     * 所以不能只看 success。
     */
    private static boolean isFailure(Map<String, Object> result) {
        Object success = result.get("success");
        if (success != null) {
            return !Boolean.parseBoolean(String.valueOf(success));
        }
        return result.containsKey("error");
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /**
     * 解析要操作的沙盒：显式传入的 boxId 优先（兼容旧调用方式），否则取当前会话的沙盒。
     * 同时刷新会话最后使用时间，避免正在使用的沙盒被回收任务误删。
     */
    private String resolveBoxId(Object memoryId, String explicitBoxId) {
        String sessionKey = sessionKey(memoryId);
        if (sessionKey != null) {
            sandboxSessions.touch(sessionKey);
        }
        if (!isBlank(explicitBoxId)) {
            return explicitBoxId;
        }
        return sessionKey == null ? null : sandboxSessions.boxIdOf(sessionKey);
    }

    /**
     * 会话标识。memoryId 由 LangChain4j 经 {@link ToolMemoryId} 注入（即会话 ID）。
     * 取不到时返回 null，此时需要模型显式提供 boxId。
     */
    private static String sessionKey(Object memoryId) {
        return memoryId == null ? null : String.valueOf(memoryId);
    }

    /**
     * 无法定位沙盒时返回结构化提示，引导模型先建沙盒，
     * 而不是抛一个它看不懂的异常。
     */
    private static Map<String, Object> noSandbox(String toolName) {
        log.warn("工具 [{}] 无法定位沙盒：当前会话没有沙盒，且调用方未提供 boxId", toolName);
        return Map.of(
                "success", false,
                "errorCode", "NO_SANDBOX",
                "message", "当前会话还没有沙盒",
                "hint", "请先调用 create_box 创建沙盒，再重试本工具；或在参数中显式传入 boxId。"
        );
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
