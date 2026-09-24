package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.McpInformationMapper;
import com.huzhijian.nexusagentweb.mcp.McpClientRegistry;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.utils.HttpUtils;
import com.huzhijian.nexusagentweb.vo.McpDetailVO;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

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
    private final McpClientRegistry mcpClientRegistry;

    public McpInformationServiceImpl(HttpUtils httpUtils, McpInformationMapper mcpInformationMapper,
                                     UserConfigService userConfigService, McpClientRegistry mcpClientRegistry) {
        this.httpUtils = httpUtils;
        this.mcpInformationMapper = mcpInformationMapper;
        this.userConfigService = userConfigService;
        this.mcpClientRegistry = mcpClientRegistry;
    }

    /**
     * 为本次对话构建 MCP 工具提供者。
     * <p>
     * 客户端由 {@link McpClientRegistry} 缓存复用 —— 原实现每次对话都新建且不关闭，
     * 是明确的连接泄漏。连不上的服务会立即关闭连接并标记 available=false。
     * 全部服务都不可用时返回 null（调用方即按「无 MCP」处理）。
     */
    @Override
    public McpResolution getMcp(List<Long> mcpIds, Long userId) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return McpResolution.none();
        }
        List<McpInformation> list = query().eq("user_id", userId)
                .in("id", mcpIds)
                .eq("available", true)
                .list();
        if (list.isEmpty()) {
            // 可能是：用户选的 id 都不属于他 / 之前已被标记为不可用 —— 都按"没有 MCP"处理
            return McpResolution.none();
        }
        List<McpClient> mcpClients = new ArrayList<>();
        List<String> unavailableNames = new ArrayList<>();
        for (McpInformation info : list) {
            McpClient client = mcpClientRegistry.getOrCreate(info);
            if (client == null) {
                // 连不上：标记为不可用，避免每次对话都白白尝试；
                // 同时记下服务名，稍后注入提示词 —— 让模型知道"有但暂时用不了"
                update().set("available", false).eq("id", info.getId()).update();
                unavailableNames.add(info.getName());
            } else {
                mcpClients.add(client);
            }
        }
        if (mcpClients.isEmpty()) {
            log.warn("本次请求的 {} 个 MCP 服务全部不可用：{}", unavailableNames.size(), unavailableNames);
            return new McpResolution(null, List.copyOf(unavailableNames));
        }
        McpToolProvider provider = McpToolProvider.builder()
                .mcpClients(mcpClients)
                .build();
        return new McpResolution(provider, List.copyOf(unavailableNames));
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
            // 更新的配置可能与已建连接不符，逐个作废缓存
            updateList.forEach(mcp -> {
                mcpInformationMapper.updateMCP(mcp);
                mcpClientRegistry.evict(mcp.getId());
            });
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
//        越权修复：必须限定 user_id，否则任何人可用别人的 id 删除其 MCP
        Long userId = requireUserId();
        boolean removed = remove(Wrappers.<McpInformation>lambdaQuery()
                .eq(McpInformation::getId, id)
                .eq(McpInformation::getUserId, userId));
        if (!removed) {
            throw new NotFoundException("MCP 不存在或无权限操作");
        }
        // 配置没了，缓存的连接也要一并关掉
        mcpClientRegistry.evict(id);
    }

    @Override
    public void updateMCPById(McpServerItemDTO mcPs) {
        Long userId = requireUserId();
        if (mcPs.id() == null) {
            throw new ValidationException("更新 MCP 时 id 不能为空！");
        }
//        SQL 侧同样限定了 user_id（见 McpInformationMapper.xml 的 updateMCP）
        McpInformation mcpInformation = transformMcpInformation(mcPs, userId);
        int updated = mcpInformationMapper.updateMCP(mcpInformation);
        if (updated == 0) {
            throw new NotFoundException("MCP 不存在或无权限操作");
        }
        // URL/header 可能已变，旧连接作废
        mcpClientRegistry.evict(mcPs.id());
    }

    @Override
    public McpDetailVO getDetailById(Long id) {
//        越权修复：必须限定 user_id
        Long userId = requireUserId();
        McpInformation mcpInformation = query()
                .eq("id", id)
                .eq("user_id", userId)
                .one();
        if (mcpInformation == null) {
            throw new NotFoundException("MCP 不存在或无权限查看");
        }
        return BeanUtil.copyProperties(mcpInformation, McpDetailVO.class);
    }

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录");
        }
        return userId;
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




