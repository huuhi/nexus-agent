package com.huzhijian.nexusagentweb.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.vo.AttachedFileVO;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import com.huzhijian.nexusagentweb.vo.QuotaVO;
import com.huzhijian.nexusagentweb.vo.Result;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;

/**
 * JSON 序列化配置：把 {@code Long} / {@code long} 序列化成<b>字符串</b>（2026-10-05）。
 * <p>
 * <b>这不是「风格偏好」，是修一个必然发生的线上故障。</b>
 * <p>
 * <h3>为什么</h3>
 * 本项目所有表主键都由 {@code MyBatis-Plus} 生成：显式 {@code IdType.ASSIGN_ID} 的
 * （{@code SysFile}）是<b>雪花 ID</b>，未声明的走全局默认策略，同样是雪花 ID ——
 * 也就是说 {@code sys_file / sys_user / user_memory / mcp_information} 的
 * {@code id} 都是 <b>19 位十进制数</b>，例如 {@code 2107069112529358849}。
 * <p>
 * 而 JavaScript 的 {@code number} 是 <b>IEEE754 双精度浮点</b>，能精确表示的整数上限是
 * {@code Number.MAX_SAFE_INTEGER = 9007199254740991}（<b>16 位</b>）。
 * 一旦 JSON 里出现裸数字，{@code JSON.parse} 会把它读成 double，
 * <b>超出 2^53 的低位直接被抹掉</b>：
 * <pre>{@code
 *   库里真实 id      = 2107069112529358849
 *   浏览器 JSON.parse = 2107069112529358848   // 差 1
 * }</pre>
 * 实测抽样 4000 个雪花 ID，<b>99.6% 会被改写</b>。
 * <p>
 * <h3>为什么表现成「文件删不掉」</h3>
 * 删除是<b>按 id 回传</b>的操作，链路是
 * {@code 后端返回 id → 浏览器 double → 前端拼 URL → 后端按 id 查库}。
 * id 在浏览器里已经变了，后端 {@code WHERE id = ?} 必然落空 ——
 * 这就是那个「每一行点删除都报文件不存在或无权限删除」的根本原因，
 * <b>与 {@code bizType} 无关</b>（2026-10-05 上午那一轮已把
 * {@code bizType=ARTIFACT} 的多余限制移除，照旧全失败，正是因为真凶在这里）。
 * 同理，<b>凡是把 id 拿去改 / 删的接口都会中招</b>：技能、用户记忆、MCP、聊天记录…
 * <p>
 * <h3>为什么全局配而不是逐个字段加 {@code @JsonSerialize}</h3>
 * 逐个加注解治标：新增一个实体忘了加，同样的 bug 换个接口复现一次。
 * 主键是「跨表统一的一等公民」，在<b>序列化层</b>一次性兜住，才是根治。
 * <p>
 * <h3>对前端的影响（必须知道）</h3>
 * <ul>
 *   <li>所有 {@code Long} 字段现在都是<b>带引号的字符串</b>，包括
 *       {@code id}、{@code userId}、{@code fileSize}、{@code total}、{@code seq}；</li>
 *   <li><b>路径参数照收不误</b>：{@code DELETE /api/file/"2107..."}（带引号）由
 *       Spring 自动剥引号转回 {@code Long}，所以前端<b>不要自己拼引号</b>，
 *       直接把字符串插进 URL 即可；</li>
 *   <li>非空判断用 {@code == null}（字符串形式）；<b>不要</b>用
 *       {@code if (id)} 之外的真值假设，也不要对 id 做算术运算。</li>
 * </ul>
 * <b>注意</b>：请求方向<b>不受影响</b> —— Jackson 反序列化时字符串→Long 是标准行为。
 *
 * @see <a href="https://github.com/FasterXML/jackson-databind">jackson-databind</a>
 */
@Configuration
public class JacksonConfig {

