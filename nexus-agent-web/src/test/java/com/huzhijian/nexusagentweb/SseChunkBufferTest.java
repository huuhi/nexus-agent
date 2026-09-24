package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.converter.SseChunkBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式增量合并的单元测试（P2-12）。
 * <p>
 * 这段逻辑错了的后果很直观：漏发 → 前端丢字；该 flush 时没 flush → 顺序错乱
 * （正文插到工具卡片后面）。所以边界都要守住。
 */
class SseChunkBufferTest {

    private static final String THINK = "THINK";
    private static final String CONTENT = "CONTENT";

    /** 阈值调得很大，测试里靠 drain 或类型切换来触发 */
    private SseChunkBuffer bigBuffer() {
        return new SseChunkBuffer(10_000, 10_000);
    }

    @Test
    @DisplayName("未达任何阈值时不发送（这是减少帧数的关键）")
    void holdsUntilThreshold() {
        SseChunkBuffer buffer = bigBuffer();

        assertNull(buffer.append(CONTENT, "你"));
        assertNull(buffer.append(CONTENT, "好"));
        assertTrue(buffer.hasPending(), "内容应还在缓冲里");
    }

    @Test
    @DisplayName("字符数达到阈值就发送，且内容是完整拼接的")
    void flushesWhenReachingMaxChars() {
        SseChunkBuffer buffer = new SseChunkBuffer(5, 10_000);

        assertNull(buffer.append(CONTENT, "abc"));
        SseChunkBuffer.Batch batch = buffer.append(CONTENT, "de");

        assertNotNull(batch);
        assertEquals(CONTENT, batch.type());
        assertEquals("abcde", batch.text());
        assertFalse(buffer.hasPending());
    }

    @Test
    @DisplayName("超过时间阈值就发送（兜住低速内容，不让它被一直憋着）")
    void flushesOnInterval() throws InterruptedException {
        SseChunkBuffer buffer = new SseChunkBuffer(10_000, 1);
        assertNull(buffer.append(CONTENT, "慢"));

        Thread.sleep(5);
        SseChunkBuffer.Batch batch = buffer.append(CONTENT, "吞吞");

        assertNotNull(batch);
        assertEquals("慢吞吞", batch.text());
    }

    @Test
    @DisplayName("类型切换（思考 → 正文）时先把上一批发出去，保证顺序不乱")
    void flushesOnTypeSwitch() {
        SseChunkBuffer buffer = bigBuffer();
        assertNull(buffer.append(THINK, "我先想想"));

        SseChunkBuffer.Batch flushed = buffer.append(CONTENT, "答案是");

        assertNotNull(flushed, "切换类型时必须把上一批发掉");
        assertEquals(THINK, flushed.type());
        assertEquals("我先想想", flushed.text());
        assertTrue(buffer.hasPending(), "新内容应留在缓冲里等下次判断");
    }

    @Test
    @DisplayName("drain 取出剩余内容（结束/工具事件前必须调用）")
    void drainForcesRemaining() {
        SseChunkBuffer buffer = bigBuffer();
        buffer.append(CONTENT, "尾部内容");

        SseChunkBuffer.Batch batch = buffer.drain();

        assertNotNull(batch);
        assertEquals("尾部内容", batch.text());
        assertNull(buffer.drain(), "drain 之后应已清空");
        assertFalse(buffer.hasPending());
    }

    @Test
    @DisplayName("缓冲为空时 drain 返回 null（调用方无需判断）")
    void drainOnEmptyReturnsNull() {
        assertNull(bigBuffer().drain());
    }

    @Test
    @DisplayName("空字符串 / null 增量被忽略，不产生空帧")
    void ignoresEmptyChunks() {
        SseChunkBuffer buffer = bigBuffer();

        assertNull(buffer.append(CONTENT, null));
        assertNull(buffer.append(CONTENT, ""));
        assertFalse(buffer.hasPending());
    }

    @Test
    @DisplayName("批次是快照：后续 append 不会污染已发出的内容")
    void batchIsSnapshot() {
        SseChunkBuffer buffer = new SseChunkBuffer(3, 10_000);
        SseChunkBuffer.Batch batch = buffer.append(CONTENT, "abc");

        buffer.append(CONTENT, "xyz");

        assertEquals("abc", batch.text());
    }

    @Test
    @DisplayName("阈值配成 0/负数时兜到安全值（否则等于没优化，每块都发）")
    void guardsAgainstSillyConfig() {
        SseChunkBuffer buffer = new SseChunkBuffer(0, 0);

        // 兜底为 200 字符 / 60ms：小块内容不会被立刻发出去
        assertNull(buffer.append(CONTENT, "短"));
        assertTrue(buffer.hasPending());
    }
}
