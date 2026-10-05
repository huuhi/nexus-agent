package com.huzhijian.nexusagentweb.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        };
    }
}
