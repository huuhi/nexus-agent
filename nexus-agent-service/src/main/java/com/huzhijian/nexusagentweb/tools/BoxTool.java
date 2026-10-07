package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.dto.UploadFileDTO;
import com.huzhijian.nexusagentweb.exception.QuotaExceededException;
import com.huzhijian.nexusagentweb.handler.SafeExecuteToolHandler;
import com.huzhijian.nexusagentweb.sandbox.SandboxClient;
import com.huzhijian.nexusagentweb.sandbox.SandboxSessionRegistry;
import com.huzhijian.nexusagentweb.service.QuotaService;
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
    private final ToolCallGuard toolCallGuard;
    private final RunUserRegistry runUserRegistry;
    private final QuotaService quotaService;
    /** 2026-10-07：产物魔数校验要用（读 OSS 对象头部）。 */
    private final com.huzhijian.nexusagentweb.utils.AliOssUtil aliOssUtil;

    public BoxTool(SandboxClient sandboxClient,
                   SandboxSessionRegistry sandboxSessions,
                   SafeExecuteToolHandler safeExecuteToolHandler,
                   ToolCallGuard toolCallGuard,
                   RunUserRegistry runUserRegistry,
                   QuotaService quotaService,
                   com.huzhijian.nexusagentweb.utils.AliOssUtil aliOssUtil) {
        this.sandboxClient = sandboxClient;
        this.sandboxSessions = sandboxSessions;
        this.safeExecuteToolHandler = safeExecuteToolHandler;
        this.toolCallGuard = toolCallGuard;
        this.runUserRegistry = runUserRegistry;
        this.quotaService = quotaService;
        this.aliOssUtil = aliOssUtil;
    }

    /**
     * 创建（或复用）沙盒，返回沙盒 ID。
     * <p>
     * 同一会话内重复调用会复用已有沙盒，不会重复计费。
     */
    @Tool(name = "create_box", value = "创建或复用沙盒，返回沙盒ID。同一会话内重复调用会复用已有沙盒。")
    public Map<String, Object> createBox(@ToolMemoryId Object memoryId) {
        // 无参数工具：同一会话内反复调用即视为重复（复用逻辑本身很轻，但模型死循环会白烧 token）
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "create_box", ToolCallGuard.fingerprint());
        if (blocked != null) {
            return blocked;
        }
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
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "delete_box",
                ToolCallGuard.fingerprint(boxId));
        if (blocked != null) {
            return blocked;
        }
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
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "upload_file",
                ToolCallGuard.fingerprint(uploadFile == null ? null : uploadFile.file_url(),
                        uploadFile == null ? null : uploadFile.file_path()));
        if (blocked != null) {
            return blocked;
        }
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
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "download_file",
                ToolCallGuard.fingerprint(path));
        if (blocked != null) {
            return blocked;
        }
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return noSandbox("download_file");
        }
        // 🔴 不能用 UserContextHolder.getUserId()：工具跑在流式回调线程上，ThreadLocal 恒为 null，
        //    结果就是 OSS 侧拿不到 user_id（对象不归属任何用户）。必须走 RunUserRegistry 反查。
        Long userId = runUserRegistry.findUserId(memoryId);
        Map<String, Object> result = handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("download_file",
                () -> sandboxClient.downloadFile(path, resolved, userId)));
