package com.huzhijian.nexusagentweb.skills;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.Skills;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: Skill 加载器（本地目录扫描方案，对应决策 D3）。
 * <p>
 * <b>目录约定</b>（由 {@code langchain4j-skills} 的 {@link FileSystemSkillLoader} 定义）：
 * <pre>
 * &lt;root-dir&gt;/
 *   └── my-skill/            ← 一个目录 = 一个 skill
 *         ├── SKILL.md       ← 必需；YAML frontmatter 提供 name / description
 *         ├── notes.txt      ← 可选文档资源；read_resource 可读（加载时即读入内存）
 *         └── scripts/...    ← 可选脚本目录；read_resource <b>读不到</b>（库刻意排除，
 *                              约定脚本供沙盒执行、文档供阅读，两者分开）
 * </pre>
 * 除 {@code SKILL.md} 与 {@code scripts/} 外，技能目录下的普通文件都会被索引为
 * 资源（递归、读入内存）——所以资源文件不宜过大。
 * SKILL.md 形如：
 * <pre>
 * ---
 * name: my-skill
 * description: 一句话说明什么时候该用这个 skill
 * ---
 * 这里是给模型看的完整步骤说明……
 * </pre>
 * <p>
 * <b>为什么是本地目录而不是数据库/上传</b>：skill 本质上是一个文件夹（含脚本等资源），
 * 在 B/S 架构下做「用户上传 zip」需要额外解决落盘位置、解压安全、版本管理，
 * 成本远高于收益（决策 D3）。所以先把本地目录这条最简路径做通。
 * <p>
 * <b>将来要支持用户自定义</b>：不需要改本类的结构，只要在扫描时把
 * {@code <root>/users/<userId>} 也纳入即可（见 {@link #scan()} 的注释位置）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillLoader {

    private final AgentProperties agentProperties;

    /** 缓存的扫描结果 */
    private volatile List<FileSystemSkill> cached = List.of();
    private volatile Instant loadedAt = Instant.EPOCH;
    /** 目录不存在时只提示一次，避免刷日志 */
    private volatile boolean missingDirLogged = false;

    /**
     * 当前可用的全部 skill（受 refreshInterval 缓存控制）。
     */
    public List<FileSystemSkill> all() {
        if (!agentProperties.getSkill().isEnabled()) {
            return List.of();
        }
        Duration ttl = agentProperties.getSkill().getRefreshInterval();
        boolean expired = ttl == null || ttl.isZero() || ttl.isNegative()
                || Instant.now().isAfter(loadedAt.plus(ttl));
        if (!expired) {
            return cached;
        }
        synchronized (this) {
            expired = ttl == null || ttl.isZero() || ttl.isNegative()
                    || Instant.now().isAfter(loadedAt.plus(ttl));
            if (expired) {
                cached = scan();
                loadedAt = Instant.now();
            }
            return cached;
        }
    }

    /**
     * 可用 skill 的名称列表。
     */
    public List<String> availableNames() {
        return all().stream().map(Skill::name).toList();
    }

    /**
     * 「名称：说明」列表，便于排查「某个 skill 为什么没生效」。
     */
    public List<String> describeAll() {
        return all().stream().map(s -> s.name() + "：" + s.description()).toList();
    }

    /**
     * 解析本次对话要启用的 skill。
     *
     * @param requested 请求里指定的 skill 名称；**为空表示启用全部**（客户端不必先知道有哪些）
     * @return 无可启用项时返回 null（调用方据此不注册 skill 工具）
     */
    public Skills resolve(List<String> requested) {
        List<FileSystemSkill> available = all();
        if (available.isEmpty()) {
            return null;
        }
        List<FileSystemSkill> selected;
        if (requested == null || requested.isEmpty()) {
            selected = available;
        } else {
            Map<String, FileSystemSkill> byName = new LinkedHashMap<>();
            available.forEach(s -> byName.put(s.name(), s));
            selected = new ArrayList<>();
            for (String name : requested) {
                FileSystemSkill skill = byName.get(name);
                if (skill == null) {
                    // 只提示不报错：前端传来的名字可能过期（skill 被删了），不该让整次对话失败
                    log.warn("请求的 skill 不存在，已忽略：{}（当前可用：{}）", name, byName.keySet());
                    continue;
                }
                selected.add(skill);
            }
        }
        if (selected.isEmpty()) {
            return null;
        }
        log.debug("本次启用 skill：{}", selected.stream().map(Skill::name).toList());
        return Skills.from(selected);
    }

    /**
     * 生成注入系统提示词的「可用 skill 清单」。
     * <p>
     * 必须让模型知道有哪些 skill，否则 `activate_skill` 工具无从下手。
     * 没有可用 skill 时返回一句明确说明，避免提示词里出现空占位。
     */
    public String formatForPrompt(List<String> requested) {
        Skills skills = resolve(requested);
        if (skills == null) {
            return "当前没有可用的技能（skills）。";
        }
        // 用库自带的格式化，保持与 activate_skill 工具描述的措辞一致
        return skills.formatAvailableSkills();
    }

    /**
     * 重新扫描目录（refreshInterval 内的手动刷新入口）。
     */
    public void reload() {
        cached = scan();
        loadedAt = Instant.now();
    }

    private List<FileSystemSkill> scan() {
        Path root = resolveRootDir();
        if (root == null || !Files.isDirectory(root)) {
            if (!missingDirLogged) {
                log.info("Skill 目录不存在，Skill 能力为空：{}（可用 nexus.agent.skill.root-dir 指定）", root);
                missingDirLogged = true;
            }
            return List.of();
        }
        try {
            List<FileSystemSkill> skills = FileSystemSkillLoader.loadSkills(root);
            if (skills.isEmpty()) {
                log.debug("Skill 目录下没有合法 skill（每个子目录需含 SKILL.md）：{}", root);
            } else {
                log.info("已加载 {} 个 skill：{}", skills.size(),
                        skills.stream().map(Skill::name).toList());
            }
            // 将来要支持「用户自定义上传」：在这里追加扫描 <root>/users/<userId> 即可，
            // 本类的其它逻辑（按名称筛选、提示词格式化）无需改动。
            return skills;
        } catch (Exception e) {
            // 扫描失败不能让应用起不来，降级为「没有 skill」
            log.warn("扫描 skill 目录失败，本次按无 skill 处理：{} 原因={}", root, e.getMessage());
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
