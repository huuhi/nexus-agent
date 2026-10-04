package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.UserSkillService;
import com.huzhijian.nexusagentweb.skills.OfficialSkillSource;
import com.huzhijian.nexusagentweb.skills.SkillLoader;
import dev.langchain4j.skills.Skills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillLoader 的纯单元测试：验证「目录扫描 + 用户技能合并 + 按名称解析 + 缓存」核心行为。
 * <p>
 * 用 @TempDir 构造隔离的技能目录，不依赖 Spring 容器、不读真实 skills/ 目录。
 * 用户技能用 Mockito 打桩（纯单测，不连库）。
 */
class SkillLoaderTest {

    @TempDir
    Path tempDir;

    /** 在临时根目录下造一个合法技能（SKILL.md + frontmatter） */
    private void writeSkill(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: 测试技能 %s
                ---

                # 步骤
                1. 什么都不做
                """.formatted(name, name));
    }

    /** 用户技能服务打桩：本��测试里恒定返回空（绝大多数用例只关心官方技能） */
    private UserSkillService emptyUserSkills() {
        UserSkillService svc = Mockito.mock(UserSkillService.class);
        Mockito.when(svc.loadForChat(Mockito.any())).thenReturn(List.of());
        return svc;
    }

    /**
     * 打桩出若干用户技能。
     * <p>
     * 关键：{@code userId == null} 时返回空 —— 未登录场景不该拿到任何用户技能。
     */
    private UserSkillService stubUserSkills(dev.langchain4j.skills.Skill... skills) {
        UserSkillService svc = Mockito.mock(UserSkillService.class);
        List<dev.langchain4j.skills.Skill> fixed = List.of(skills);
        Mockito.when(svc.loadForChat(Mockito.nullable(Long.class)))
                .thenAnswer(inv -> inv.getArgument(0) == null ? List.of() : fixed);
        return svc;
    }

    private SkillLoader loaderWith(String rootDir, Duration ttl, boolean enabled) {
        return loaderWith(rootDir, ttl, enabled, emptyUserSkills());
    }

    private SkillLoader loaderWith(String rootDir, Duration ttl, boolean enabled, UserSkillService userSkills) {
        AgentProperties props = new AgentProperties();
        props.getSkill().setRootDir(rootDir);
        props.getSkill().setRefreshInterval(ttl);
        props.getSkill().setEnabled(enabled);
        return new SkillLoader(new OfficialSkillSource(props), userSkills);
    }

    @Test
    @DisplayName("enabled=false 时直接返回空，不扫描目录")
    void disabledReturnsEmptyWithoutScanning() throws IOException {
        writeSkill("a-skill");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), false);

        assertTrue(loader.available(1L).isEmpty());
        assertNull(loader.resolve(null, 1L));
        assertEquals("当前没有可用的技能（skills）。", loader.formatForPrompt(null, 1L));
    }

    @Test
    @DisplayName("根目录不存在时降级为空列表，不抛异常")
    void missingRootDirDegradesToEmpty() {
        SkillLoader loader = loaderWith(tempDir.resolve("no-such-dir").toString(),
                Duration.ofSeconds(60), true);

        assertTrue(loader.available(1L).isEmpty());
        assertNull(loader.resolve(List.of("whatever"), 1L));
    }

    @Test
    @DisplayName("resolve(null) 启用全部技能；请求不带 skills 时无需先知道有哪些")
    void resolveNullEnablesAll() throws IOException {
        writeSkill("skill-a");
        writeSkill("skill-b");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true);

        Skills skills = loader.resolve(null, 1L);

        assertNotNull(skills);
        assertEquals(2, loader.available(1L).size());
        assertEquals(List.of("skill-a", "skill-b"), loader.availableNames(1L));
    }

    @Test
    @DisplayName("resolve 按名称筛选：只启用请求里存在的")
    void resolveFiltersByName() throws IOException {
        writeSkill("skill-a");
        writeSkill("skill-b");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true);

        Skills skills = loader.resolve(List.of("skill-b"), 1L);

        assertNotNull(skills);
        // 只选中 skill-b；available() 仍是全部
        assertEquals(2, loader.available(1L).size());
    }

    @Test
    @DisplayName("请求了不存在的技能时静默忽略；全部不存在时返回 null（不注册技能工具）")
    void resolveUnknownNamesIgnored() throws IOException {
        writeSkill("skill-a");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true);

        assertNull(loader.resolve(List.of("nope-1", "nope-2"), 1L));
        // 混合存在与不存在：保留存在的
        assertNotNull(loader.resolve(List.of("skill-a", "nope"), 1L));
    }

    @Test
    @DisplayName("refreshInterval 内使用缓存：新增技能目录不立即可见")
    void cachedWithinTtl() throws IOException {
        writeSkill("skill-a");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(600), true);

        assertEquals(1, loader.available(1L).size());
        // TTL 内新增目录 → 走缓存，看不见
        writeSkill("skill-b");
        assertEquals(1, loader.available(1L).size());
    }

    @Test
    @DisplayName("refreshInterval=0 每次重扫：新增技能立即可见（无需重启）")
    void refreshIntervalZeroAlwaysRescans() throws IOException {
        writeSkill("skill-a");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ZERO, true);

        assertEquals(1, loader.available(1L).size());
        writeSkill("skill-b");
        assertEquals(2, loader.available(1L).size());
    }

    @Test
    @DisplayName("reload() 手动刷新绕过缓存")
    void reloadBypassesCache() throws IOException {
        writeSkill("skill-a");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(600), true);

        assertEquals(1, loader.available(1L).size());
        writeSkill("skill-b");
        loader.reload();
        assertEquals(2, loader.available(1L).size());
    }

    @Test
    @DisplayName("没有 SKILL.md 的子目录被跳过（不完整的技能不算技能）")
    void skipsDirWithoutSkillMd() throws IOException {
        writeSkill("good-skill");
        Files.createDirectories(tempDir.resolve("bad-skill")); // 无 SKILL.md
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true);

        List<String> names = loader.availableNames(1L);
        assertEquals(List.of("good-skill"), names);
    }

    @Test
    @DisplayName("formatForPrompt 有技能时返回包含名称与说明的清单")
    void formatForPromptIncludesNames() throws IOException {
        writeSkill("skill-a");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true);

        String prompt = loader.formatForPrompt(null, 1L);

        assertTrue(prompt.contains("skill-a"), "提示词里应包含技能名：" + prompt);
        assertTrue(prompt.contains("测试技能"), "提示词里应包含技能说明：" + prompt);
    }

    @Test
    @DisplayName("资源索引规则：SKILL.md 与 scripts/ 之外的普通文件被预索引")
    void resourcesAreIndexedAtLoadTime() throws IOException {
        writeSkill("skill-with-assets");
        Files.writeString(tempDir.resolve("skill-with-assets").resolve("notes.txt"), "笔记");
        Files.createDirectories(tempDir.resolve("skill-with-assets").resolve("scripts"));
        Files.writeString(tempDir.resolve("skill-with-assets").resolve("scripts").resolve("run.py"), "print(1)");

        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true);

        // read_resource 只能读这份预索引清单 —— 匹配不到就报错，天然无路径穿越。
        // 库的约定：scripts/ 是执行目录（刻意排除），SKILL.md 不是资源。
        assertEquals(1, loader.available(1L).get(0).resources().size());
        assertEquals("notes.txt", loader.available(1L).get(0).resources().get(0).relativePath());
    }

    // ==================== 用户技能合并（2026-10-04 新增） ====================

    @Test
    @DisplayName("用户技能与官方技能合并成一份清单")
    void mergesUserSkillsWithOfficial() throws IOException {
        writeSkill("official-skill");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true,
                stubUserSkills(dev.langchain4j.skills.DefaultSkill.builder()
                        .name("my-skill")
                        .description("我自己做的")
                        .content("正文")
                        .build()));

        assertEquals(List.of("official-skill", "my-skill"), loader.availableNames(1L));
    }

    @Test
    @DisplayName("用户技能也能被 resolve 按名字选中（内存 Skill 与目录 Skill 一视同仁）")
    void resolvesUserSkillByName() throws IOException {
        writeSkill("official-skill");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true,
                stubUserSkills(dev.langchain4j.skills.DefaultSkill.builder()
                        .name("my-skill")
                        .description("我自己做的")
                        .content("正文")
                        .build()));

        assertNotNull(loader.resolve(List.of("my-skill"), 1L));
    }

    @Test
    @DisplayName("userId 为 null 时只返回官方技能，不因查用户技能报错")
    void nullUserIdReturnsOnlyOfficial() throws IOException {
        writeSkill("official-skill");
        SkillLoader loader = loaderWith(tempDir.toString(), Duration.ofSeconds(60), true,
                stubUserSkills(dev.langchain4j.skills.DefaultSkill.builder()
                        .name("my-skill").description("d").content("c").build()));

        assertEquals(List.of("official-skill"), loader.availableNames(null));
    }
}