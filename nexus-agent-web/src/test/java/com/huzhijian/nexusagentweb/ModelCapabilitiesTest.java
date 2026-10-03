package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.domain.Model;
import com.huzhijian.nexusagentweb.em.ModelType;
import com.huzhijian.nexusagentweb.model.ModelCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelCapabilities} 的纯单测。
 * <p>
 * 覆盖 2026-10-03 新增的模型元数据（vision / contextWindow / maxOutputTokens）：
 * 老配置里没有这三个字段（jsonb 里的旧数据反序列化后为 null），必须兜底成默认值，不能 NPE。
 */
@DisplayName("ModelCapabilities —— 视觉 / 上下文窗口 / 最大输出的默认值与兜底")
class ModelCapabilitiesTest {

    private static Model model(Boolean vision, Integer contextWindow, Integer maxOutput) {
        Model m = new Model();
        m.setName("test-model");
        m.setType(ModelType.CHAT);
        m.setVision(vision);
        m.setContextWindow(contextWindow);
        m.setMaxOutputTokens(maxOutput);
        return m;
    }

    @Test
    @DisplayName("老配置（三个字段全为 null）：默认不支持视觉，256k / 32k")
    void legacyConfigFallsBackToDefaults() {
        ModelCapabilities caps = ModelCapabilities.of(model(null, null, null));

        assertFalse(caps.vision(), "默认必须是不支持视觉 —— 不填就发真图会被上游拒绝");
        assertEquals(256_000, caps.contextWindow());
        assertEquals(32_000, caps.maxOutputTokens());
    }

    @Test
    @DisplayName("显式配置：按填的来")
    void explicitConfig() {
        ModelCapabilities caps = ModelCapabilities.of(model(true, 128_000, 8_192));

        assertTrue(caps.vision());
        assertEquals(128_000, caps.contextWindow());
        assertEquals(8_192, caps.maxOutputTokens());
    }

    @Test
    @DisplayName("填了 0 或负数：当作没填（按默认），不能算出一个负数窗口")
    void nonPositiveFallsBack() {
        assertEquals(256_000, ModelCapabilities.of(model(false, 0, 0)).contextWindow());
        assertEquals(32_000, ModelCapabilities.of(model(false, -1, -5)).maxOutputTokens());
    }

    @Test
    @DisplayName("记忆窗口 = min(全局上限, 上下文窗口 − 输出上限)")
    void memoryWindow() {
        ModelCapabilities caps = ModelCapabilities.of(model(true, 256_000, 32_000));
        assertEquals(224_000, caps.memoryWindow(1_000_000), "256k − 32k = 224k");
        assertEquals(50_000, caps.memoryWindow(50_000), "受全局上限约束");
    }

    @Test
    @DisplayName("配置填错（输出比窗口还大）：保底给最小窗口，不能把历史裁光")
    void invalidConfigKeepsMinimalWindow() {
        ModelCapabilities caps = ModelCapabilities.of(model(false, 4_000, 32_000));
        assertEquals(2_048, caps.memoryWindow(1_000_000));
    }

    @Test
    @DisplayName("模型条目为 null（走系统默认模型）：返回 DEFAULT")
    void nullModel() {
        assertEquals(ModelCapabilities.DEFAULT, ModelCapabilities.of(null));
    }
}
