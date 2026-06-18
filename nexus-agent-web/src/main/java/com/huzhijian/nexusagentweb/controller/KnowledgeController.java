package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.dto.KnowledgeDTO;
import com.huzhijian.nexusagentweb.dto.KnowledgeFileDTO;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseService;
import com.huzhijian.nexusagentweb.vo.KnowledgeDetailVO;
import com.huzhijian.nexusagentweb.vo.Result;
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
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping("/file")
    public Result fileInsertKnowledge(@RequestBody @Valid KnowledgeFileDTO knowledgeDTO){
        String msg=knowledgeBaseService.insertKnowledge(knowledgeDTO);
        return Result.ok(msg);
    }
    @PostMapping
    public Result createKnowledge(@RequestBody @Valid KnowledgeDTO knowledgeDTO){
        knowledgeBaseService.createKnowledge(knowledgeDTO);
        return Result.ok();
    }
    @GetMapping("/list")
    public Result getKnowledge(){
        List<KnowledgeBase> list= knowledgeBaseService.getKnowledgeList();
        return Result.ok(list);
    }
    @GetMapping("/{id}")
    public Result getKnowledgeById(@PathVariable("id") Integer id){
        KnowledgeDetailVO detailVO=knowledgeBaseService.getKnowledgeById(id);
        return Result.ok(detailVO);
    }


}
