package com.huzhijian.nexusagentweb.skills;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 官方技能来源：只做一件事 —— 扫描部署目录 {@code nexus.agent.skill.root-dir}。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 * 说明: <b>为什么从 {@link SkillLoader} 里拆出来</b>：
 * 用户可见的技能 = 官方（目录） + 用户上传（DB）。于是出现一对双向依赖 ——
 * 对话链路里 {@code SkillLoader} 要拿用户技能（{@code loadForChat}），
 * 而技能库页面列「官方」技能时又需要目录扫描结果。
 * 构造器注入下这是<b>真的循环依赖</b>，Spring 会直接启动失败。
 * 把「扫目录」这一件事抽成本组件后，两边都只依赖它，环就解开了。
 * <p>
 * 目录约定见 {@code skills/README.md}：每个子目录一个技能，内含 SKILL.md。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfficialSkillSource {

    private final AgentProperties agentProperties;

    private volatile List<Skill> cached = List.of();
    private volatile Instant loadedAt = Instant.EPOCH;
    /** 目录不存在时只提示一次，避免刷日志 */
    private volatile boolean missingDirLogged = false;

    /**
     * 官方技能清单（受 refreshInterval 缓存控制）。
     */
    public List<Skill> all() {
        if (!agentProperties.getSkill().isEnabled()) {
            return List.of();
        }
        Duration ttl = agentProperties.getSkill().getRefreshInterval();
        if (!expired(ttl)) {
            return cached;
        }
        synchronized (this) {
            if (!expired(ttl)) {
                return cached;
            }
            cached = scan();
            loadedAt = Instant.now();
            return cached;
        }
    }

    /**
     * 重新扫描目录（上传/新建官方技能后手动刷新）。
     */
    public void reload() {
        cached = scan();
        loadedAt = Instant.now();
    }

    private boolean expired(Duration ttl) {
        return ttl == null || ttl.isZero() || ttl.isNegative()
                || Instant.now().isAfter(loadedAt.plus(ttl));
    }

    private List<Skill> scan() {
        Path root = resolveRootDir();
        if (root == null || !Files.isDirectory(root)) {
            if (!missingDirLogged) {
                log.info("Skill 目录不存在，官方技能为空：{}（可用 nexus.agent.skill.root-dir 指定）", root);
                missingDirLogged = true;
            }
            return List.of();
        }
        try {
            // 泛型不变：FileSystemSkillLoader 返回 List<FileSystemSkill>，
            // 不能直接赋给 List<Skill>，必须新建一个列表逐个上转
            List<Skill> skills = new java.util.ArrayList<>(FileSystemSkillLoader.loadSkills(root));
            if (skills.isEmpty()) {
                log.debug("Skill 目录下没有合法技能（每个子目录需含 SKILL.md）：{}", root);
            } else {
                log.info("已加载 {} 个官方技能：{}", skills.size(),
                        skills.stream().map(Skill::name).toList());
            }
            return skills;
        } catch (Exception e) {
            log.warn("扫描 skill 目录失败，本次按无官方技能处理：{} 原因={}", root, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 skill 根目录：支持 {@code ~} 与相对路径（相对于应用工作目录）。
     */
    private Path resolveRootDir() {
        String configured = agentProperties.getSkill().getRootDir();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String value = configured.trim();
        if (value.startsWith("~")) {
            String home = System.getProperty("user.home");
            value = home + value.substring(1);
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }
}