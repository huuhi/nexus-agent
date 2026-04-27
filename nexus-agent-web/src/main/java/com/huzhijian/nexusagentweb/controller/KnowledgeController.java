package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.dto.KnowledgeDTO;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseService;
import com.huzhijian.nexusagentweb.vo.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    public Result fileInsertKnowledge(@RequestBody @Valid KnowledgeDTO knowledgeDTO){
        String msg=knowledgeBaseService.insertKnowledge(knowledgeDTO.fileIds(),knowledgeDTO.knowledgeId());
        return Result.ok(msg);
    }
}