//        2026-10-07：只记诊断日志，**不拦**（理由见 warnIfSandboxCodeOutdated）
        warnIfSandboxCodeOutdated(result, "download_file");
        return result;
    }

    /**
     * 把沙盒里的文件作为**交付物**发布给用户（P2-10）。
     * <p>
     * 与 {@code download_file} 的区别在**语义**：前者只是"把文件取出来"（模型拿到一个 URL 自己看），
     * 后者表示"这是要交给用户的成果" —— 返回结果里带 {@code artifact} 结构，
     * 上层（{@code ChatServiceImpl.onToolExecuted}）据此**落库**（{@code sys_file}, biz_type=ARTIFACT + session_id）
     * 并推 **SSE {@code artifact} 事件**，前端渲染成下载卡片。
     * <p>
     * 为什么落库与事件不在本类做：这里只有 {@code @ToolMemoryId}（会话 ID），
     * 而落库需要 userId、发事件需要 SSE writer —— 两者都在对话主流程里，且都在同一次工具回调中，
     * 因此把"产出"与"交付"分开：工具负责产出，主流程负责交付。
     */
    @Tool(name = "publish_artifact",
            value = "把沙盒中的文件作为交付物发布给用户（用户可直接下载）。生成报告/表格/文档/图片等文件后，用它把文件交给用户。")
    public Map<String, Object> publishArtifact(@ToolMemoryId Object memoryId,
                                              @P("沙盒内文件路径，例如 /home/report.docx") String path,
                                              @P(value = "给用户看的文件名，可不传（默认取路径中的文件名）", required = false) String name,
                                              @P(value = "沙盒ID，可不传", required = false) String boxId) {
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "publish_artifact",
                ToolCallGuard.fingerprint(path, name));
        if (blocked != null) {
            return blocked;
        }
        String resolved = resolveBoxId(memoryId, boxId);
        if (resolved == null) {
            return noSandbox("publish_artifact");
        }
        // 🔴 不能用 UserContextHolder.getUserId()：工具跑在流式回调线程上，ThreadLocal 恒为 null
        Long userId = runUserRegistry.findUserId(memoryId);
        if (userId == null) {
            return structuredFailure("NO_USER", "无法确定当前用户，产物未发布（会话上下文缺失）。",
                    "不要重复调用该工具。");
        }
        // 文件 + 产物配额（docs/sql/012）：**必须在转存 OSS 之前**拦 ——
        // 一旦走完 downloadFile，文件就已经生成并上传了，此时再拦只能拦住"落库 + 下载卡片"，
        // 对象会变成 OSS 里的孤儿（还占着存储）。拦在这里才能真正不产生新文件。
        try {
            quotaService.assertWithinFileQuota(userId);
        } catch (QuotaExceededException e) {
            log.warn("产物被配额拦截：userId={} 原因={}", userId, e.getMessage());
            return structuredFailure("QUOTA_EXCEEDED", e.getMessage(),
                    "不要再重试，也不要改用 download_file 绕开 —— 请直接告诉用户文件数量已达今日上限，"
                            + "并说明明天 00:00 会自动重置。");
        }
        Map<String, Object> result = handleBoxResult(memoryId, safeExecuteToolHandler.mapTool("publish_artifact",
                () -> sandboxClient.downloadFile(path, resolved, userId)));
        if (result == null || isFailure(result)) {
            // 失败（含沙盒失效）原样回传：SafeExecuteToolHandler/handleBoxResult 已经给了模型自纠提示
            return result;
        }
        result = warnIfSandboxCodeOutdated(result, "publish_artifact");
        Map<String, Object> payload = artifactPayload(result, path, name);
        if (isFailure(payload)) {
            return payload;
        }
//        🔴 2026-10-07：发布前做**魔数校验**（第二道防线）。
//        即使沙盒代码带了 binary_read 标志，任何环节的字节损坏都不该流到用户手里 ——
//        10-05 的教训是「用户拿到打不开的文件」比「明确报错」糟得多。
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) payload.get("artifact");
        String url = artifact == null ? null : (String) artifact.get("url");
