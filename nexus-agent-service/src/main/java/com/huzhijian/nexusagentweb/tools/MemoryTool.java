package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/1
 * 说明: 用户长期记忆工具。
 * <p>
 * 注意职责边界：知识库检索（RAG）已拆分到 {@link RagTool}，
 * 本类只负责"用户长期记忆"的检索与写入，不要在这里再加 RAG 相关方法。
 */
@Component
@Slf4j
public class MemoryTool implements AgentToolSet {

    @Override
    public String key() {
        return "memory";
    }

    @Override
    public String description() {
        return "用户长期记忆：检索用户画像、保存用户偏好";
    }

    private final UserMemoryService memoryService;
    private final String SAVE_USER_MEMORY= """
            用于主动保存用户的长期记忆。
            
            【核心要求】
            1. 必须极度精简，去掉废话和冗余。
            2. 严禁包含“用户”或“他/她”等主语（默认主语即为用户本人）。
            3. 仅保留最核心的属性或偏好，无需展开过于具体的细节列表
            4. 记忆内容是供AI后续读取的，请使用客观、简练的断言式短语，尽量一句话说明白。
            
            【长期记忆范畴】
            - 长期稳定的喜好或厌恶（如：喜欢看科幻片、不喜欢吃辣）
            - 长期习惯或作息（如：习惯晚睡、每周五健身）
            - 长期身份信息（如：职业是程序员、现居北京）
            - 长期目标（如：正在准备考研）
            - 持续性的客观事实（如：养了一只英短猫）
            
            如果执行失败，禁止重复尝试！
            """;
    public MemoryTool(UserMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(name = "search_user_memory",value = "检索用户画像")
    public String searchUserMemory(@P("关键字") String query){
        try {
            List<UserMemoryVO> memory = memoryService.getMemory(query);
            StringBuilder builder = new StringBuilder();
            memory.forEach(memoryVO -> {
                String content = memoryVO.getContent();
                builder.append(content);
            });
            return builder.toString();
        } catch (Exception e) {
            return "错误，请勿重复"+e.getMessage();
        }
    }

    @Tool(name = "save_user_data",value = SAVE_USER_MEMORY)
    public String saveLongMemory(@P("记忆内容，需要符合核心要求") String content,@P(value = "会话ID",required = false) String sessionId){
        Long userId = UserContextHolder.getUserId();
        log.info("用户ID：{}",userId);
        UserMemory userLongMemory =  UserMemory.builder().content(content).userId(userId).source(sessionId).build();
        try {
            memoryService.saveMemory(userLongMemory);
        } catch (Exception e) {
            return "error:"+e.getMessage();
        }
        return "ok";
    }
}
