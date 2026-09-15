package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.McpInformationMapper;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.utils.HttpUtils;
import com.huzhijian.nexusagentweb.vo.McpDetailVO;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final UserConfigService userConfigService;

    public McpInformationServiceImpl(HttpUtils httpUtils, McpInformationMapper mcpInformationMapper, UserConfigService userConfigService) {
        this.httpUtils = httpUtils;
        this.mcpInformationMapper = mcpInformationMapper;
        this.userConfigService = userConfigService;
    }

    @Override
    public McpToolProvider getMcp(List<Long> MCPIds,Long userId) {
        if (MCPIds==null|| MCPIds.isEmpty()){
            return null;
        }
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
    public List<McpServerItemVO> getMcpInformationByService() {
//        获取用户的mcp token
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        UserConfig userConfig = userConfigService.getUserConfig(userId);


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
        List<McpServerItemVO> body = response.getBody();
        if (body==null||body.isEmpty()){
            return List.of();
        }
//        这里将服务返回的ID设置为StrID，方便之后添加时 判断更新/添加
        body.forEach(item->{
            item.setStrId(item.getId());
            item.setId("");
        });
        return body;
    }

    @Override
    public void saveMcp(List<McpServerItemDTO> mcPs) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
//      应该要过滤一下，如果已经在数据库中存在，则执行更新
        List<McpInformation> existMCPs = query().eq("user_id", userId).list();
//        需要添加的MCP服务ID
        List<McpInformation> list=new ArrayList<>();
//        更新
        List<McpInformation> updateList=new ArrayList<>();

//        收集
        Map<String, Long> map = existMCPs.stream().collect(Collectors.toMap(McpInformation::getStrId,McpInformation::getId));
        for (McpServerItemDTO mcp : mcPs) {

//            相同服务
            McpInformation mcpInformation = transformMcpInformation(mcp,userId);
            if (map.containsKey(mcp.strId())) {
//                相同
                mcpInformation.setAvailable(true);
                mcpInformation.setId(map.get(mcp.strId()));
                updateList.add(mcpInformation);
            }else{
                list.add(mcpInformation);
            }
        }
        if (!updateList.isEmpty()) {
            updateList.forEach(mcpInformationMapper::updateMCP);
        }
        if (!list.isEmpty()) {
            mcpInformationMapper.saveBatch(list);
        }
    }

    @Override
    public List<McpServerItemVO> getMcpInformation() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        List<McpInformation> list = query().eq("user_id", userId)
                .list();
        return BeanUtil.copyToList(list, McpServerItemVO.class);
    }

    @Override
    public void removeMCP(Long id) {
        removeById(id);
    }

    @Override
    public void updateMCPById(McpServerItemDTO mcPs) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录");
        }
        McpInformation mcpInformation = transformMcpInformation(mcPs, userId);
        mcpInformationMapper.updateMCP(mcpInformation);
    }

    @Override
    public McpDetailVO getDetailById(Long id) {
        McpInformation mcpInformation = getById(id);
        return BeanUtil.copyProperties(mcpInformation,McpDetailVO.class);
    }

    private McpInformation transformMcpInformation(McpServerItemDTO mcp,Long userId) {
        String header = JSONUtil.toJsonStr(mcp.header());
        log.debug("header:{}", mcp);
        return McpInformation.builder()
                .type(mcp.type())
                .description(mcp.description())
                .name(mcp.name())
                .id(mcp.id())
                .url(mcp.url())
                .strId(mcp.strId())
                .logoUrl(mcp.logoUrl())
                .userId(userId)
                .header(header)
                .available(mcp.available())
                .build();

    }
}




