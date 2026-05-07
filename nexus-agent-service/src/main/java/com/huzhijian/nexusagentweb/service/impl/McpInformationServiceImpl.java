package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserConfigContextHolder;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.McpInformationMapper;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.utils.HttpUtils;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
* @author windows
* @description 针对表【mcp_information(MCP配置信息)】的数据库操作Service实现
* @createDate 2026-04-21 20:54:09
*/
@Service
@Slf4j
public class McpInformationServiceImpl extends ServiceImpl<McpInformationMapper, McpInformation>
    implements McpInformationService{
    private final HttpUtils  httpUtils;
    private final McpInformationMapper mcpInformationMapper;

    public McpInformationServiceImpl(HttpUtils httpUtils, McpInformationMapper mcpInformationMapper) {
        this.httpUtils = httpUtils;
        this.mcpInformationMapper = mcpInformationMapper;
    }

    @Override
    public McpToolProvider getMcp(List<Long> MCPIds,Long userId) {
        List<McpInformation> list = query().eq("user_id", userId)
                .in("id", MCPIds)
                .eq("available",true)
                .list();
        List<McpClient> mcpClients = list.stream().map(m -> {
            StreamableHttpMcpTransport mcpTransport = StreamableHttpMcpTransport.builder()
                    .url(m.getUrl()).timeout(Duration.ofSeconds(5))
                    .build();
            McpClient mcpClient = DefaultMcpClient.builder()
                    .transport(mcpTransport).build();
            try {
                mcpClient.checkHealth();
            } catch (Exception e) {
                try {
                    mcpClient.close();
                } catch (Exception ex) {
                    update().set("available",false).eq("id",m.getId()).update();
                }
            }
            return mcpClient;
        }).toList();
        return McpToolProvider.builder().mcpClients(mcpClients)
//                .toolNameMapper((client,toolSep)->  +"_"+toolSep.name())
                .build();
    }

    @Override
    public List<McpServerItemVO> getMcpInformation() {
//        获取用户的mcp token
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        UserConfig userConfig = UserConfigContextHolder.getUserConfig();
        if (userConfig == null) {
            throw new UnauthorizedException("未配置");
        }
        String salt = userConfig.getSalt();
        String mcpToken = userConfig.getMcpToken();
        String rawToken = EncryptorFactory.text(salt).decrypt(mcpToken);


//        这边获取mcp列表
        ResponseEntity<List<McpServerItemVO>> response = httpUtils.getWithRaw("/mcp", Map.of("token", rawToken)).toEntityList(McpServerItemVO.class).block();
        if (response==null) {
            throw new RuntimeException("错误");
        }
        return response.getBody();
    }

    @Override
    public void saveMcp(List<McpServerItemDTO> mcPs) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        List<McpInformation> list = mcPs.stream().map(mcp -> {
            String header = JSONUtil.toJsonStr(mcp.header());
            log.debug("header:{}", header);
            return McpInformation.builder()
                    .type(mcp.type())
                    .description(mcp.description())
                    .name(mcp.name())
                    .url(mcp.url())
                    .logoUrl(mcp.logoUrl())
                    .header(header)
                    .userId(userId).build();
        }).toList();
        mcpInformationMapper.saveBatch(list);
    }
}




