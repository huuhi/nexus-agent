package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.dto.SkillGenerateDTO;
import com.huzhijian.nexusagentweb.dto.SkillSaveDTO;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.service.UserSkillService;
import com.huzhijian.nexusagentweb.vo.Result;
import com.huzhijian.nexusagentweb.vo.SkillDetailVO;
import com.huzhijian.nexusagentweb.vo.SkillVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 技能库：上传、AI 生成、管理自己的技能。
 * <p>
 * 对应 minmax 的「插件-技能」页。技能本质是一个带 YAML 头的文件夹，
 * 官方技能由部署方放在 {@code nexus.agent.skill.root-dir}，
 * 用户技能存在 {@code user_skill} 表（上传或 AI 生成），两者在对话时合并成同一份清单。
 * <p>
 * <b>上传是两步的</b>：{@code /upload} 只解析不落库，返回草稿让用户确认；
 * 用户点头后才调 {@code /save} 落库。理由：解析可能失败（缺 frontmatter、名字被占），
 * 而且让人有机会看清模型到底写了什么再决定要不要存。
 *
 * @author 胡志坚
 * @version 1.0
 */
@RestController
@RequestMapping("/api/skill")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "技能库", description = "上传 / AI 生成 / 管理技能（社区共享）")
public class SkillController {

    /**
     * 上传文件大小上限。
     * <p>
     * Spring Boot 默认只有 1MB（{@code spring.servlet.multipart.max-file-size}），
     * 而技能包里常常要带模板/参考文档，1MB 太小。这里再叠一层应用层校验，
     * 避免超大文件先全读进内存才发现超限。
     */
    private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;

    private final UserSkillService userSkillService;

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录");
        }
        return userId;
    }

    @Operation(summary = "技能库列表（官方 + 社区公开 + 我的私有）",
            description = """
                    `keyword` 匹配技能名或说明；
                    `source` 可选 `BUILTIN`（官方）/ `UPLOAD` / `AI_GENERATED`，不传表示不限。
                    排序：自己创建的在前，社区内按使用次数倒序，官方技能在后。
                    """)
    @GetMapping("/list")
    public Result list(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String source) {
        return Result.ok(userSkillService.list(requireUserId(), keyword, source));
    }

    @Operation(summary = "技能详情（含正文与资源全文）",
            description = "列表页不带正文就是为了避免一次吐出所有技能正文 —— 正文会全部进系统提示词。")
    @GetMapping("/{name}")
    public Result detail(@PathVariable String name) {
        return Result.ok(userSkillService.detail(requireUserId(), name));
    }

    @Operation(summary = "上传技能包（只解析，不落库）",
            description = """
                    支持 `.zip` / `.skill` / `.md`。
                    zip 里必需有 `SKILL.md`（YAML frontmatter 含 name 与 description），
                    其余文本文件（md / txt / json / yaml / csv / html / xml）会作为
                    `read_resource` 可读的资源收录；`scripts/` 下的内容**被刻意忽略**
                    （库读不到它，也不该托管可执行内容）。

                    返回的是解析出的草稿，**不会入库** —— 确认无误后再调 `/save`。
                    """)
    @PostMapping("/upload")
    public Result upload(@RequestParam MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("请选择要上传的技能包！");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ValidationException("技能包过大（上限 8MB）！");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("读取上传文件失败：" + e.getMessage());
        }
        return Result.ok(userSkillService.parseUpload(requireUserId(), content, file.getOriginalFilename()));
    }

    @Operation(summary = "用模型生成技能（只出草稿，不落库）",
            description = """
                    传 `requirement`（想做什么）即可。生成结果走**与上传完全相同的校验**，
                    所以模型写出不合规的 name / 缺 description 会被直接拒掉。

                    可选 `referenceResources`：把你自己的模板/参考文档传进来，
                    否则模型只能凭空编内容。确认草稿后调 `/save` 落库（`source` 传 `AI_GENERATED`）。
                    """)
    @PostMapping("/ai-generate")
    public Result aiGenerate(@RequestBody @Valid SkillGenerateDTO dto) {
        return Result.ok(userSkillService.generate(requireUserId(), dto));
    }

    @Operation(summary = "保存技能（上传或 AI 生成后，确认落库）",
            description = """
                    `visibility` 传 `PUBLIC` 即公开到社区，所有登录用户都能看到并使用；
                    不传默认 `PRIVATE`（仅自己可见）。

                    技能名全局唯一。若自己已有同名技能则**视为覆盖更新**；
                    若被别人占用会报错。
                    """)
    @PostMapping("/save")
    public Result save(@RequestBody @Valid SkillSaveDTO dto) {
        return Result.ok(userSkillService.save(requireUserId(), dto));
    }

    @Operation(summary = "修改自己创建的技能")
    @PutMapping("/{name}")
    public Result update(@PathVariable String name, @RequestBody @Valid SkillSaveDTO dto) {
        return Result.ok(userSkillService.update(requireUserId(), name, dto));
    }

    @Operation(summary = "删除自己创建的技能",
            description = "官方技能不可删除（它来自部署目录）。")
    @DeleteMapping("/{name}")
    public Result delete(@PathVariable String name) {
        userSkillService.delete(requireUserId(), name);
        return Result.ok();
    }

    @Operation(summary = "上架 / 下架自己的技能",
            description = "下架（`enabled=false`）后不再进入任何人的对话上下文，但记录保留，可随时恢复。")
    @PutMapping("/{name}/enabled")
    public Result setEnabled(@PathVariable String name, @RequestParam boolean enabled) {
        userSkillService.setEnabled(requireUserId(), name, enabled);
        return Result.ok();
    }
}