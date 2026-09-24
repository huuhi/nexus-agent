package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.vo.McpDetailVO;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import dev.langchain4j.mcp.McpToolProvider;

import java.util.List;

/**
* @author windows
* @description 针对表【mcp_information(MCP配置信息)】的数据库操作Service
* @createDate 2026-04-21 20:54:09
*/
public interface McpInformationService extends IService<McpInformation> {

    /**
     * 解析本次对话要用的 MCP 客户端。
     * <p>
     * 返回 {@link McpResolution} 而不是裸的 {@code McpToolProvider}（P2-9）：
     * 除了"能用的客户端"，还要把**连不上因而被跳过的服务名**带回去 ——
     * 否则模型完全不知道用户配过这些能力，只会回一句"我没有这个能力"，
     * 用户也无从判断是"没配"还是"配了但连不上"。
     *
     * @param mcpIds 本次请求指定的 MCP 配置 id；为空则视为不启用
     * @param userId 当前用户，用于越权过滤
     */
    McpResolution getMcp(List<Long> mcpIds, Long userId);

    /**
     * MCP 解析结果。
     *
     * @param provider        可用客户端组成的 provider；**一个都没有时为 null**
     * @param unavailableNames 本次**选择了但连不上**的服务名（不含"用户根本没选"的）；
     *                         用于在系统提示词里告知模型"该能力当前不可用"
     */
    record McpResolution(McpToolProvider provider, List<String> unavailableNames) {

        /** 没有任何 MCP：provider 为空、也没有不可用项 */
        public static McpResolution none() {
            return new McpResolution(null, List.of());
        }
    }

    List<McpServerItemVO> getMcpInformationByService();

    void saveMcp(List<McpServerItemDTO> mcPs);

    List<McpServerItemVO> getMcpInformation();

    void removeMCP(Long id);

    void updateMCPById(McpServerItemDTO mcPs);

    McpDetailVO getDetailById(Long id);
}
