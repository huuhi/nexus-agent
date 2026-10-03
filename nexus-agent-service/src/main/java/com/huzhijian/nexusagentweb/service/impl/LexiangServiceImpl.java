package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.LexiangCredential;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.lexiang.LexiangApi;
import com.huzhijian.nexusagentweb.lexiang.LexiangClient;
import com.huzhijian.nexusagentweb.lexiang.LexiangTokenProvider;
import com.huzhijian.nexusagentweb.mapper.LexiangCredentialMapper;
import com.huzhijian.nexusagentweb.service.LexiangService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.vo.LexiangSpaceVO;
import com.huzhijian.nexusagentweb.vo.LexiangTeamVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 乐享知识库接入实现。
 * <p>
 * <b>凭证处理约定</b>：AppSecret 用 {@link EncryptorFactory} + 用户 salt 加密后落库，
 * 任何对外接口都不回显明文（见 {@code LexiangCredentialVO}）。
 * 复用的是与「用户自带模型 Key」完全相同的那套机制，不另造加密方案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LexiangServiceImpl extends ServiceImpl<LexiangCredentialMapper, LexiangCredential>
        implements LexiangService {

    private final LexiangClient client;
    private final LexiangTokenProvider tokenProvider;
    private final UserConfigService userConfigService;

    @Override
    public void save(Long userId, String appKey, String appSecret, String staffId,
                     String defaultTeamId, String defaultSpaceId) {
        String salt = requireSalt(userId);
        String encrypted = EncryptorFactory.text(salt).encrypt(appSecret);

        LexiangCredential exist = getByUserId(userId);
        LexiangCredential entity = LexiangCredential.builder()
                .id(exist == null ? null : exist.getId())
                .userId(userId)
                .appKey(appKey)
                .appSecret(encrypted)
                .staffId(StrUtil.blankToDefault(staffId, LexiangApi.STAFF_SYSTEM_BOT))
                .defaultTeamId(StrUtil.emptyToNull(defaultTeamId))
                .defaultSpaceId(StrUtil.emptyToNull(defaultSpaceId))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .build();
        if (exist == null) {
            entity.setCreatedAt(Timestamp.valueOf(LocalDateTime.now()));
            save(entity);
            log.info("用户 {} 首次接入乐享知识库", userId);
        } else {
            updateById(entity);
            log.info("用户 {} 更新乐享知识库凭证", userId);
        }
        // 凭证变了，旧的 access_token 必须作废，
        // 否则会出现「我明明改对了还是 401」的假象
        tokenProvider.evict(appKey);
        // 旧 AppKey 也可能还留着缓存（用户换了 AppKey 的场景）
        if (exist != null && StrUtil.isNotBlank(exist.getAppKey()) && !exist.getAppKey().equals(appKey)) {
            tokenProvider.evict(exist.getAppKey());
        }
    }

    @Override
    public LexiangCredential getByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        return getOne(Wrappers.<LexiangCredential>lambdaQuery().eq(LexiangCredential::getUserId, userId));
    }

    @Override
    public List<LexiangTeamVO> listTeams(Long userId) {
        String token = requireToken(userId);
        List<LexiangApi.SpaceNode> nodes = client.listTeams(token, 50);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
                .map(n -> LexiangTeamVO.builder()
                        .id(n.getId())
                        // 团队列表与知识库列表结构相同（都是 JSON:API），attributes.name 复用
                        .name(n.getAttributes() == null ? null : n.getAttributes().getName())
                        .build())
                .toList();
    }

    @Override
    public List<LexiangSpaceVO> listSpaces(Long userId, String teamId) {
        String token = requireToken(userId);
        List<LexiangApi.SpaceNode> nodes = client.listSpaces(token, teamId, 50);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
                .map(n -> LexiangSpaceVO.builder()
                        .id(n.getId())
                        .name(n.getAttributes() == null ? null : n.getAttributes().getName())
                        .logo(n.getAttributes() == null ? null : n.getAttributes().getLogo())
                        .teamId(teamId)
                        .url("https://lexiangla.com/spaces/" + n.getId())
                        .build())
                .toList();
    }

    @Override
    public int testConnection(Long userId, String staffId, String spaceId) {
        LexiangCredential credential = requireCredential(userId);
        String token = resolveToken(credential);
        // 走一次真实检索：比只换 token 更能暴露授权范围、x-staff-id 等问题
        List<LexiangApi.SearchHit> hits = client.search(token,
                StrUtil.blankToDefault(staffId, credential.getStaffId()),
                StrUtil.blankToDefault(spaceId, credential.getDefaultSpaceId()),
                "测试", 1, false);
        return hits == null ? 0 : hits.size();
    }

    @Override
    public void updateDefaultSpace(Long userId, String spaceId, String teamId) {
        LexiangCredential credential = requireCredential(userId);
        // 只改检索范围，**不重新加密 AppSecret**：
        // 密钥已是密文，没必要为了换个知识库把它解密再加密一遍
        update(Wrappers.<LexiangCredential>lambdaUpdate()
                .eq(LexiangCredential::getUserId, userId)
                .set(LexiangCredential::getDefaultSpaceId, StrUtil.emptyToNull(spaceId))
                .set(LexiangCredential::getDefaultTeamId, StrUtil.emptyToNull(teamId))
                .set(LexiangCredential::getUpdatedAt, Timestamp.valueOf(LocalDateTime.now())));
        log.info("用户 {} 的默认乐享知识库切换为 {}", userId, spaceId);
    }

    @Override
    public List<LexiangApi.SearchHit> search(Long userId, String spaceId, String query, int topN) {
        LexiangCredential credential = requireCredential(userId);
        String token = resolveToken(credential);
        String target = StrUtil.blankToDefault(spaceId, credential.getDefaultSpaceId());
        log.debug("乐享检索：userId={} spaceId={} topN={}", userId, target, topN);
        return client.search(token, credential.getStaffId(), target, query, topN, true);
    }

    /**
     * 取当前用户的有效 access_token（优先用已保存的凭证解密出 AppSecret）。
     */
    private String requireToken(Long userId) {
        return resolveToken(requireCredential(userId));
    }

    private String resolveToken(LexiangCredential credential) {
        String salt = requireSalt(credential.getUserId());
        String plainSecret = EncryptorFactory.text(salt).decrypt(credential.getAppSecret());
        return tokenProvider.getToken(credential.getAppKey(), plainSecret);
    }

    private LexiangCredential requireCredential(Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        LexiangCredential credential = getByUserId(userId);
        if (credential == null) {
            throw new IllegalStateException("尚未接入乐享知识库：请先在设置里填写 AppKey 与 AppSecret。");
        }
        return credential;
    }

    /**
     * 取用户的加密盐值 —— 与模型 Key 加密共用同一个 salt。
     */
    private String requireSalt(Long userId) {
        var userConfig = userConfigService.getUserConfig(userId);
        if (userConfig == null || StrUtil.isBlank(userConfig.getSalt())) {
            // 不能给一个空 salt 去加密：EncryptorFactory 会直接抛 IllegalStateException，
            // 这里的提示要让用户知道该先去配置站点
            throw new IllegalStateException("用户配置不完整（缺少加密盐值），请先保存一次 API 配置。");
        }
        return userConfig.getSalt();
    }
}
