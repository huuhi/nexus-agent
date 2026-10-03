package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.model.MultimodalTokenCountEstimator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MultimodalTokenCountEstimator} 的纯单测。
 * <p>
 * 背景（2026-10-03）：图片消息从「URL 文本」改成真正的 {@code ImageContent} 后，
 * 原生 {@link OpenAiTokenCountEstimator} 一遇到就抛
 * {@code IllegalArgumentException: Unknown content type}（已实测复现），
 * {@code TokenWindowChatMemory} 裁剪窗口时会直接炸 —— 这就是当年图片只发 URL 文本的原因。
 * 本估算器：文本委托 OpenAI 估算器，图片按固定常数。
 */
@DisplayName("MultimodalTokenCountEstimator —— ImageContent 不再炸，按常数估算")
class MultimodalTokenCountEstimatorTest {

    private static final String MODEL = "gpt-4o";
    private final OpenAiTokenCountEstimator plain = new OpenAiTokenCountEstimator(MODEL);
    private final MultimodalTokenCountEstimator estimator = new MultimodalTokenCountEstimator(MODEL, 1024);

    @Test
    @DisplayName("原生估算器对 ImageContent 确实会炸（锁定改动机cid）")
    void plainEstimatorBreaksOnImage() {
        UserMessage withImage = UserMessage.from(
                TextContent.from("这是谁？"),
                ImageContent.from("https://example.com/a.png"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> plain.estimateTokenCountInMessage(withImage));
    }

    @Test
    @DisplayName("混合消息：不炸，且 = 文本部分 + 1024")
    void mixedMessage() {
        UserMessage mixed = UserMessage.from(
                TextContent.from("这是谁？"),
                ImageContent.from("https://example.com/a.png"));

        int tokens = assertDoesNotThrow(() -> estimator.estimateTokenCountInMessage(mixed));

        int textOnly = estimator.estimateTokenCountInMessage(UserMessage.from(TextContent.from("这是谁？")));
        assertEquals(textOnly + 1024, tokens);
    }

    @Test
    @DisplayName("只有图片（没有文字）：至少按 1024 算")
    void imageOnly() {
        UserMessage imageOnly = UserMessage.from(ImageContent.from("https://example.com/a.png"));
        int tokens = assertDoesNotThrow(() -> estimator.estimateTokenCountInMessage(imageOnly));
        assertEquals(1024, tokens);
    }

    @Test
    @DisplayName("纯文本消息：与原生估算器完全一致")
    void textOnlyMatchesDelegate() {
        UserMessage textOnly = UserMessage.from(TextContent.from("帮我分析这份数据"));
        assertEquals(plain.estimateTokenCountInMessage(textOnly), estimator.estimateTokenCountInMessage(textOnly));
    }

    @Test
    @DisplayName("多条消息列表：逐条累加（含 AI 消息，走委托）")
    void messageList() {
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                UserMessage.from(TextContent.from("第一句")),
                AiMessage.from("好的"),
                UserMessage.from(TextContent.from("看这张图"), ImageContent.from("https://e.com/a.png")));

        int tokens = estimator.estimateTokenCountInMessages(messages);
        assertTrue(tokens > 1024, "至少应包含一张图的 1024");
    }

    @Test
    @DisplayName("多张图片：按张数累加")
    void multipleImages() {
        UserMessage two = UserMessage.from(
                ImageContent.from("https://e.com/a.png"),
                ImageContent.from("https://e.com/b.png"));
        UserMessage one = UserMessage.from(ImageContent.from("https://e.com/a.png"));

        assertEquals(estimator.estimateTokenCountInMessage(one) + 1024,
                estimator.estimateTokenCountInMessage(two));
    }
}
