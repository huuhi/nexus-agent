package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.UserSkill;
import com.huzhijian.nexusagentweb.dto.SkillSaveDTO;
import com.huzhijian.nexusagentweb.dto.SkillGenerateDTO;
import com.huzhijian.nexusagentweb.em.SkillSource;
import com.huzhijian.nexusagentweb.em.SkillVisibility;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.PermissionDeniedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.mapper.UserSkillMapper;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.skills.OfficialSkillSource;
import com.huzhijian.nexusagentweb.skills.SkillGeneratePrompt;
import com.huzhijian.nexusagentweb.skills.SkillPackageParser;
import com.huzhijian.nexusagentweb.skills.SkillPackageParser.SkillDraft;
import com.huzhijian.nexusagentweb.skills.SkillPackageParser.SkillResource;
import com.huzhijian.nexusagentweb.vo.SkillDetailVO;
import com.huzhijian.nexusagentweb.vo.SkillVO;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.skills.DefaultSkillResource;
import dev.langchain4j.skills.Skill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户技能服务实现。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 * 说明: 对应 minmax 的「技能」页 —— 上传（.zip / .md）、AI 生成、官方/社区分栏。
 * <p>
 * <b>设计要点</b>：
 * <ol>
 *   <li><b>不落盘</b>：正文与资源全在 DB 与内存里转悠（理由见 {@link SkillPackageParser}）</li>
 *   <li><b>两步提交</b>：上传/生成只出草稿，用户确认后才落库 ——
 *       解析可能失败，也让人有机会看清模型到底写了什么</li>
 *   <li><b>技能名全局唯一</b>：模型 {@code activate_skill(name)} 只认名字，重名会让路由变糊</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSkillServiceImpl extends ServiceImpl<UserSkillMapper, UserSkill>
        implements UserSkillService {

    /** 与 {@code SkillPackageParser} 同一套命名规则 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /** NOT NULL 的 jsonb 列必须在应用层归一化（DEFAULT 只在「不写该列」时生效） */
    private static final String EMPTY_JSON_OBJECT = "{}";
    private static final String EMPTY_JSON_ARRAY = "[]";

    private static final String AUTHOR_OFFICIAL = "官方";

    private final UserMapper userMapper;
    /**
     * 官方技能（部署目录）。
     * <p>
     * 依赖它而不是 {@code SkillLoader}，是为了断开循环：
     * {@code SkillLoader} → {@code UserSkillService}（要拿用户技能），
     * 而这里 → {@code OfficialSkillSource}（只要目录扫描）。两边共用一个叶子组件。
     */
    private final OfficialSkillSource officialSkillSource;
    /**
     * AI 生成用的是系统默认模型。
     * <p>
     * 用 {@link ObjectProvider} 而不是直接注入：与 {@code ChatContextFactory} 里的
     * {@code StreamingChatModel} 同理 —— yml 里 {@code langchain4j.open-ai.chat-model}
     * 段可能没配，直接注入会让整个应用起不来。这里缺了就抛明确错误。
     */
    private final ObjectProvider<ChatModel> chatModelProvider;

    @Override
    public List<SkillVO> list(Long userId, String keyword, String source) {
        requireUserId(userId);
        List<SkillVO> out = new ArrayList<>();

        //  ==== 自己创建的（PRIVATE 或 PUBLIC 都算）====
        LambdaQueryWrapper<UserSkill> own = Wrappers.<UserSkill>lambdaQuery()
                .eq(UserSkill::getUserId, userId);
        applyKeyword(own, keyword);
        List<UserSkill> ownSkills = list(own);
        for (UserSkill s : ownSkills) {
            out.add(toVO(s, authorNameOf(s.getUserId()), true));
        }

        //  ==== 别人公开共享的 ====
        LambdaQueryWrapper<UserSkill> shared = Wrappers.<UserSkill>lambdaQuery()
                .eq(UserSkill::getVisibility, SkillVisibility.PUBLIC.name())
                .eq(UserSkill::getEnabled, true)
                .ne(UserSkill::getUserId, userId);
        applyKeyword(shared, keyword);
        // 热门优先：use_count 倒序 → 更新时间倒序
        shared.orderByDesc(UserSkill::getUseCount).orderByDesc(UserSkill::getUpdatedAt);
        for (UserSkill s : list(shared)) {
            out.add(toVO(s, authorNameOf(s.getUserId()), false));
        }

        //  ==== 官方（部署目录）====
        // 官方技能不在本表，要向 SkillLoader 要。这里用懒注入避免循环依赖
        //（SkillLoader 又要调 loadForChat）。
        out.addAll(officialSkillVOs(keyword));

        if (source != null && !source.isBlank()) {
            final String wanted = source.trim().toUpperCase(Locale.ROOT);
            out.removeIf(v -> !wanted.equals(v.getSource()));
        }
        return out;
    }

    @Override
    public SkillDetailVO detail(Long userId, String name) {
        requireUserId(userId);
        UserSkill skill = getByName(name);
        if (skill == null) {
            // 可能是个官方技能
            SkillDetailVO official = officialDetail(userId, name);
            if (official != null) {
                return official;
            }
            throw new NotFoundException("技能不存在：" + name);
        }
        assertVisible(skill, userId);
        return toDetail(skill, authorNameOf(skill.getUserId()), true);
    }

    @Override
    public SkillPackageParser.SkillDraft parseUpload(Long userId, byte[] content, String originalName) {
        requireUserId(userId);
        SkillDraft draft = SkillPackageParser.parse(content, originalName);
        // 名字冲突在这里就报出来，别等到入库时抛 SQL 异常
        UserSkill exist = getByName(draft.getName());
        if (exist != null && !exist.getUserId().equals(userId)) {
            throw new ValidationException("技能名「" + draft.getName() + "」已被其他用户占用，请修改 SKILL.md 里的 name！");
        }
        return draft;
    }

    @Override
    public SkillPackageParser.SkillDraft generate(Long userId, SkillGenerateDTO dto) {
        requireUserId(userId);
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new ValidationException("未配置默认模型，无法生成技能！"
                    + "请在 application-prod.yml 配置 langchain4j.open-ai.chat-model 或 nexus.agent.system-models。");
        }
        String prompt = buildGeneratePrompt(dto);

        String raw;
        try {
            ChatResponse response = model.chat(
                    SystemMessage.from(SkillGeneratePrompt.SYSTEM),
                    UserMessage.from(prompt));
            raw = response.aiMessage().text();
        } catch (Exception e) {
            // 复用「200 但内容不对」的教训：解析不了必须抛明确异常，不能返回半成品
            throw new ValidationException("模型生成技能失败：" + e.getMessage());
        }
        return parseGeneratedMarkdown(raw, dto);
    }

    @Override
    public SkillDetailVO save(Long userId, SkillSaveDTO dto) {
        requireUserId(userId);
        String name = normalizeName(dto.getName());
        String description = dto.getDescription() == null ? "" : dto.getDescription().trim();
        if (description.isBlank()) {
            throw new ValidationException("技能说明不能为空！");
        }
        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new ValidationException("技能正文不能为空！");
        }

        UserSkill exist = getByName(name);
        if (exist != null) {
            // 自己的同名技能 = 覆盖更新；别人的 = 拒绝
            if (!exist.getUserId().equals(userId)) {
                throw new ValidationException("技能名「" + name + "」已被其他用户占用，请换一个！");
            }
            updateFields(exist, description, dto.getContent(), dto.getVisibility(), dto.getResources());
            updateById(exist);
            return toDetail(exist, authorNameOf(userId), true);
        }

        UserSkill entity = UserSkill.builder()
                .userId(userId)
                .name(name)
                .description(description)
                .content(dto.getContent())
                .frontmatter(EMPTY_JSON_OBJECT)
                .resources(resourcesToJson(dto.getResources()))
                .visibility(normalizeVisibility(dto.getVisibility()))
                .source(normalizeSource(dto.getSource()))
                .enabled(true)
                .useCount(0)
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .build();
        save(entity);
        return toDetail(entity, authorNameOf(userId), true);
    }

    @Override
    public SkillDetailVO update(Long userId, String name, SkillSaveDTO dto) {
        requireUserId(userId);
        UserSkill exist = getByName(name);
        if (exist == null) {
            throw new NotFoundException("技能不存在：" + name);
        }
        if (!exist.getUserId().equals(userId)) {
            throw new PermissionDeniedException("只能修改自己创建的技能！");
        }
        String newName = normalizeName(dto.getName());
        if (!newName.equals(exist.getName())) {
            UserSkill clash = getByName(newName);
            if (clash != null && !clash.getId().equals(exist.getId())) {
                throw new ValidationException("技能名「" + newName + "」已被占用！");
            }
            exist.setName(newName);
        }
        updateFields(exist, dto.getDescription(), dto.getContent(), dto.getVisibility(), dto.getResources());
        updateById(exist);
        return toDetail(exist, authorNameOf(userId), true);
    }

    @Override
    public void delete(Long userId, String name) {
        requireUserId(userId);
        UserSkill exist = getByName(name);
        if (exist == null) {
            throw new NotFoundException("技能不存在：" + name);
        }
        if (!exist.getUserId().equals(userId)) {
            throw new PermissionDeniedException("只能删除自己创建的技能！");
        }
        removeById(exist.getId());
    }

    @Override
    public void setEnabled(Long userId, String name, boolean enabled) {
        requireUserId(userId);
        UserSkill exist = getByName(name);
        if (exist == null) {
            throw new NotFoundException("技能不存在：" + name);
        }
        if (!exist.getUserId().equals(userId)) {
            throw new PermissionDeniedException("只能操作自己创建的技能！");
        }
        exist.setEnabled(enabled);
        exist.setUpdatedAt(Timestamp.valueOf(LocalDateTime.now()));
        updateById(exist);
    }

    @Override
    public List<Skill> loadForChat(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            //  可见性 = (自己的 且 已上架) 或 (别人公开的 且 已上架)
            //  ⚠️ 用显式括号包住整个 or —— MyBatis-Plus 的 or() 不带括号时，
            //  前面的 eq 条件会被 OR 掉，等于「所有人都能看到所有技能」。
            List<UserSkill> rows = list(Wrappers.<UserSkill>lambdaQuery()
                    .eq(UserSkill::getEnabled, true)
                    .and(w -> w.eq(UserSkill::getUserId, userId)
                            .or()
                            .eq(UserSkill::getVisibility, SkillVisibility.PUBLIC.name())));
            List<Skill> out = new ArrayList<>(rows.size());
            for (UserSkill s : rows) {
                try {
                    out.add(toMemorySkill(s));
                } catch (Exception e) {
                    log.warn("技能转内存失败，已跳过：{} 原因={}", s.getName(), e.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            //  典型场景是 010 迁移没跑（表不存在）。
            //  绝不能因为缺迁移就让整个对话挂掉 —— 降级为「没有用户技能」。
            log.warn("加载用户技能失败（是否忘了执行 docs/sql/010？），按无用户技能处理：{}", e.getMessage());
            return List.of();
        }
    }

    // ==================================================================
    //  以下是纯转换逻辑，便于单测直接断言
    // ==================================================================

    /**
     * DB 行 → 内存 {@link Skill}。
     * <p>
     * 这是本功能的核心：{@code Skills.from(Collection<? extends Skill>)} 接受任意实现，
     * 所以用户技能<b>根本不需要变成文件</b>。
     */
    static Skill toMemorySkill(UserSkill row) {
        List<dev.langchain4j.skills.SkillResource> resources = new ArrayList<>();
        for (SkillResource r : resourcesFromJson(row.getResources())) {
            resources.add(DefaultSkillResource.builder()
                    .relativePath(r.path())
                    .content(r.content())
                    .build());
        }
        return DefaultSkill.builder()
                .name(row.getName())
                .description(row.getDescription())
                .content(row.getContent())
                .resources(resources)
                .build();
    }

    /**
     * 解析模型返回的 SKILL.md 文本。
     * <p>
     * 模型被要求输出纯 Markdown（带 frontmatter），所以复用与上传完全相同的解析路径 ——
     * <b>AI 生成的技能和上传的技能走同一套校验</b>，模型不听话时也会被拦下来。
     */
    static SkillDraft parseGeneratedMarkdown(String raw, SkillGenerateDTO dto) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("模型没有返回内容，请重试！");
        }
        String text = raw.trim();
        // 容忍模型多嘴包一层 ```markdown 代码块
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // 用户指定了名字就以用户的为准（模型自己起名经常不合规）
        if (dto != null && StrUtil.isNotBlank(dto.getName())) {
            String wanted = dto.getName().trim().toLowerCase(Locale.ROOT);
            text = text.replaceFirst("(?m)^name:\\s*.*$", "name: " + wanted);
        }
        return SkillPackageParser.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "generated.md");
    }

    /**
     * 组装 AI 生成用的用户提示词。
     */
    static String buildGeneratePrompt(SkillGenerateDTO dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户想做一个技能，需求如下：\n")
                .append(dto.getRequirement().trim()).append("\n");
        if (StrUtil.isNotBlank(dto.getExtra())) {
            sb.append("\n补充约束：\n").append(dto.getExtra().trim()).append("\n");
        }
        if (StrUtil.isNotBlank(dto.getName())) {
            sb.append("\n技能名必须使用：").append(dto.getName().trim()).append("\n");
        }
        if (dto.getReferenceResources() != null && !dto.getReferenceResources().isEmpty()) {
            sb.append("\n以下是用户提供的参考资源。如果技能需要用到它们，"
                            + "请在正文里用 read_resource 引用，并在附录里原样给出（你需要输出附录）。")
                    .append("\n参考资源：\n");
            for (SkillGenerateDTO.ResourceDTO r : dto.getReferenceResources()) {
                sb.append("<resource path=\"").append(r.getPath()).append("\">\n")
                        .append(r.getContent()).append("\n</resource>\n");
            }
        }
        return sb.toString();
    }

    private void updateFields(UserSkill exist, String description, String content,
                              String visibility, List<SkillSaveDTO.ResourceDTO> resources) {
        if (StrUtil.isNotBlank(description)) {
            exist.setDescription(description.trim());
        }
        if (StrUtil.isNotBlank(content)) {
            exist.setContent(content);
        }
        if (StrUtil.isNotBlank(visibility)) {
            exist.setVisibility(normalizeVisibility(visibility));
        }
        if (resources != null) {
            exist.setResources(resourcesToJson(resources));
        }
        exist.setUpdatedAt(Timestamp.valueOf(LocalDateTime.now()));
    }

    private void applyKeyword(LambdaQueryWrapper<UserSkill> wrapper, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return;
        }
        String kw = keyword.trim();
        // 转义 LIKE 通配符，否则用户搜 "%" 会把所有技能都捞出来
        String escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        wrapper.and(w -> w.like(UserSkill::getName, escaped)
                .or().like(UserSkill::getDescription, escaped));
    }

    private void assertVisible(UserSkill skill, Long userId) {
        if (skill.getUserId().equals(userId)) {
            return;
        }
        if (!SkillVisibility.PUBLIC.name().equals(skill.getVisibility())) {
            // 私有技能对别人等同于不存在，不告诉调用方「它存在但你看不到」
            throw new NotFoundException("技能不存在：" + skill.getName());
        }
    }

    static String normalizeName(String name) {
        if (StrUtil.isBlank(name)) {
            throw new ValidationException("技能名不能为空！");
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (!NAME_PATTERN.matcher(n).matches()) {
            throw new ValidationException(
                    "技能名不合法：" + name + "（只允许小写字母、数字、连字符，且以字母或数字开头）");
        }
        return n;
    }

    static String normalizeVisibility(String visibility) {
        if (StrUtil.isBlank(visibility)) {
            return SkillVisibility.PRIVATE.name();
        }
        String v = visibility.trim().toUpperCase(Locale.ROOT);
        if (!SkillVisibility.isValid(v)) {
            throw new ValidationException("visibility 只能是 PRIVATE 或 PUBLIC！");
        }
        return v;
    }

    static String normalizeSource(String source) {
        if (StrUtil.isBlank(source)) {
            return SkillSource.UPLOAD.name();
        }
        String s = source.trim().toUpperCase(Locale.ROOT);
        if (!SkillSource.isValid(s)) {
            throw new ValidationException("source 只能是 UPLOAD 或 AI_GENERATED！");
        }
        // BUILTIN 是官方技能的展示值，不允许用户手动声称
        if (SkillSource.BUILTIN.name().equals(s)) {
            return SkillSource.UPLOAD.name();
        }
        return s;
    }

    static String resourcesToJson(List<SkillSaveDTO.ResourceDTO> resources) {
        if (resources == null || resources.isEmpty()) {
            return EMPTY_JSON_ARRAY;
        }
        List<Map<String, String>> list = new ArrayList<>(resources.size());
        for (SkillSaveDTO.ResourceDTO r : resources) {
            if (r == null || StrUtil.isBlank(r.getPath())) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("path", r.getPath().trim());
            m.put("content", r.getContent() == null ? "" : r.getContent());
            list.add(m);
        }
        return list.isEmpty() ? EMPTY_JSON_ARRAY : JSONUtil.toJsonStr(list);
    }

    static List<SkillResource> resourcesFromJson(String json) {
        if (StrUtil.isBlank(json) || EMPTY_JSON_ARRAY.equals(json.trim())) {
            return List.of();
        }
        try {
            //  ⚠️ JSONUtil.toList **没有** TypeReference 重载（只有 Class），
            //  所以走 JSONArray 逐项取。资源的结构是自己写出去的（path/content 两个字符串键），
            //  用数组 API 比泛型反序列化更直接，也不会有类型推断的坑。
            JSONArray arr = JSONUtil.parseArray(json);
            List<SkillResource> out = new ArrayList<>(arr.size());
            for (Object item : arr) {
                if (!(item instanceof JSONObject o)) {
                    continue;
                }
                Object path = o.get("path");
                Object content = o.get("content");
                if (path == null) {
                    continue;
                }
                out.add(new SkillResource(String.valueOf(path),
                        content == null ? "" : String.valueOf(content)));
            }
            return out;
        } catch (Exception e) {
            log.warn("技能资源 JSON 解析失败，按无资源处理：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按技能名查一条。
     * <p>
     * 技能名是全局唯一的（DB 上有唯一索引），所以按名查就是「取一条」。
     */
    private UserSkill getByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return getOne(Wrappers.<UserSkill>lambdaQuery().eq(UserSkill::getName, name));
    }

    SkillVO toVO(UserSkill s, String author, boolean owned) {
        return new SkillVO(s.getId(), s.getName(), s.getDescription(), s.getSource(),
                s.getVisibility(), author, owned, s.getUseCount(), s.getUpdatedAt(),
                resourcesFromJson(s.getResources()).stream().map(SkillResource::path).toList());
    }

    SkillDetailVO toDetail(UserSkill s, String author, boolean owned) {
        List<SkillDetailVO.SkillResourceVO> resources = resourcesFromJson(s.getResources()).stream()
                .map(r -> new SkillDetailVO.SkillResourceVO(r.path(), r.content()))
                .toList();
        return new SkillDetailVO(s.getId(), s.getName(), s.getDescription(), s.getContent(),
                s.getFrontmatter(), s.getSource(), s.getVisibility(), author, owned,
                s.getUseCount(), s.isEnabledFlag(), resources, s.getCreatedAt(), s.getUpdatedAt());
    }

    private String authorNameOf(Long authorId) {
        if (authorId == null) {
            return AUTHOR_OFFICIAL;
        }
        try {
            User u = userMapper.selectById(authorId);
            if (u != null && StrUtil.isNotBlank(u.getUsername())) {
                return u.getUsername();
            }
        } catch (Exception e) {
            log.debug("取作者名失败，降级为 id：{}", e.getMessage());
        }
        return "user-" + authorId;
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new ValidationException("用户未登录！");
        }
    }

    //  ==== 官方技能 ====
    //  官方技能来自部署目录，不在本表。列表/详情要带上它们，
    //  依赖 OfficialSkillSource（而非 SkillLoader）以避免与 SkillLoader 形成循环依赖。

    private List<SkillVO> officialSkillVOs(String keyword) {
        List<SkillVO> out = new ArrayList<>();
        for (Skill s : officialSkillSource.all()) {
            if (StrUtil.isNotBlank(keyword)
                    && !s.name().contains(keyword.trim().toLowerCase(Locale.ROOT))
                    && !s.description().toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new SkillVO(null, s.name(), s.description(), SkillSource.BUILTIN.name(),
                    "PUBLIC", AUTHOR_OFFICIAL, false, 0, null,
                    s.resources().stream().map(dev.langchain4j.skills.SkillResource::relativePath).toList()));
        }
        return out;
    }

    private SkillDetailVO officialDetail(Long userId, String name) {
        for (Skill s : officialSkillSource.all()) {
            if (!s.name().equals(name)) {
                continue;
            }
            List<SkillDetailVO.SkillResourceVO> resources = s.resources().stream()
                    .map(r -> new SkillDetailVO.SkillResourceVO(r.relativePath(), r.content()))
                    .toList();
            return new SkillDetailVO(null, s.name(), s.description(), s.content(), EMPTY_JSON_OBJECT,
                    SkillSource.BUILTIN.name(), "PUBLIC", AUTHOR_OFFICIAL, false, 0, true,
                    resources, null, null);
        }
        return null;
    }
}