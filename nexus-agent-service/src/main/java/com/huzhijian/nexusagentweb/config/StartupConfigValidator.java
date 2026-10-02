package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 启动时**一次性**校验关键配置，缺失则给出完整清单而不是"修一个报一个"。
 * <p>
 * <b>为什么需要它</b>：这是根因 R2「没有开箱路径，门槛全压在配置上」的直接对症。
 * 原来的表现有三种，都很难受：
 * <ol>
 *   <li>{@code application-prod.yml} 里写的是 {@code ${DEEPSEEK}}，变量没设时
 *       Spring 抛 {@code Could not resolve placeholder} —— 但**一次只报一个**，
 *       要来回启动很多次才能把配置补齐；</li>
 *   <li>{@code application-dev.yml} 被 gitignore，新环境没有它，
 *       报错是零散的「找不到数据源/连接被拒」，看不出到底缺什么；</li>
 *   <li>{@code JWT_SECRET}、{@code API_KEY_SECRET} 缺失时是**静默**的
 *       （前者随机生成密钥 → 重启后所有 token 失效；后者已由 {@code EncryptorFactory}
 *       fail-fast，这里做统一汇总）。</li>
 * </ol>
 * <p>
 * <b>分级</b>：
 * <ul>
 *   <li><b>必需</b>：缺了根本无法提供核心对话能力 → 默认 fail-fast 阻止启动
 *       （可用 {@code nexus.agent.config.fail-fast=false} 降级为 WARN，
 *       但只建议临时排查时用）；</li>
 *   <li><b>建议</b>：缺了只是某项能力不可用（RAG / 邮箱验证码 / 沙盒）→ 只 WARN，
 *       并写明**具体哪项能力会不可用**，避免出现"配了但它没反应"。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigValidator {

    private final Environment environment;
    private final AgentProperties agentProperties;

    /** 校验级别 */
    private enum Level {
        /** 缺了核心能力不可用 */
        REQUIRED,
        /** 缺了只是某项能力降级 */
        RECOMMENDED
    }

    /**
     * 一条校验项。
     *
     * @param name        配置名（日志里显示）
     * @param key         Spring 属性键。**环境变量名也走这里** ——
     *                    Spring 的 Environment 自带 {@code systemEnvironment} 属性源，
     *                    {@code getProperty("JWT_SECRET")} 能直接读到同名环境变量，
     *                    因此不需要维护「属性」与「环境变量」两套来源
     * @param level       级别
     * @param consequence 缺失后果（写给用户看，必须具体到"哪项能力不可用"）
     */
    private record Requirement(String name, String key, Level level, String consequence, String envKey) {
        /** 没有等价环境变量的普通配置项 */
        Requirement(String name, String key, Level level, String consequence) {
            this(name, key, level, consequence, null);
        }
    }

    private static final List<Requirement> REQUIREMENTS = List.of(
            new Requirement("数据库连接串",
                    "spring.datasource.url",
                    Level.REQUIRED,
                    "无法连接 PostgreSQL，应用无法提供服务（prod 模板里是 ${SERVICE_IP}）"),
            new Requirement("对话模型 API Key",
                    "langchain4j.open-ai.streaming-chat-model.api-key",
                    Level.REQUIRED,
                    "默认流式对话模型无法调用，任何对话都会失败（prod 模板里是 ${DEEPSEEK}）"),
            new Requirement("用户 Key 加密主密钥",
                    "nexus.agent.api-key-secret",
                    Level.REQUIRED,
                    "用户自带 API Key 无法加密存储（保存配置时会失败）",
                    "API_KEY_SECRET"),
            new Requirement("JWT 签名密钥",
                    "nexus.agent.jwt-secret",
                    Level.RECOMMENDED,
                    "会随机生成密钥 → **应用重启后所有已签发 token 立即失效**，需重新登录",
                    "JWT_SECRET"),
            new Requirement("向量模型 Key AI_KEY",
                    "langchain4j.open-ai.embedding-model.api-key",
                    Level.RECOMMENDED,
                    "知识库（RAG）无法向量化与检索（prod 模板里是 ${AI_KEY}）"),
            new Requirement("标题生成模型 Key MOONSHOT",
                    "langchain4j.open-ai.chat-model.api-key",
                    Level.RECOMMENDED,
                    "会话标题生成不可用（会自动降级为「用户问题前 255 字符」，prod 模板里是 ${MOONSHOT}）"),
            new Requirement("Redis 主机",
                    "spring.data.redis.host",
                    Level.RECOMMENDED,
                    "邮箱验证码与用户配置缓存不可用，注册/登录会失败（prod 模板里是 ${SERVICE_IP}）"),
            new Requirement("邮箱 SMTP 账号",
                    "spring.mail.username",
                    Level.RECOMMENDED,
                    "邮箱验证码无法发送（prod 模板里是 ${MAIL_USERNAME}）"),
            new Requirement("邮箱 SMTP 密码",
                    "spring.mail.password",
                    Level.RECOMMENDED,
                    "邮箱验证码无法发送（prod 模板里是 ${MAIL_PASSWORD}）"));

    @PostConstruct
    public void validate() {
        List<Requirement> missingRequired = new ArrayList<>();
        List<Requirement> missingRecommended = new ArrayList<>();

        for (Requirement requirement : REQUIREMENTS) {
            if (!isPresent(requirement)) {
                (requirement.level() == Level.REQUIRED ? missingRequired : missingRecommended)
                        .add(requirement);
            }
        }

        reportMissing(missingRecommended, "建议配置缺失", "以下能力将不可用（应用仍可启动）");

        if (missingRequired.isEmpty()) {
            log.info("配置自检通过：{} 项必需配置齐全{}",
                    REQUIREMENTS.size() - missingRecommended.size(),
                    missingRecommended.isEmpty() ? "" : "，另有 " + missingRecommended.size() + " 项建议配置缺失");
            return;
        }

        boolean failFast = agentProperties.getStartup().isFailFast();
        reportMissing(missingRequired, "必需配置缺失", "这些配置缺一不可");

        if (failFast) {
            throw new IllegalStateException(
                    "配置自检未通过：缺少 " + missingRequired.size() + " 项必需配置（详见上方日志）。"
                            + "补齐后重启；确认要带病启动可临时设置 nexus.agent.config.fail-fast=false。");
        }
        log.warn("配置自检未通过，但因 nexus.agent.config.fail-fast=false 仍继续启动 —— 请不要在生产环境这样跑");
    }

    /**
     * 判断配置是否有值。
     * <p>
     * 注意占位符未解析的情形：{@code ${DEEPSEEK}} 在变量缺失时会让 getProperty 抛异常，
     * 这里按「缺失」处理，由本类统一汇总，避免 Spring 一次只报一个。
     */
    private boolean isPresent(Requirement requirement) {
        if (hasValue(safeGetProperty(requirement.key()))) {
            return true;
        }
//        兼容「只设了同名环境变量」的情形：Spring 的 Environment 自带 systemEnvironment 属性源，
//        所以这里仍然走 Environment 而不是 System.getenv —— 保持来源统一。
        return requirement.envKey() != null && hasValue(safeGetProperty(requirement.envKey()));
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }

    private String safeGetProperty(String key) {
        try {
            return environment.getProperty(key);
        } catch (Exception e) {
            // 占位符无法解析：由本类统一报告，不让它变成启动期的单条异常
            return null;
        }
    }

    private void reportMissing(List<Requirement> missing, String title, String headline) {
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(70)).append("\n");
        sb.append("  ").append(title).append("（").append(missing.size()).append(" 项）：")
                .append(headline).append("\n");
        sb.append("=".repeat(70)).append("\n");
        for (int i = 0; i < missing.size(); i++) {
            Requirement requirement = missing.get(i);
            sb.append(i + 1).append(". ").append(requirement.name()).append("\n");
            sb.append("   配置项：").append(requirement.key());
            if (requirement.envKey() != null) {
                sb.append("（或环境变量 ").append(requirement.envKey()).append("）");
            }
            sb.append("\n");
            sb.append("   后果：").append(requirement.consequence()).append("\n");
        }
        sb.append("-".repeat(70)).append("\n");
        sb.append("  配置方式：写进 application-dev.yml（本地，不提交）或设为环境变量。\n");
        sb.append("  快速开始与常见启动失败排查见 README.md。\n");
        sb.append("=".repeat(70));

        if (title.startsWith("建议")) {
            log.warn(sb.toString());
        } else {
            log.error(sb.toString());
        }
    }
}
