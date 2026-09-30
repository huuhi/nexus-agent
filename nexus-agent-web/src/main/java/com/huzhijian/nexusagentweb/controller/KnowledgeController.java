package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.dto.KnowledgeDTO;
import com.huzhijian.nexusagentweb.dto.KnowledgeFileDTO;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseService;
import com.huzhijian.nexusagentweb.vo.KnowledgeDetailVO;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
@RestController
@RequestMapping("/api/knowledge")
@Tag(name = "知识库", description = "创建知识库、上传文件入库（向量化）、列表与详情")
public class KnowledgeController {
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Operation(summary = "把已上传的文件加入知识库（异步向量化）", description = """
            **异步**：接口立刻返回，切片与向量化在后台跑（`@Async`），
            进度看 `GET /api/knowledge/{id}` 里每个文件的状态。
            ⚠️ `KnowledgeFileDTO` 的 `configId` / `model` 已废弃且不再必填 ——
            向量模型固定为**系统模型**（P2-13），用户自选会让入库与检索的向量空间不一致。
            """)
    @PostMapping("/file")
    public Result fileInsertKnowledge(@RequestBody @Valid KnowledgeFileDTO knowledgeDTO){
        String msg=knowledgeBaseService.insertKnowledge(knowledgeDTO);
        return Result.ok(msg);
    }
    @Operation(summary = "创建知识库")
    @PostMapping
    public Result createKnowledge(@RequestBody @Valid KnowledgeDTO knowledgeDTO){
        knowledgeBaseService.createKnowledge(knowledgeDTO);
        return Result.ok();
    }
    @Operation(summary = "当前用户的知识库列表")
    @GetMapping("/list")
    public Result getKnowledge(){
        List<KnowledgeBase> list= knowledgeBaseService.getKnowledgeList();
        return Result.ok(list);
    }
    @Operation(summary = "知识库详情（含文件与入库状态）")
    @GetMapping("/{id}")
    public Result getKnowledgeById(@PathVariable("id") Integer id){
        KnowledgeDetailVO detailVO=knowledgeBaseService.getKnowledgeById(id);
        return Result.ok(detailVO);
    }


}