    /**
     * 以 <b>字段类型</b> 为准注册序列化器，覆盖包装类型 {@code Long} 与原始类型
     * {@code long}（两者必须分别注册，{@code long} 是独立的 Class 对象）。
     * <p>
     * 刻意<b>不碰</b> {@code Integer / int}：状态码、分页条数、HTTP 状态这些
     * 本来就远小于 2^53，序列化成字符串只会给前端平白添噪音
     * （比如 {@code res.code !== 0} 这种判断会静默失效）。<b>只治 Long。</b>
     * <p>
     * 用 {@code Jackson2ObjectMapperBuilderCustomizer} 而不是直接
     * {@code @Bean ObjectMapper}：后者会<b>替换</b> Spring Boot 自动配置的那个
     * mapper，把 Boot 的一堆默认设置（时间格式、{@code FAIL_ON_UNKNOWN_PROPERTIES} 等）
     * 全部清空，牵连面极大。
     * <p>
     * 同理不用 {@code modulesToInstall(SimpleModule)}：它按模块顺序生效，
     * 排在后面的模块能把前面的覆盖掉，行为依赖装配顺序，不够显式。
     * {@code serializerByType} 是「按类型直接绑定」，没有顺序歧义。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);

            // 🔴 2026-10-08：上面那条规则是「一刀切」，它会把**计量值**也算进去。
            // 下面用 mixin 把「本来就是数字、必须保持数字」的字段精确挑回来 ——
            // 注意方向：**默认仍是字符串（安全），只有显式列出的字段才是数字**。
            //
            // 为什么不反过来（默认数字、只给 id 加保护）：那样漏标的后果是
            // 「某个 id 变成裸数字 → 19 位精度被 JS 抹掉 → 那个接口的 id 操作静默失效」，
            // 正是这个类要防的那类故障，且完全静默。而当前方向漏标的后果只是
            // 「某个计量值还是字符串」—— 前端本来就声明成 string | number 双兼容，不会坏。
            // **让失败模式落在"安全"那一侧。**
            //
            // 为什么不在 domain 的字段上直接加 @JsonSerialize：
            // ① domain 模块只依赖 jackson-annotations，没有 databind（LongAsNumberSerializer 需要的接口在 databind 里）；
            // ② 形态是**序列化层**的策略，集中在唯一事实源这里，读这一个文件就知道全貌。
            mixins().forEach(builder::mixIn);
        };
    }

    /**
     * Mixin 注册表：<b>目标类 → mixin 类</b>。
     * <p>
     * 抽成 static 方法是为了让<b>护栏测试与运行时配置共用同一份事实源</b> ——
     * 否则测试里再抄一份映射，就又出现「两处各写一遍、改一处忘一处」的老问题
     * （这正是本次 `context*` 字段事故的形态）。
     *
     * @see LongFieldsAsNumber
     */
    public static Map<Class<?>, Class<?>> mixins() {
        return Map.of(
                SysFile.class, LongFieldsAsNumber.ForSysFile.class,
                KnowledgeFileVO.class, LongFieldsAsNumber.ForKnowledgeFileVO.class,
                AttachedFileVO.class, LongFieldsAsNumber.ForAttachedFileVO.class,
                QuotaVO.class, LongFieldsAsNumber.ForQuotaVO.class,
                User.class, LongFieldsAsNumber.ForUser.class,
                Result.class, LongFieldsAsNumber.ForResult.class);
    }

