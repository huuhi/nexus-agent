package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.TypeReference;
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
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import com.huzhijian.nexusagentweb.vo.McpDetailVO;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    /**
     * 出网 URL 校验（SSRF 防护）。
     * <p>在**保存时**先校验一次，让用户当场拿到明确报错；
     * {@link McpClientRegistry} 在真正建连前还会再校验一次（覆盖历史脏数据）。
     */
    private final UrlGuard urlGuard;

    public McpInformationServiceImpl(HttpUtils httpUtils, McpInformationMapper mcpInformationMapper,
                                     UserConfigService userConfigService, McpClientRegistry mcpClientRegistry,
                                     UrlGuard urlGuard) {
        this.httpUtils = httpUtils;
        this.mcpInformationMapper = mcpInformationMapper;
        this.userConfigService = userConfigService;
        this.mcpClientRegistry = mcpClientRegistry;
        this.urlGuard = urlGuard;
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
        // ⚠️ 2026-10-04：这两个 null/空之前会一路传到 EncryptorFactory 里炸成
        // IllegalStateException / NPE，报错完全看不出是「用户没配 MCP token」。
        // 这里提前拦下并说人话。
        if (salt == null || salt.isBlank() || mcpToken == null || mcpToken.isBlank()) {
            throw new ValidationException("尚未配置 MCP Token，请先在「用户设置」里保存 MCP Token");
        }
        String rawToken = EncryptorFactory.decryptChecked(salt, mcpToken, "MCP Token");


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
//        🔴 2026-10-05：标出「哪些已经添加过」。
//        以前这个接口只回模板，前端无从判断，于是已添加的条目照样显示「添加」，
//        用户点一次就多插一条（后端虽然有 strId 去重，但 strId 一旦缺失/带空格就整个失效）。
//        现在由后端直接给出答案，前端只看 added 一个字段。
        markAdded(body, userId);
        return body;
    }

    /**
     * 给预置模板打上「当前用户是否已添加」的标记。
     * <p>
     * 匹配用 <b>strId 优先、url 兜底</b>两级：
     * <ul>
     *   <li>strId 是供应方的稳定标识（{@code (user_id, str_id)} 上有唯一约束），最可靠；</li>
     *   <li>但历史脏数据可能 {@code str_id IS NULL}，或前后带空格 —— 这时退到 url 比对，
     *      同一个服务地址不应该被重复登记。</li>
     * </ul>
     */
    private void markAdded(List<McpServerItemVO> presets, Long userId) {
        Map<String, Long> byStrId = new HashMap<>();
        Map<String, Long> byUrl = new HashMap<>();
        for (McpInformation m : query().eq("user_id", userId).list()) {
            String strId = blankToNull(m.getStrId());
            if (strId != null) {
                // 同一 strId 有多条历史行时保留 id 最小的那条，行为可预期
                byStrId.merge(strId, m.getId(), (a, b) -> Math.min(a, b));
            }
            String url = blankToNull(m.getUrl());
            if (url != null) {
                byUrl.merge(normalizeUrl(url), m.getId(), (a, b) -> Math.min(a, b));
            }
        }
        for (McpServerItemVO item : presets) {
            Long localId = matchExistingId(blankToNull(item.getStrId()), blankToNull(item.getUrl()),
                    byStrId, byUrl);
            if (localId != null) {
                item.setAdded(true);
                item.setLocalId(String.valueOf(localId));
            } else {
                item.setAdded(false);
                item.setLocalId(null);
            }
        }
    }

    /**
     * 在「当前用户已有的配置」里找一条与待添加项对应的记录。
     *
     * @return 命中的本地主键；没有则 null（表示这是新服务）
     */
    private static Long matchExistingId(String strId, String url,
                                        Map<String, Long> byStrId, Map<String, Long> byUrl) {
        if (strId != null) {
            Long hit = byStrId.get(strId);
            if (hit != null) {
                return hit;
            }
        }
        if (url != null) {
            return byUrl.get(normalizeUrl(url));
        }
        return null;
    }

    /**
     * URL 归一化：只去首尾空白与末尾斜杠。
     * <p>
     * ⚠️ <b>刻意不转小写</b>：URL 的 path 部分大小写敏感，统一小写会把两个不同的服务误判成同一个。
     */
    private static String normalizeUrl(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public void saveMcp(List<McpServerItemDTO> mcPs) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        if (mcPs == null || mcPs.isEmpty()) {
            return;
        }
//      应该要过滤一下，如果已经在数据库中存在，则执行更新
        List<McpInformation> existMCPs = query().eq("user_id", userId).list();
//        需要添加的MCP服务ID
        List<McpInformation> list=new ArrayList<>();
//        更新
        List<McpInformation> updateList=new ArrayList<>();

//        收集。⚠️ 2026-10-04 加了 filter + merge 函数：
//        ① 库里可能有 str_id IS NULL 的历史行。PostgreSQL 的唯一约束里 NULL 与 NULL 不算冲突
//           （uk_mcp_user_strid 拦不住），所以这种行可能**有多条**；
//           而 Collectors.toMap 碰到 null key 直接抛 NPE，碰到重复 key 抛 IllegalStateException
//           —— 两种都在「用户点一下保存」时炸成 500。strId 为空的行本来就无法参与匹配，直接跳过。
//        ② merge 时保留 id 最小的那条，保证同一 strId 有多个历史行时行为可预期。
//        🔴 2026-10-05 升级为 **strId + url 两级匹配**：
//           原来只认 strId，前端一旦漏传（预置列表里 id 被刻意置空，很容易丢），
//           就落进 else 分支直接 insert —— 而 str_id 为 NULL 时唯一约束又不生效，
//           于是同一个服务能被无限插重复行。现在 strId 匹配不上还会用 url 再兜一次。
        Map<String, Long> byStrId = new HashMap<>();
        Map<String, Long> byUrl = new HashMap<>();
        for (McpInformation m : existMCPs) {
            String strId = blankToNull(m.getStrId());
            if (strId != null) {
                byStrId.merge(strId, m.getId(), (a, b) -> Math.min(a, b));
            }
            String url = blankToNull(m.getUrl());
            if (url != null) {
                byUrl.merge(normalizeUrl(url), m.getId(), (a, b) -> Math.min(a, b));
            }
        }
        for (McpServerItemDTO mcp : mcPs) {

//            相同服务
            McpInformation mcpInformation = transformMcpInformation(mcp,userId);
            Long existingId = matchExistingId(blankToNull(mcp.strId()), blankToNull(mcp.url()),
                    byStrId, byUrl);
            if (existingId != null) {
//                相同：走更新，绝不新增重复行
                mcpInformation.setAvailable(true);
                mcpInformation.setId(existingId);
                updateList.add(mcpInformation);
            }else{
                list.add(mcpInformation);
            }
        }
        if (!updateList.isEmpty()) {
            // 更新的配置可能与已建连接不符，逐个作废缓存
            updateList.forEach(mcp -> {
                int updated = mcpInformationMapper.updateMCP(mcp);
                if (updated == 0) {
                    // 并发删除 / 越权：不能当成功，否则用户以为自己配好了，其实没有
                    log.warn("更新 MCP 失败（记录不存在或不属于当前用户）：strId={} id={}", mcp.getStrId(), mcp.getId());
                }
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
        List<McpServerItemVO> vos = BeanUtil.copyToList(list, McpServerItemVO.class);
        // 这个列表本身就是「已添加」的：与 GET /api/mcp/service 的 added 字段保持同一套语义，
        // 前端不用再区分两个接口各返回什么
        vos.forEach(vo -> {
            vo.setAdded(true);
            vo.setLocalId(vo.getId());
        });
        return vos;
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
        McpDetailVO vo = BeanUtil.copyProperties(mcpInformation, McpDetailVO.class);
        // ⚠️ 实体里 header 是 jsonb 的**文本**，直接透传给前端会变成一个 JSON 字符串
        // （前端得自己再 parse 一次）。这里解析成对象，和 DTO 的 Map 形态对齐。
        vo.setHeader(parseHeader(mcpInformation.getHeader()));
        return vo;
    }

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录");
        }
        return userId;
    }

    private McpInformation transformMcpInformation(McpServerItemDTO mcp,Long userId) {
        // ⚠️ 2026-10-04：先校验 URL 再入库，防 SSRF（用户填内网地址/云元数据地址）。
        // 原来这里原样入库，随后 McpClientRegistry.checkHealth() 直接让服务端出网。
        urlGuard.validate(mcp.url(), "MCP 服务地址（" + mcp.name() + "）");
        // ⚠️ 不要打整个 DTO：header 里通常带鉴权 token（用户自己填的凭据），
        // 落到日志里等于凭据多了一份副本。只打标识信息。
        log.debug("登记 MCP 服务：name={} strId={} url={} header字段数={}",
                mcp.name(), mcp.strId(), mcp.url(),
                mcp.header() == null ? 0 : mcp.header().size());
        return McpInformation.builder()
                .type(mcp.type())
                .description(mcp.description())
                .name(mcp.name())
                .id(mcp.id())
                .url(mcp.url())
                .strId(mcp.strId())
                .logoUrl(mcp.logoUrl())
                .userId(userId)
                .header(toHeaderJson(mcp.header()))
                // ⚠️ available 是 boolean NOT NULL：DTO 里不传就是 null，
                // updateMCP 的 `available = #{available}` 会写 null 进去直接违约。
                // 语义上也该是 true —— 用户主动来登记/改配置了，就是想用它。
                .available(mcp.available() == null || mcp.available())
                .build();

    }

    /**
     * 把请求头 Map 序列化成可入库的 jsonb 文本。
     * <p>
     * ⚠️ <b>2026-10-04 修的线上 500 就是这里</b>：原来直接 {@code JSONUtil.toJsonStr(mcp.header())}，
     * 而 hutool 对 {@code null} 入参**返回 {@code null} 而不是字符串 {@code "null"}**。
     * 前端从 {@code GET /api/mcp/service}（服务商预置列表）拿到的条目根本没有 header 字段
     * —— {@code McpServerItemVO} 里就没有它 —— 原样回传 {@code POST /api/mcp} 时 header 为 null，
     * 写成 {@code NULL::jsonb} 撞上 {@code header jsonb NOT NULL} 约束：
     * <pre>ERROR: null value in column "header" of relation "mcp_information" violates not-null constraint</pre>
     * <p>
     * 顺带在这里做两件防御：
     * <ol>
     *   <li><b>拒绝非字符串值</b>：请求头的值最终要拼进 HTTP 头，值是对象/数组时
     *       {@code String::valueOf} 会得到 {@code {k=[a, b]}} 这种非法头值，
     *       与其等下在建连时炸掉，不如入库前就报错。</li>
     *   <li><b>拒绝非法 key</b>：HTTP 头名只允许 token 字符，含空格/换行/冒号的一律拒绝
     *       （换行尤其危险，属于 header 注入）。</li>
     * </ol>
     * 空 Map 归一化成 {@code {}}，与建表 DDL 的 {@code DEFAULT '{}'} 保持一致。
     */
    private static final String EMPTY_HEADER_JSON = "{}";

    private String toHeaderJson(Map<String, Object> header) {
        if (header == null || header.isEmpty()) {
            return EMPTY_HEADER_JSON;
        }
        validateHeaderEntries(header);
        return JSONUtil.toJsonStr(header);
    }

    /** 校验请求头的 key/value 形态，见 {@link #toHeaderJson}。 */
    private void validateHeaderEntries(Map<String, Object> header) {
        for (Map.Entry<String, Object> e : header.entrySet()) {
            String name = e.getKey();
            Object value = e.getValue();
            if (name == null || name.isBlank() || !HEADER_NAME_PATTERN.matcher(name).matches()) {
                throw new ValidationException("MCP 请求头名称不合法：" + name);
            }
            if (!(value instanceof String)) {
                throw new ValidationException("MCP 请求头「" + name + "」的值必须是字符串，当前是 "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
            }
            String v = (String) value;
            if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\0') >= 0) {
                // 换行能让攻击者注入第二个请求头（或伪造整个响应），必须在入库前就挡住
                throw new ValidationException("MCP 请求头「" + name + "」的值含有非法字符（换行/NUL）");
            }
        }
    }

    /**
     * HTTP 头名允许的字符：RFC 7230 的 token 定义（tchar）。
     * 刻意不用 {@code [^{}\s()\[\]<>|,:;"/\\?@=]} 这种「黑名单式」正则 ——
     * 黑名单永远漏字符，而头名直接决定我们会不会被注入。
     */
    private static final Pattern HEADER_NAME_PATTERN =
            Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");

    /**
     * 把入库的 jsonb 文本解析回请求头 Map，供出站建连使用。
     * <p>解析不了（历史脏数据 / 人工改过库）就退化成空 Map —— 建连时少带一个头，
     * 好过整个功能直接不可用。
     */
    public static Map<String, String> parseHeader(String headerJson) {
        if (headerJson == null || headerJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = JSONUtil.toBean(headerJson, new TypeReference<Map<String, Object>>() {
            }, false);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, String.valueOf(v));
                }
            });
            return out;
        } catch (Exception e) {
            log.warn("MCP header 不是合法 JSON，已按空处理：{}", e.getMessage());
            return Map.of();
        }
    }
}




