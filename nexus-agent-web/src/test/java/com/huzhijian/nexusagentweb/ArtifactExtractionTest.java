package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 产物识别的单元测试（P2-10）。
 * <p>
 * {@code extractArtifact} 决定了「工具结果算不算一个产物」—— 判错会有实际后果：
 * 漏判 → 用户看不到下载卡片（文件其实已生成）；误判 → 给前端推一个假的产物事件。
 */
class ArtifactExtractionTest {

    @Test
    @DisplayName("publish_artifact 的成功结果能被识别，字段完整取出")
    void extractsArtifactFromToolResult() {
        String result = """
                {"success":true,"message":"已把「季度报表.xlsx」发布为交付物，用户可直接下载。",
                 "artifact":{"name":"季度报表.xlsx","url":"https://oss.example.com/user/1/artifact/2026-09-24/x.xlsx",
                 "sourcePath":"/home/x.xlsx","extension":"xlsx","size":2048}}""";

        Map<String, Object> artifact = ChatServiceImpl.extractArtifact(result);

        assertNotNull(artifact);
        assertEquals("季度报表.xlsx", artifact.get("name"));
        assertEquals("xlsx", artifact.get("extension"));
        assertEquals(2048, artifact.get("size"));
    }

    @Test
    @DisplayName("普通工具结果（沙盒命令输出等）不误判为产物")
    void ignoresOrdinaryToolResults() {
        assertNull(ChatServiceImpl.extractArtifact("{\"success\":true,\"stdout\":\"total 4\\n-rw-r--r-- 1\"}"));
        assertNull(ChatServiceImpl.extractArtifact("[{\"name\":\"a.txt\"}]"));
        assertNull(ChatServiceImpl.extractArtifact("文件已保存到 /home/x.txt"));
    }

    @Test
    @DisplayName("结果里提到 artifact 字样但不是 JSON 结构时不误判")
    void ignoresMentionsWithoutArtifactObject() {
        // 模型可能把 "artifact" 写进普通文本输出里，不能只靠 contains 判断
        assertNull(ChatServiceImpl.extractArtifact("{\"success\":true,\"message\":\"artifact 是产物\"}"));
    }

    @Test
    @DisplayName("空值 / 非法 JSON 不抛异常（不能让主流程挂掉）")
    void handlesBadInputSafely() {
        assertNull(ChatServiceImpl.extractArtifact(null));
        assertNull(ChatServiceImpl.extractArtifact(""));
        assertNull(ChatServiceImpl.extractArtifact("   "));
        assertNull(ChatServiceImpl.extractArtifact("{\"artifact\": 这不是JSON"));
    }

    @Test
    @DisplayName("失败的产物结果不会走到这里；但真出现时不带 artifact 结构也不会误判")
    void failureResultHasNoArtifact() {
        String failure = """
                {"success":false,"errorCode":"EMPTY_RESPONSE","message":"沙盒服务没有返回下载链接（url 为空）",
                 "hint":"不要重复调用同一参数"}""";

        assertNull(ChatServiceImpl.extractArtifact(failure));
    }
}
