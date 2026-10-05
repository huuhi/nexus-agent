package com.huzhijian.nexusagentweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huzhijian.nexusagentweb.config.JacksonConfig;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.BatchDeleteFileDTO;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.vo.BatchDeleteResultVO;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import com.huzhijian.nexusagentweb.vo.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 雪花 ID 精度回归测试（2026-10-05）。
 * <p>
 * <b>线上现象</b>：「文件与产物」面板里<b>每一行</b>点删除都返回
 * {@code {"code":1,"msg":"文件不存在或无权限删除"}}。
 * <p>
 * <b>真凶不是 bizType（那是上午那一轮的误判），而是 JS 的 number 存不下 19 位雪花 ID。</b>
 * {@code SysFile.id} 用 {@code IdType.ASSIGN_ID} 生成，是19 位十进制数；而
 * {@code JSON.parse} 产出的是 IEEE754 double，精确整数上限 {@code Number.MAX_SAFE_INTEGER}
 * 只有 16 位（9007199254740991），<b>超出部分直接被抹掉</b>：
 * <pre>{@code
 *   库里真实 id       = 2107069112529358849
 *   浏览器 JSON.parse = 2107069112529358848    // 差 1
 * }</pre>
 * 前端拿这个被改写的 id 拼URL 回传，后端 {@code WHERE id = ?} 必然落空。
 * <p>
 * <h3>为什么这个测试必须起真实 Spring 容器（L2 层）</h3>
 * {@code JacksonConfig} 是<b>配置类</b>：写错了、Bean 没注册、类型没覆盖全，
 * <b>编译期和纯 mock 单测都不会报错</b>，只有真实装配出来的
 * {@code ObjectMapper} 才能反映线上行为。所以这里用
 * {@link ApplicationContextRunner} 拉起带 {@link JacksonAutoConfiguration} 的真实容器，
 * 拿到容器里那个真正被Spring MVC 使用的 mapper 来断言 ——
 * 这与 {@code RuntimeConfigBindingTest} 是同一套范式。
 * <p>
 * <h3>断言方式：真的模拟一遍 JS</h3>
 * 断言「序列化成字符串」只是手段，真正的判据是
 * <b>经过 JS 语义之后还能还原成同一个 id</b>。见 {@link #roundTripThroughJs}：
 * 它按 JSON 里是带引号字符串还是裸数字，走<b>两条不同的路径</b> ——
 * 裸数字那条真的经过 {@code double}，精度会在那一步丢掉。
 */
@DisplayName("雪花 ID 精度 —— Long 必须序列化成字符串（文件删除失效的真凶）")
class SnowflakeIdPrecisionTest {

    /** JavaScript 的 Number.MAX_SAFE_INTEGER，也是能精确表示的整数上限 */
    private static final long JS_MAX_SAFE_INTEGER = 9007199254740991L;

    /**
     * 一个真实形状的 19 位雪花 ID。
     * <p>
     * 取自实际位布局：{@code (毫秒时间戳 - 2010-01-01) << 22 | workerId << 12 | 序列}。
     * 刻意<b>选一个低位非 0 的奇数</b>——低 12 位是序列号，
     * 挑奇数才能确保「被 double 抹掉后一定变样」，不会误判为通过。
     */
    private static final long SNOWFLAKE_ID = 2107069112529358849L;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    @DisplayName("前提校验：该 id 确实超出 JS 安全整数 —— 否则本类全部断言都是自欺欺人")
    void testIdExceedsJsSafeInteger() {
        assertThat(SNOWFLAKE_ID)
                .as("前提校验：这个 id 必须大于 2^53-1，否则精度丢失根本不会发生")
                .isGreaterThan(JS_MAX_SAFE_INTEGER);
        assertThat(String.valueOf(SNOWFLAKE_ID)).hasSize(19);
    }

    @Test
    @DisplayName("元测试：裸数字经 JS 往返必然丢精度 —— 证明本类真的能测出问题")
    void rawNumberLosesPrecisionInJs() {
        // 这是整个测试类的「有效性证明」：它证明 roundTripThroughJs 真的会走 IEEE754
        // 并丢掉低位。首版实现用的是 BigDecimal.longValueExact()（精确算术），
        // 裸数字走它也「无损」→ 测试全绿但什么都没测到。有了这条，元测试立刻变红。
        String rawNumberJson = "{\"id\":" + SNOWFLAKE_ID + "}";

        assertThat(roundTripThroughJs(rawNumberJson, "id"))
                .as("如果这条也过了，说明模拟 JS 的逻辑退化成精确算术了，"
                        + "整类测试都失去意义 —— 必须让它红")
                .isNotEqualTo(SNOWFLAKE_ID);
        // 只检查**值**部分没引号 —— `{"id":` 这个 key 本身就带引号，
        // 断言整串不含引号是自己写错（第一版就踩了，害得元测试红了一次）。
        assertThat(extractRawValue(rawNumberJson, "id"))
                .as("值必须是裸数字，才能走 double 路径")
                .doesNotStartWith("\"");
    }

    @Test
    @DisplayName("元测试：字符串形态经 JS 往返精确无损 —— 两个方向一起才构成证明")
    void rawStringSurvivesJsUnchanged() {
        String quotedJson = "{\"id\":\"" + SNOWFLAKE_ID + "\"}";

        assertThat(roundTripThroughJs(quotedJson, "id"))
                .as("字符串形态必须原样回来，否则修复无效")
                .isEqualTo(SNOWFLAKE_ID);
    }

    @Test
    @DisplayName("SysFile.id 序列化成字符串：前端拿到原文，回传必能查到")
    void sysFileIdIsSerializedAsString() {
        ObjectMapper mapper = resolveMapper();

        SysFile file = SysFile.builder()
                .id(SNOWFLAKE_ID)
                .userId(7L)
                .fileName("报告.pdf")
                .bizType(BizType.ARTIFACT)
                .build();

        String json = assertDoesNotThrowSerialize(mapper, file);

        assertThat(json)
                .as("id 必须带引号，是字符串而不是裸数字")
                .contains("\"id\":\"" + SNOWFLAKE_ID + "\"");

        // 关键断言：模拟 JS 拿到这个字符串再拼回 URL，后端能否还原出同一个 id
        assertThat(roundTripThroughJs(json, "id"))
                .as("字符串形态的 id 经过 JS 往返后必须精确无损")
                .isEqualTo(SNOWFLAKE_ID);
    }

    @Test
    @DisplayName("KnowledgeFileVO.id（文件列表用的 VO）同样是字符串")
    void knowledgeFileVoIdIsSerializedAsString() {
        ObjectMapper mapper = resolveMapper();

        // KnowledgeFileVO 只有 @AllArgsConstructor，没有无参构造 —— 用全参构造
        KnowledgeFileVO vo = new KnowledgeFileVO(
                SNOWFLAKE_ID,                 // id
                "https://bucket.oss-cn-guangzhou.aliyuncs.com/file/a.pdf", // fileUrl
                "a.pdf",                      // fileName
                1024L,                        // fileSize
                null,                         // failReason
                null,                         // uploadStatus
                null,                         // createTime
                BizType.CHAT,                 // bizType
                "PDF");                       // extension

        String json = assertDoesNotThrowSerialize(mapper, vo);
        assertThat(json)
                .as("文件列表接口的 id 必须也是字符串，否则前端拿去删除照样丢精度")
                .contains("\"id\":\"" + SNOWFLAKE_ID + "\"");
    }

    @Test
    @DisplayName("反向：字符串形态的 id 能被反序列化回 Long —— 前端不需要 parseInt")
    void stringIdIsDeserializedBackToLong() {
        ObjectMapper mapper = resolveMapper();

        String json = "{\"ids\":[\"" + SNOWFLAKE_ID + "\"]}";
        BatchDeleteFileDTO dto = assertDoesNotThrowDeserialize(mapper, json, BatchDeleteFileDTO.class);

        assertThat(dto.getIds())
                .as("前端原样回传字符串 id，后端必须能接住—— 这是批量删除能用的前提")
                .containsExactly(SNOWFLAKE_ID);
    }

    @Test
    @DisplayName("Result.total 等包装 Long 也变字符串，但 code 保持数字（前端 code!==0 的判断不能失效）")
    void integerFieldsAreNotAffected() {
        ObjectMapper mapper = resolveMapper();

        String json = assertDoesNotThrowSerialize(mapper, Result.ok("ok", List.of("x")));
        assertThat(json)
                .as("Integer 不许被改，否则前端 `res.code !== 0` 这类判断会静默失效")
                .contains("\"code\":0");

        String errJson = assertDoesNotThrowSerialize(mapper, Result.error("文件不存在或无权限删除"));
        assertThat(errJson)
                .as("错误码必须是数字，错误文案原样透出")
                .contains("\"code\":1")
                .contains("文件不存在或无权限删除");
    }

    @Test
    @DisplayName("批量删除结果里的 id 也是字符串，与列表接口形态一致（前端可直接比对）")
    void batchDeleteResultIdsAreStrings() {
        ObjectMapper mapper = resolveMapper();

        BatchDeleteResultVO result = new BatchDeleteResultVO(
                2, 1, 1, List.of(String.valueOf(SNOWFLAKE_ID)), List.of("2107069112529358851"));

        String json = assertDoesNotThrowSerialize(mapper, result);

        assertThat(json)
                .as("deletedIds / failedIds 里的 id 形态必须与列表接口的 id 完全一致")
                .contains("\"deletedIds\":[\"" + SNOWFLAKE_ID + "\"]")
                .contains("\"failedIds\":[\"2107069112529358851\"]");
    }

    // ---------------------------------------------------------------- 工具方法

    /**
     * 从真实 Spring 容器里取出 {@code ObjectMapper}。
     * <p>
     * 断言容器<b>确实</b>产出了 mapper：如果 {@code JacksonConfig} 的 Bean 定义写坏了，
     * 这里会直接失败，而不是悄悄用一个裸new 的 mapper 让测试变成自欺欺人。
     */
    private ObjectMapper resolveMapper() {
        // ApplicationContextRunner#run 的 Consumer 回调返回 void，
        // 所以在 lambda 里做断言，拿到 mapper 后赋给局部变量带出来。
        ObjectMapper[] holder = new ObjectMapper[1];
        runner.run(context -> {
            assertThat(context)
                    .as("容器必须能正常启动；配置类写错时这里会直接失败，"
                            + "而不是悄悄用一个裸 new 的 mapper 让测试变成自欺欺人")
                    .hasNotFailed();
            assertThat(context).hasSingleBean(ObjectMapper.class);
            holder[0] = context.getBean(ObjectMapper.class);
        });
        return holder[0];
    }

    private static String assertDoesNotThrowSerialize(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError("序列化失败：" + e.getMessage(), e);
        }
    }

    private static <T> T assertDoesNotThrowDeserialize(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new AssertionError("反序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 模拟「浏览器 {@code JSON.parse} 拿到值 → 前端拼进 URL → 后端收到」的完整链路，
     * 返回前端最终会发出去的那个 id。
     * <p>
     * <b>这里是整个测试的关键，也是最容易写错的地方。</b>要分两种情况：
     * <ul>
     *   <li>JSON 里是<b>带引号的字符串</b>（修复后）：JS 里就是 string，
     *       插进 URL 时原样输出 —— 精度天然无损；</li>
     *   <li>JSON 里是<b>裸数字</b>（修复前）：JS 里是 number，
     *       {@code JSON.parse} 会把它读成 IEEE754 double，超出 2^53 的低位被抹掉，
     *       再 {@code String(num)} 拼进 URL 就成了另一个 id。</li>
     * </ul>
     * 所以这里<b>必须真的走一次 double</b>。用 {@code BigDecimal.longValueExact()}
     * 是错的—— 那是精确算术，裸数字走它也「无损」，等于没测。
     * 正确做法是先 {@code doubleValue()}（IEEE754 截断）再转回 long，
     * 与 JS 的 {@code Number} → {@code String} 语义一致。
     */
    private static long roundTripThroughJs(String json, String field) {
        String raw = extractRawValue(json, field);
        boolean wasJsonString = raw.startsWith("\"");
        String literal = wasJsonString ? raw.substring(1, raw.length() - 1) : raw;

        if (wasJsonString) {
            // JS 里是 string：String(s) 就是它本身，不经过任何数值转换
            return Long.parseLong(literal);
        }
        // JS 里是 number：JSON.parse 先读成 double，再 String(num) 拼进 URL。
        // doubleValue() 就是那个「读成 double」的动作 —— 精度就在这一步丢掉。
        return (long) Double.parseDouble(literal);
    }

    /**
     * 从 JSON 里抠出某个字段的<b>原始字面量</b>（保留引号）。
     * <p>
     * 刻意手写而不是用 Jackson 解析：Jackson 会把数字读成 {@code long}、
     * 把字符串读成 {@code String}，精度问题在这一步就被抹平了，
     * 恰好把要测的东西测没了。这里要的是「线上传输的字节」。
     */
    private static String extractRawValue(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) {
            throw new AssertionError("JSON 里找不到字段 " + field + "：" + json);
        }
        int start = idx + key.length();

        // ⚠️ substring(start, end) **不含** end 位置 —— 若end 停在闭引号上，
        // 那个引号会被切掉，于是「字符串」被误当成「裸数字」，
        // 再走 double 路径就凭空少掉一位（2107...849 变成 2107...84）。
        // 所以闭引号要**额外 +1** 才能把整串字面量（含引号）取全。
        if (json.charAt(start) == '"') {
            int closing = json.indexOf('"', start + 1);
            if (closing < 0) {
                throw new AssertionError("JSON 里字段 " + field + " 的字符串没有闭合：" + json);
            }
            return json.substring(start, closing + 1);
        }

        // 裸数字：读到逗号或对象结束为止
        int end = start;
        while (end < json.length()
                && json.charAt(end) != ','
                && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end).trim();
    }
}
