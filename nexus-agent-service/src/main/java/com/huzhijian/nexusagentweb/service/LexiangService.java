package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.LexiangCredential;
import com.huzhijian.nexusagentweb.lexiang.LexiangApi;
import com.huzhijian.nexusagentweb.lexiang.LexiangTokenProvider;
import com.huzhijian.nexusagentweb.vo.LexiangSpaceVO;
import com.huzhijian.nexusagentweb.vo.LexiangTeamVO;

import java.util.List;

/**
 * 乐享知识库接入服务。
 */
public interface LexiangService {

    /**
     * 保存（或覆盖）当前用户的乐享凭证。
     * <p>
     * AppSecret 会用 EncryptorFactory 加密后落库，明文不入库。
     */
    void save(Long userId, String appKey, String appSecret, String staffId,
              String defaultTeamId, String defaultSpaceId);

    /**
     * 取当前用户的凭证（含密文 AppSecret），未配置时返回 null。
     */
    LexiangCredential getByUserId(Long userId);

    /**
     * 列出用户可访问的团队。
     */
    List<LexiangTeamVO> listTeams(Long userId);

    /**
     * 列出某团队下的知识库。
     */
    List<LexiangSpaceVO> listSpaces(Long userId, String teamId);

    /**
     * 连通性测试：用用户提交的凭证真实检索一次。
     * <p>
     * 走的是真实链路（换 token + 检索），所以能真正暴露 AppSecret 错误、
     * 授权范围不足、x-staff-id 不存在等问题 —— 比只换 token 更可信。
     *
     * @return 命中条数
     */
    int testConnection(Long userId, String staffId, String spaceId);

    /**
     * 设为默认检索的知识库。
     * <p>
     * 只改 space/team 指向，<b>不动 AppSecret</b> —— 密钥已经存成密文了，
     * 没必要为了换个知识库把它解密再加密一遍（多一次密钥暴露在内存里的机会）。
     */
    void updateDefaultSpace(Long userId, String spaceId, String teamId);

    /**
     * 检索用户的乐享知识库，供 Agent 工具调用。
     *
     * @param spaceId 传 null 则用用户配置的默认知识库
     * @return 命中的片段列表
     */
    List<LexiangApi.SearchHit> search(Long userId, String spaceId, String query, int topN);
}