    /**
     * 把 {@code Long} 写成**裸 JSON number** 的序列化器。
     * <p>
     * 🔴 <b>为什么不用 Jackson 自带的 {@code NumberSerializer}</b>：它<b>没有无参构造器</b>
     * （只接受 {@code Class<? extends Number>} 做参数），而 {@code @JsonSerialize(using = …)}
     * 要求 Jackson 能反射实例化。直接引用会在<b>运行时</b>抛
     * <pre>InvalidDefinitionException: Class …NumberSerializer has no default (no arg) constructor</pre>
     * —— 写这批断言时实测踩到，编译期完全看不出来。
     * <p>
     * 这个类的作用是<b>抵消类型级规则</b>：{@code serializerByType(Long, ToStringSerializer)}
     * 是类型级绑定，而 {@code @JsonSerialize(using = …)} 是属性级，优先级更高 ——
     * 所以这里直接 {@code gen.writeNumber} 就不会再被那条规则拦一道。
     */
    public static class LongAsNumberSerializer extends JsonSerializer<Long> {
        @Override
        public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
            } else {
                // 这些字段（体积 / 条数 / 配额）都在 2^53 以内，前端 JSON.parse 不会丢精度
                gen.writeNumber(value.longValue());
            }
        }
    }

    /**
     * 「这些 {@code Long} 字段是计量值，不是主键，必须序列化成 JSON number」的集中声明。
     * <p>
     * 2026-10-08 由 frontend 按前端类型定义逐条确认后给出，**一次改齐这一批**：
     *
     * <table border="1">
     *   <tr><th>字段</th><th>位置</th><th>为什么是数字</th></tr>
     *   <tr><td>{@code fileSize}</td><td>文件列表 / 附件</td><td>前端要格式化体积、算进度</td></tr>
     *   <tr><td>{@code fileQuota}/{@code fileUsed}/{@code fileRemaining}</td>
     *       <td>配额接口、上传被拒的 error data</td><td>前端要做「已用 X / 上限 Y」的算术</td></tr>
     *   <tr><td>{@code total}</td><td>{@code Result} 信封（分页）</td><td>分页算术</td></tr>
     *   <tr><td>{@code quota}/{@code used}/{@code remaining}</td><td>配额接口（token）</td><td>同上</td></tr>
     * </table>
     * <p>
     * ⚠️ <b>{@code id} 类字段一个都不在这里</b> —— {@code SysFile.id}、{@code KnowledgeFileVO.id}、
     * {@code userId} 等必须继续是字符串（19 位雪花 ID 超出 JS 的 {@code 2^53} 安全整数，
     * 裸数字过一遍 {@code JSON.parse} 低位就被抹掉）。这正是上一轮
     * 「文件删不掉」事故的根因，别为了顺手而放松。
     * <p>
     * ⚠️ <b>新增 {@code Long} 字段时的自检</b>：问一句「它是主键，还是计量值？」
     * 主键 → 什么都不用做（默认就是字符串，安全）；
     * 计量值 → 加到下面某个 mixin 里，并补一条 {@code NumberFieldSerializationTest} 断言。
     * 忘了也不会静默出错 —— 前端拿到字符串会立刻看见。
     * <p>
     * 用 mixin 而不是给字段加注解：mixin 只声明「要覆盖哪个属性」，其余属性照常走类定义，
     * 所以不会影响本类的其他字段。抽象方法名必须与实体 getter 完全一致
     * （Lombok {@code @Data} 生成 {@code getFileSize()} → 这里写 {@code getFileSize()}）。
     */
    public static final class LongFieldsAsNumber {

        private LongFieldsAsNumber() {
        }

        /** {@code SysFile}：{@code fileSize} 是字节数，前端要算体积。{@code id}/{@code userId} 不动。 */
        public abstract static class ForSysFile {
            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getFileSize();
        }

        /** {@code KnowledgeFileVO}：同上。{@code id} 保持字符串。 */
        public abstract static class ForKnowledgeFileVO {
            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getFileSize();
        }

        /**
         * {@code QuotaVO}：token 与文件配额全是「数量」。
         * <p>
         * ⚠️ 这几个不许改 Java 类型成 {@code int} —— 用序列化层指定就够了，
         * 动类型要连带改所有调用方，而配额口径以后可能放宽。
         */
        public abstract static class ForQuotaVO {
            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getQuota();

            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getUsed();

            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getRemaining();

            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getFileQuota();

            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getFileUsed();

            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getFileRemaining();
        }

        /** {@code Result}：分页 {@code total}，前端要做分页算术。{@code code} 是 Integer，本来就不受影响。 */
        public abstract static class ForResult {
            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getTotal();
        }

        /**
         * {@code AttachedFileVO}：附件体积，随<b>历史消息</b>下发给前端。
         * <p>
         * 🔴 2026-10-08 第二轮补漏：frontend 的清单里写了 `AttachedFile.fileSize`，
         * 而我第一遍只改了 `SysFile` / `KnowledgeFileVO`，**漏了这一个** ——
         * 它是独立的一个 VO（历史消息里的附件），不在文件列表那条链路上。
         * 这正是「同一个语义散在多个类里」的代价，也是下面
         * {@code LongFieldClassificationTest} 要防的东西。
         */
        public abstract static class ForAttachedFileVO {
            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getFileSize();
        }

        /**
         * {@code User}：本轮的 token 配额 / 已用量 / 文件配额。
         * <p>
         * ⚠️ 这几个字段<b>目前不下发给前端</b>（`/api/user/login` 与 `/register` 只返回 token 字符串，
         * 配额查询走 {@code QuotaVO}）。加上是因为它们是**配额口径的数字**，与 {@code QuotaVO} 同源 ——
         * 哪天有人图省事直接返回 {@code User} 实体，形态必须已经是对的，
         * 而不是等前端报「配额算出 NaN」再回头查。
         * <p>
         * ⚠️ {@code User.id} <b>不在</b>这里（它是雪花主键，必须保持字符串）。
         */
        public abstract static class ForUser {
            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getTokenQuota();

            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getTokenUsed();

            @JsonSerialize(using = LongAsNumberSerializer.class)
            public abstract Long getFileQuota();
        }
    }
}
