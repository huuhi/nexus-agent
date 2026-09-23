package com.huzhijian.nexusagentweb.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/6/16
 * 说明: 工具执行的安全包装：**永远不抛异常**，把失败转成结构化结果交回模型。
 * <p>
 * 为什么必须结构化：模型看到 {@code {"error":"..."}} 只能猜，无法自纠。而且原实现有三个缺陷：
 * <ol>
 *   <li>{@code Map.of("error", e.getMessage(), "success", false)} —— 当异常没有 message 时
 *       {@code getMessage()} 返回 null，而 {@code Map.of} 不接受 null，
 *       **错误处理自身会抛 NPE**，把「工具失败」升级成「整个请求失败」。</li>
 *   <li>完全没有日志：工具失败了运维侧看不到，只能等用户来反馈。</li>
 *   <li>把原始异常信息（可能很长、含栈信息）整段塞给模型，浪费 token 且读不懂。</li>
 * </ol>
 * <p>
 * 现在的返回形状（扁平结构，便于模型理解；保留 {@code success} 字段，与沙盒服务的返回风格一致）：
 * <pre>
 * { "success": false, "errorCode": "SERVICE_UNREACHABLE", "message": "简洁原因", "hint": "给模型的自纠建议" }
 * </pre>
 */
@Slf4j
@Component
public class SafeExecuteToolHandler {

    /** 外部服务连不上（沙盒未启动、地址不对） */
    public static final String CODE_SERVICE_UNREACHABLE = "SERVICE_UNREACHABLE";
    /** 外部调用超时 */
    public static final String CODE_TIMEOUT = "TIMEOUT";
    /** 请求参数不合法（4xx） */
    public static final String CODE_BAD_REQUEST = "BAD_REQUEST";
    /** 下游服务内部错误（5xx） */
    public static final String CODE_UPSTREAM_ERROR = "UPSTREAM_ERROR";
    /** 本地参数校验失败 */
    public static final String CODE_BAD_PARAMETER = "BAD_PARAMETER";
    /** 外部服务返回空响应 */
    public static final String CODE_EMPTY_RESPONSE = "EMPTY_RESPONSE";
    /** 未归类 */
    public static final String CODE_UNKNOWN = "UNKNOWN";

    /** 给模型的提示语，按错误码区分，目的是让它知道「该怎么补救」而不是盲目重试 */
    private static final Map<String, String> HINTS = Map.of(
            CODE_SERVICE_UNREACHABLE, "外部服务不可达。不要重复重试；请告知用户相关服务未启动或地址配置有误。",
            CODE_TIMEOUT, "外部调用超时。可换更小的任务重试一次；再次失败请改变方案，不要重复同一请求。",
            CODE_BAD_REQUEST, "请求参数不合法。请检查参数格式（例如路径需以 / 开头、ID 不能为空）后修正再试。",
            CODE_UPSTREAM_ERROR, "下游服务报错。不要重复重试同一请求，请换一种方式或告知用户。",
            CODE_BAD_PARAMETER, "参数校验失败。请检查参数是否缺失或格式错误。",
            CODE_EMPTY_RESPONSE, "外部服务返回了空响应。不要重复重试，请换一种方式或告知用户。",
            CODE_UNKNOWN, "未知错误。禁止重复调用同一工具，请换方案或告知用户。"
    );

    /**
     * 包装返回 Map 的工具。
     *
     * @param toolName 工具名，仅用于日志定位（不作为参数传给外部服务）
     */
    public Map<String, Object> mapTool(String toolName, Supplier<Map<String, Object>> supplier) {
        try {
            Map<String, Object> result = supplier.get();
            return result == null ? failurePayload(toolName, CODE_EMPTY_RESPONSE, "外部服务返回空响应", null) : result;
        } catch (Exception e) {
            return failurePayload(toolName, e);
        }
    }