//        用「最终发布名」判断扩展名（模型可以指定 name，可能与路径不同）
        String publishedName = artifact == null || artifact.get("name") == null
                ? fileNameOf(path) : String.valueOf(artifact.get("name"));
        String mismatch = magicMismatch(url, publishedName);
        if (mismatch != null) {
            // 尽力删掉刚上传的坏对象，别让它留在 OSS 里等人下载
            try {
                aliOssUtil.deleteByUrl(url);
            } catch (Exception e) {
                log.warn("删除损坏产物失败（忽略）：url={}", url);
            }
            log.error("产物魔数校验失败，已拒绝发布：path={} 原因={} 沙盒代码版本={}",
                    path, mismatch, result.containsKey(BINARY_READ_FLAG) ? "新" : "旧（缺 binary_read）");
//            hint 按「沙盒代码确实是旧的」与否给不同说法：旧代码那条要直接给出修复命令，
//            否则模型和运维都只能猜
            String hint = result.containsKey(BINARY_READ_FLAG)
                    ? "沙盒返回了版本标志却仍读出坏文件，可能是文件在生成环节就已损坏。"
                    + "不要用同一文件反复重试，请换个路径或重新生成。"
                    : "已确认沙盒代码过旧（响应缺 binary_read 标志）—— 二进制被按文本读取。"
                    + "请在服务器上更新并重建 box 容器：cd nexus_agent_box && "
                    + "docker compose -f docker-compose.yml up -d --build，然后重试。";
            return structuredFailure("SANDBOX_ARTIFACT_CORRUPTED",
                    "产物文件头校验失败：" + mismatch + "（坏文件已删除，未发布）", hint);
        }
        return payload;
    }

    /**
     * 沙盒下载响应里标识「按二进制读」的字段（10-05 修复后的 nexus_agent_box 才有）。
     */
    private static final String BINARY_READ_FLAG = "binary_read";

    /**
     * 沙盒代码可能过旧的**诊断**（2026-10-07）。
     * <p>
     * <b>为什么不直接拦截</b>（曾经的写法已撤回）：本项目 web 与 box <b>独立部署</b>，
     * 两者升级节奏不同步。若把「缺这个标志」当成硬失败，会出现
     * 「只更新了 jar、没更新 box → <b>所有</b>文件下载全报错」——
     * 而那些文件（csv/md/html 等文本类）本来完全没问题，拦下来只是白 outage。
     * <p>
     * 真正的拦截是 {@link #magicMismatch} 那道**魔数校验**：只有二进制文件才需要担心被损坏，
     * 文本文件没有非法字节、怎么解码都不坏。这才是针对性防线。
     * <p>
     * 所以这里只<b>打 WARN</b>：沙盒代码老这件事应该在日志里看得见，
     * 并在真的产出坏文件时作为 hint 提示更新，而不是无条件拦路。
     */
    private Map<String, Object> warnIfSandboxCodeOutdated(Map<String, Object> result, String toolName) {
        if (result == null || isFailure(result) || result.containsKey(BINARY_READ_FLAG)) {
            return result;
        }
        log.warn("工具 [{}]：沙盒响应缺少 {} 标志 —— 线上 box 可能仍是 10-05 之前的旧代码"
                        + "（二进制会被按文本读，png/jpg/docx 等产物必然损坏）。"
                        + "若产物确实损坏，先更新并重建 box：cd nexus_agent_box && "
                        + "docker compose -f docker-compose.yml up -d --build",
                toolName, BINARY_READ_FLAG);
        return result;
    }

    /** 常见二进制格式的文件头魔数（key = 小写扩展名）。文本类（md/html/csv/svg…）不在表内、不校验 */
    private static final Map<String, byte[]> MAGIC_BY_EXT = Map.ofEntries(
            Map.entry("png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}),          // ‰PNG
            Map.entry("jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),  // JPEG SOI
            Map.entry("jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            Map.entry("gif", new byte[]{0x47, 0x49, 0x46, 0x38}),                 // GIF8
            Map.entry("webp", new byte[]{0x52, 0x49, 0x46, 0x46}),                // RIFF
            Map.entry("bmp", new byte[]{0x42, 0x4D}),                             // BM
            Map.entry("pdf", new byte[]{0x25, 0x50, 0x44, 0x46}),                 // %PDF
            Map.entry("docx", new byte[]{0x50, 0x4B, 0x03, 0x04}),                // PK..
            Map.entry("xlsx", new byte[]{0x50, 0x4B, 0x03, 0x04}),
            Map.entry("pptx", new byte[]{0x50, 0x4B, 0x03, 0x04}),
            Map.entry("zip", new byte[]{0x50, 0x4B, 0x03, 0x04}),
            Map.entry("doc", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}), // OLE2
            Map.entry("xls", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}),
            Map.entry("ppt", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0})
    );

    /**
     * 校验 OSS 对象的文件头是否与扩展名的魔数一致。
     *
     * @return null = 通过（或无法校验，放行）；非 null = 不匹配的原因
     */
    private String magicMismatch(String url, String fileName) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String ext = extensionOf(fileName);
        if (ext == null) {
            return null;
        }
        byte[] expected = MAGIC_BY_EXT.get(ext);
        if (expected == null) {
            return null; // 文本类/未知格式不校验
        }
        byte[] head = aliOssUtil.readObjectHead(url, Math.max(expected.length + 4, 8));
        if (head == null) {
            // 读不到（OSS 抖动等）不拦截：发布链路本身会因下载失败而暴露
            log.warn("魔数校验读不到对象头，放行：url={}", url);
            return null;
        }
        if (head.length < expected.length) {
            return "文件只有 " + head.length + " 字节，比 " + ext + " 的文件头还短";
        }
        for (int i = 0; i < expected.length; i++) {
            if (head[i] != expected[i]) {
                return "文件头不是合法的 ." + ext + "（实际首字节 "
                        + String.format("%02X %02X %02X", head[0],
                                head.length > 1 ? head[1] : 0, head.length > 2 ? head[2] : 0)
                        + "）—— 文件内容极可能已在沙盒侧被按文本损坏";
            }
        }
        return null;
    }

    /**
     * 把「下载结果」包装成 artifact 结构。
     * <p>
     * URL 缺失时按失败返回而不是假装成功 —— 上层会据此落库并发下载卡片，
     * 给前端一个空链接比直接说清楚"没拿到链接"更糟。
     */
    private Map<String, Object> artifactPayload(Map<String, Object> downloadResult, String path, String name) {
        Object url = downloadResult.get("url");
        if (url == null || String.valueOf(url).isBlank()) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("success", false);
            failure.put("errorCode", "EMPTY_RESPONSE");
            failure.put("message", "沙盒服务没有返回下载链接（url 为空）");
            failure.put("hint", "不要重复调用同一参数；请先用 list_dir 确认文件存在，再重试一次。");
            return failure;
        }
        String fileName = (name == null || name.isBlank()) ? fileNameOf(path) : name.trim();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("name", fileName);
        artifact.put("url", url);
        artifact.put("sourcePath", path);
        artifact.put("extension", extensionOf(fileName));
        Object size = downloadResult.get("size");
        if (size != null) {
            artifact.put("size", size);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("message", "已把「" + fileName + "」发布为交付物，用户可直接下载。");
        payload.put("artifact", artifact);
        return payload;
    }

    /** 从沙盒路径取文件名（含扩展名） */
    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return "artifact";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "artifact" : name;
    }

    /** 从文件名取扩展名（不含点、小写）；取不到返回 null */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase()
                : null;
    }

    /**
     * 查看目录下的文件。
     */
    @Tool(name = "list_dir", value = "查看沙盒中某个目录下的文件列表。")
    public List<Map<String, Object>> listDir(@ToolMemoryId Object memoryId,
                                            @P("目录路径") String dirPath,
                                            @P(value = "沙盒ID，可不传", required = false) String boxId) {
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "list_dir",
                ToolCallGuard.fingerprint(dirPath));
        if (blocked != null) {
            return List.of(blocked);
        }
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
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "check_file_exist",
                ToolCallGuard.fingerprint(path));
        if (blocked != null) {
            return blocked;
        }
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
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "create_write_file",
                ToolCallGuard.fingerprint(path, content));
        if (blocked != null) {
            return blocked;
        }
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
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "execute_code",
                ToolCallGuard.fingerprint(code));
        if (blocked != null) {
            return blocked;
        }
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
        Map<String, Object> blocked = toolCallGuard.intercept(memoryId, "execute_cmd",
                ToolCallGuard.fingerprint(cmd));
        if (blocked != null) {
            return blocked;
        }
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

    /**
     * 结构化失败结果（与 {@link #noSandbox} 同一套字段，模型据此自纠而不是盲试）。
     * <p>
     * 刻意保留 {@code success=false} 而不是抛异常：抛出去会被上层当成"工具执行失败"，
     * 模型只看到一句报错、不知道该怎么办；而结构化结果能把「为什么 + 下一步做什么」讲清楚。
     */
    private static Map<String, Object> structuredFailure(String errorCode, String message, String hint) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("success", false);
        failure.put("errorCode", errorCode);
        failure.put("message", message);
        failure.put("hint", hint);
        return failure;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
