package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.dto.KnowledgeDTO;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseService;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<String> fileInsertKnowledge(@RequestBody @Valid KnowledgeDTO knowledgeDTO){
        String msg=knowledgeBaseService.insertKnowledge(knowledgeDTO.getFileIds(),knowledgeDTO.getKnowledgeId());
        return ResponseEntity.ok(msg);
    }
}