    /**
     * 包装返回 List 的工具（如列目录）。
     */
    public List<Map<String, Object>> listTool(String toolName, Supplier<List<Map<String, Object>>> supplier) {
        try {
            List<Map<String, Object>> result = supplier.get();
            return result == null
                    ? List.of(failurePayload(toolName, CODE_EMPTY_RESPONSE, "外部服务返回空响应", null))
                    : result;
        } catch (Exception e) {
            return List.of(failurePayload(toolName, e));
        }
    }

    /**
     * 包装返回字符串的工具。
     * <p>
     * 失败时返回**紧凑 JSON 文本**，形状与 Map 版本一致，便于模型统一理解。
     */
    public String stringTool(String toolName, Supplier<String> supplier) {
        try {
            String result = supplier.get();
            return result == null ? toJson(failurePayload(toolName, CODE_EMPTY_RESPONSE, "外部服务返回空响应", null)) : result;
        } catch (Exception e) {
            return toJson(failurePayload(toolName, e));
        }
    }

    private Map<String, Object> failurePayload(String toolName, Exception e) {
        return failurePayload(toolName, classify(e), safeMessage(e), e);
    }

    private Map<String, Object> failurePayload(String toolName, String code, String message, Exception e) {
        if (e == null) {
            log.warn("工具 [{}] 执行失败 code={} message={}", toolName, code, message);
        } else {
            // 带堆栈，运维侧才能定位；模型只看到上面的 message
            log.warn("工具 [{}] 执行失败 code={} message={}", toolName, code, message, e);
        }
        // 必须用 LinkedHashMap：Map.of 不接受 null 值，用它就会重演「错误处理自身抛 NPE」的老问题
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("errorCode", code);
        payload.put("message", message);
        payload.put("hint", HINTS.getOrDefault(code, HINTS.get(CODE_UNKNOWN)));
        return payload;
    }

    /**
     * 把异常归类成稳定的错误码。
     * <p>
     * 实现要点：
     * <ul>
     *   <li>**沿 cause 链逐层查找** —— 上游框架常把根因包一层
     *       （如 WebClient 的连接失败被包成 WebClientRequestException，
     *       真正的 ConnectException 在 cause 里），只看最外层会误判成 UNKNOWN。</li>
     *   <li>优先按异常类型判断（不随上游文案变化），类型都判断不出时才用关键字兜底。</li>
     * </ul>
     */
    private static String classify(Throwable e) {
        String keywordCode = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException responseException) {
                return responseException.getStatusCode().is4xxClientError() ? CODE_BAD_REQUEST : CODE_UPSTREAM_ERROR;
            }
            if (t instanceof WebClientRequestException || t instanceof ConnectException) {
                return CODE_SERVICE_UNREACHABLE;
            }
            if (t instanceof TimeoutException) {
                return CODE_TIMEOUT;
            }
            if (t instanceof IllegalArgumentException) {
                return CODE_BAD_PARAMETER;
            }
            if (keywordCode == null) {
                String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                if (msg.contains("connection refused") || msg.contains("failed to connect")
                        || msg.contains("unknownhost") || msg.contains("connect timed out")) {
                    keywordCode = CODE_SERVICE_UNREACHABLE;
                } else if (msg.contains("timeout") || msg.contains("timed out")) {
                    keywordCode = CODE_TIMEOUT;
                }
            }
            if (t.getCause() == t) {
                break; // 防御异常自引用导致的死循环
            }
        }
        return keywordCode == null ? CODE_UNKNOWN : keywordCode;
    }

    /**
     * 取一个「对模型有意义」的简短原因：兜底 null、压缩空白、限制长度，
     * 避免把长堆栈塞进模型上下文。
     */
    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        message = message.strip().replaceAll("\\s+", " ");
        int max = 300;
        return message.length() <= max ? message : message.substring(0, max) + "...";
    }

    private static String toJson(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        payload.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(k).append("\":\"").append(escape(String.valueOf(v))).append('"');
        });
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
