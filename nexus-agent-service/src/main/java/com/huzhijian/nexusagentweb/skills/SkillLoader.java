package com.huzhijian.nexusagentweb.skills;

import com.huzhijian.nexusagentweb.service.UserSkillService;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.Skills;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 解析与装配：把「官方（目录）+ 用户（DB）」两类技能合成一份可用清单。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23（2026/10/4 支持用户技能）
 * 说明: <b>目录约定</b>（由 {@code langchain4j-skills} 的 FileSystemSkillLoader 定义）：
 * <pre>
 * &lt;root-dir&gt;/
 *   └── my-skill/            ← 一个目录 = 一个 skill
 *         ├── SKILL.md       ← 必需；YAML frontmatter 提供 name / description
 *         ├── notes.txt      ← 可选文档资源；read_resource 可读（加载时即读入内存）
 *         └── scripts/...    ← 可选脚本目录；read_resource <b>读不到</b>（库刻意排除）
 * </pre>
 * SKILL.md 形如：
 * <pre>
 * ---
 * name: my-skill
 * description: 一句话说明什么时候该用这个 skill
 * ---
 * 这里是给模型看的完整步骤说明……
 * </pre>
 * <p>
 * <b>用户技能从哪来（2026-10-04 新增）</b>：用户上传 .zip/.md 或用模型生成，
 * 存在 {@code user_skill} 表，由 {@link UserSkillService#loadForChat} 在内存里装配
 * （{@code DefaultSkill}）。<b>不解压落盘</b> —— 理由见 {@link SkillPackageParser}。
 * <p>
 * <b>为什么类型是 {@link Skill} 而不是 {@code FileSystemSkill}</b>：
 * 官方技能来自目录、用户技能是内存对象，只有共同父接口 {@code Skill} 能同时装下。
 * 这是 2026-10-04 才做的放宽 —— 之前用具体类型 {@code FileSystemSkill}，
 * 等于把「技能只能来自文件系统」写进了类型系统里。
 * <p>
 * <b>缓存策略</b>：官方技能走 TTL 缓存（{@link OfficialSkillSource} 管），
 * 用户技能每次实时查库。理由是官方技能是部署期固定的（改动频率以小时计），
 * 而用户技能随时可能上传/下架 —— 让用户上传完还要等 60 秒才生效是很糟的体验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillLoader {

    private final OfficialSkillSource officialSkillSource;
    private final UserSkillService userSkillService;

    /**
     * 当前可用的全部技能 = 官方 + 该用户的。
     *
     * @param userId 当前用户；为 null 时只返回官方技能（未登录场景）
     */
    public List<Skill> available(Long userId) {
        List<Skill> out = new ArrayList<>(officialSkillSource.all());
        out.addAll(userSkillService.loadForChat(userId));
        return out;
    }

    /**
     * 可用 skill 的名称列表。
     */
    public List<String> availableNames(Long userId) {
        return available(userId).stream().map(Skill::name).toList();
    }

    /**
     * 「名称：说明」列表，便于排查「某个 skill 为什么没生效」。
     */
    public List<String> describeAll(Long userId) {
        return available(userId).stream().map(s -> s.name() + "：" + s.description()).toList();
    }

    /**
     * 解析本次对话要启用的 skill。
     * <p>
     * 🔴 <b>2026-10-06 修正「空数组」的语义</b>：以前 {@code null} 与 {@code []}
     * 都落到「启用全部」，于是前端「技能多选」里<b>取消全部勾选</b>（发 {@code []}）
     * 会得到「用上所有技能」—— 与用户意图<b>正好相反</b>，而且越是技能多的用户越吃亏
     * （凭空多塞一堆技能描述进系统提示词，首字更慢、模型还容易乱选工具）。
     * <p>
     * 现在的约定：
     * <ul>
     *   <li><b>不传</b>（字段缺省 → 反序列化成 {@code null}）→ 启用全部
     *       （保持老客户端行为不变，客户端不必先知道有哪些技能）；</li>
     *   <li><b>传空数组</b> {@code []} → <b>一个都不用</b>（用户明确表达「这次不要技能」）；</li>
     *   <li>传了名字 → 只启用这些（认不出的名字只 WARN 忽略，不让整次对话失败）。</li>
     * </ul>
     *
     * @param requested 请求里指定的 skill 名称；<b>null 表示启用全部</b>，空列表表示全部禁用
     * @param userId    当前用户，决定能看到哪些用户技能
     * @return 无可启用项时返回 null（调用方据此不注册 skill 工具）
     */
    public Skills resolve(List<String> requested, Long userId) {
        // 「明确表示这次不用技能」—— 必须在查库之前就返回，
        // 否则连官方技能目录都要扫一遍，纯属浪费
        if (requested != null && requested.isEmpty()) {
            log.debug("请求显式指定了空技能列表：本次不启用任何技能");
            return null;
        }
        List<Skill> available = available(userId);
        if (available.isEmpty()) {
            return null;
        }
        List<Skill> selected;
        if (requested == null) {
            selected = available;
        } else {
            Map<String, Skill> byName = new LinkedHashMap<>();
            available.forEach(s -> byName.put(s.name(), s));
            selected = new ArrayList<>();
            for (String name : requested) {
                Skill skill = byName.get(name);
                if (skill == null) {
                    // 只提示不报错：前端传来的名字可能过期（技能被删了），不该让整次对话失败
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
     * 必须让模型知道有哪些 skill，否则 {@code activate_skill} 工具无从下手。
     * 没有可用 skill 时返回一句明确说明，避免提示词里出现空占位。
     */
    public String formatForPrompt(List<String> requested, Long userId) {
        return formatResolved(resolve(requested, userId));
    }

    /**
     * 给**已经解析好的**技能集生成提示词文本。
     * <p>
     * 2026-10-05 抽出：一次对话里技能只需要解析一次（解析要查用户技能表），
     * 但「建工具提供者」和「拼提示词」两处都要用它 ——
     * 让调用方先 {@link #resolve} 再各自 {@code formatResolved}，
     * 避免为了拿一段文本又把整份技能清单重新解析一遍。
     *
     * @param skills 已解析的技能；null 表示「没有可用技能」
     */
    public String formatResolved(Skills skills) {
        if (skills == null) {
            return "当前没有可用的技能（skills）。";
        }
        // 用库自带的格式化，保持与 activate_skill 工具描述的措辞一致
        return skills.formatAvailableSkills();
    }

    /**
     * 重新扫描官方技能目录（新增官方技能后手动刷新）。
     * <p>
     * 用户技能是实时查库的，<b>不需要</b>刷新。
     */
    public void reload() {
        officialSkillSource.reload();
    }
}