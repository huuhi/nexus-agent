package com.huzhijian.nexusagentweb.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

import dev.langchain4j.data.message.Content;
import java.util.List;

/**
 * 支持「文本 + 图片」混合内容的 token 估算器。
 * <p>
 * <b>为什么需要它</b>（2026-10-03）：图片消息从「URL 文本」改成了真正的
 * {@code ImageContent} —— 这是让多模态模型真正看到图片的前提（OpenAI 兼容协议
 * 会把它发成 {@code image_url} part）。但 {@link OpenAiTokenCountEstimator}
 * **不认识 ImageContent**，一遇到就抛
 * {@code IllegalArgumentException: Unknown content type}（已实测复现），
 * {@code TokenWindowChatMemory} 在裁剪记忆窗口时会直接炸。
 * 当年代码里"图片只发 URL 文本"就是这个原因 —— 代价是模型永远看不到图。
 * <p>
 * 处理方式：<b>文本部分委托给 OpenAI 估算器，图片部分按固定常数算</b>
 * （常数可配：{@code nexus.agent.memory.image-tokens}，默认 1024）。
 * 这是估算不是精确计费 —— 窗口裁剪只需要「别超太多、也别裁太狠」。
 */
public class MultimodalTokenCountEstimator implements TokenCountEstimator {

    private final OpenAiTokenCountEstimator delegate;
    private final int imageTokens;

    public MultimodalTokenCountEstimator(String estimatorModel, int imageTokens) {
        this.delegate = new OpenAiTokenCountEstimator(estimatorModel);
        this.imageTokens = imageTokens;
    }

    @Override
    public int estimateTokenCountInText(String text) {
        return delegate.estimateTokenCountInText(text);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message instanceof UserMessage userMessage && containsImage(userMessage)) {
            return estimateUserMessage(userMessage);
        }
        return delegate.estimateTokenCountInMessage(message);
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateTokenCountInMessage(message);
        }
        return total;
    }

    private boolean containsImage(UserMessage userMessage) {
        return userMessage.contents().stream().anyMatch(ImageContent.class::isInstance);
    }

    /**
     * 混合消息的估算：文本部分交给委托估算器，图片部分按固定常数。
     * <p>
     * 实现手法：构造一个**只含文本**的同构 UserMessage 让委托器算
     * （这样 role 开销、分隔符等都保持一致），再加上图片常数。
     */
    private int estimateUserMessage(UserMessage userMessage) {
        List<TextContent> textContents = userMessage.contents().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .toList();

        int textTokens = 0;
        if (!textContents.isEmpty()) {
            UserMessage textOnly = UserMessage.from(textContents.toArray(new Content[0]));
            textTokens = delegate.estimateTokenCountInMessage(textOnly);
        }
        long imageCount = userMessage.contents().stream()
                .filter(ImageContent.class::isInstance)
                .count();
        return textTokens + (int) imageCount * imageTokens;
    }
}
