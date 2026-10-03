package com.huzhijian.nexusagentweb.lexiang;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

/**
 * 乐享开放接口的 HTTP 客户端。
 * <p>
 * <b>范围严格限定为「检索」</b>：换 token、列团队、列知识库、AI 检索。
 * 故意不实现文件上传与 AI 问答 —— 前者要三步走腾讯云 COS，后者会把用户
 * 的模型配额与乐享内部模型绑死，两者都不是本项目要的。
 * <p>
 * <b>两个必须遵守的协议细节</b>：
 * <ol>
 *   <li>所有 AI 搜索与写操作都必须带 {@code x-staff-id}，缺失直接报错；</li>
 *   <li>access_token 只有 2 小时有效，且取 token 的接口限频 20 次/10 分钟，
 *       因此 token 缓存交由 {@link LexiangTokenProvider} 负责，
 *       本类<b>每次调用都假定 token 已是新鲜的</b>。</li>
 * </ol>
 */
@Slf4j
@Component
public class LexiangClient {

    private final WebClient webClient;

    public LexiangClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl(LexiangApi.BASE)
                // 乐享是公网服务，超时要给足；虚拟线程下阻塞等待是可接受的
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create()
                                .responseTimeout(Duration.ofSeconds(20))))
                .build();
    }

    /**
     * 换 access_token。
     *
     * @return 新鲜的 access_token
     * @throws IllegalStateException AppSecret 错误或乐享侧限频（调用方应把消息透给用户）
     */
    public String fetchToken(String appKey, String appSecret) {
        String body = JSONUtil.toJsonStr(java.util.Map.of(
                "grant_type", "client_credentials",
                "app_key", appKey,
                "app_secret", appSecret));
        try {
            String raw = webClient.post()
                    .uri(LexiangApi.PATH_TOKEN)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            LexiangApi.TokenResp resp = JSONUtil.toBean(raw, LexiangApi.TokenResp.class);
            if (resp == null || StrUtil.isBlank(resp.getAccess_token())) {
                throw new IllegalStateException("乐享返回的 access_token 为空，请检查 AppKey / AppSecret。");
            }
            log.debug("乐享 access_token 获取成功，有效期 {} 秒", resp.getExpires_in());
            return resp.getAccess_token();
        } catch (WebClientResponseException e) {
            // 400 = app_key 无效；401 = app_secret 错误；429 = 撞了 20 次/10 分钟 的限频
            log.warn("乐享换取 token 失败：status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException(describeTokenError(e), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("乐享换取 token 异常：{}", e.getMessage());
            throw new IllegalStateException("连接乐享失败，请稍后重试：" + e.getMessage(), e);
        }
    }

    private String describeTokenError(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 400) {
            return "AppKey 无效，请到乐享【开发】→【接口凭证管理】核对。";
        }
        if (status == 401) {
            return "AppSecret 错误，请重新填写。";
        }
        if (status == 429) {
            // 这是最容易踩的坑：token 有效期 2 小时，如果不做缓存就会反复换 token 撞限频
            return "获取凭证过于频繁（乐享限制 20 次/10 分钟），请稍后再试。";
        }
        return "乐享返回错误（HTTP " + status + "）。";
    }

    /**
     * 列出某个成员可见的团队。
     * <p>
     * <b>刻意不走 {@code /cgi-bin/v1/kb/teams}</b>：官方在该接口上标注
     * 「AppKey 绑定团队时不可调用此接口」，而绝大多数 AppKey 正是按团队授权的
     * → 接口不报错但返回空，表现为「下拉框永远是空的」。
     * <p>
     * {@code /cgi-bin/v1/staffs/{staff_id}/teams} 走的是「该成员能看到哪些团队」，
     * 不受那条限制。
     *
     * @param staffId 成员账号；为空时用 {@code system-bot}（能看到的是公开范围）
     */
    public List<LexiangApi.TeamNode> listTeams(String accessToken, String staffId, int limit) {
        String effectiveStaff = StrUtil.blankToDefault(staffId, LexiangApi.STAFF_SYSTEM_BOT);
        String raw = webClient.get()
                .uri(builder -> builder.path(String.format(LexiangApi.PATH_STAFF_TEAMS, effectiveStaff))
                        .queryParam("limit", limit)
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .header("x-staff-id", effectiveStaff)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return parseDataArray(raw, "团队");
    }

    /**
     * 列出全部团队（团队管理权限）。
     * <p>
     * ⚠️ <b>AppKey 绑定团队时该接口不可用</b>，所以它只作为
     * {@link #listTeams} 查不到任何团队时的兜底尝试。
     *
     * @return 不可用或无数据时返回空列表，不抛异常
     */
    public List<LexiangApi.TeamNode> listAllTeams(String accessToken, String staffId, int limit) {
        String effectiveStaff = StrUtil.blankToDefault(staffId, LexiangApi.STAFF_SYSTEM_BOT);
        try {
            String raw = webClient.get()
                    .uri(builder -> builder.path(LexiangApi.PATH_TEAMS)
                            .queryParam("limit", limit)
                            .build())
                    .header("Authorization", "Bearer " + accessToken)
                    .header("x-staff-id", effectiveStaff)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseDataArray(raw, "团队");
        } catch (Exception e) {
            log.debug("全量团队列表不可用（AppKey 可能按团队授权），忽略：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 列出某个团队下的知识库。
     *
     * @param teamId 团队 id（乐享的 spaces 接口<b>必填</b>）
     */
    public List<LexiangApi.SpaceNode> listSpaces(String accessToken, String teamId, int limit) {
        if (StrUtil.isBlank(teamId)) {
            throw new IllegalArgumentException("乐享知识库列表需要 teamId：请先在团队列表里选一个团队。");
        }
        String raw = webClient.get()
                .uri(builder -> builder.path(LexiangApi.PATH_SPACES)
                        .queryParam("team_id", teamId)
                        .queryParam("limit", limit)
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return parseDataArray(raw, "知识库");
    }

    /**
     * 知识库检索（<b>纯检索，不调用 LLM</b>）。
     *
     * @param staffId  发起检索的成员账号，作为 x-staff-id；决定能看到哪些内容
     * @param spaceId  指定检索的知识库；传 null 表示检索全站
     * @param topN     返回条数，乐享上限 50
     * @param withScore 是否带回相关性分数
     */
    public List<LexiangApi.SearchHit> search(String accessToken, String staffId, String spaceId,
                                              String query, int topN, boolean withScore) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("query", query);
        // 检索范围：指定知识库就锁死它，不指定就是全站。
        // 乐享最多接受 20 个范围对象，这里最多 1 个。
        if (StrUtil.isNotBlank(spaceId)) {
            body.put("targets", java.util.List.of(java.util.Map.of("type", "space", "id", spaceId)));
        }
        body.put("top_n", Math.min(Math.max(topN, 1), 50));
        if (withScore) {
            body.put("with_score", true);
        }
        try {
            String raw = webClient.post()
                    .uri(LexiangApi.PATH_SEARCH)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    // x-staff-id 必填，缺失乐享直接报错；它同时决定结果里的权限范围
                    .header("x-staff-id", StrUtil.blankToDefault(staffId, LexiangApi.STAFF_SYSTEM_BOT))
                    .bodyValue(JSONUtil.toJsonStr(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            LexiangApi.Envelope<LexiangApi.SearchData> env = JSONUtil.toBean(raw,
                    new cn.hutool.core.lang.TypeReference<LexiangApi.Envelope<LexiangApi.SearchData>>() {
                    }, true);
            if (env == null || env.getCode() == null || env.getCode() != 0) {
                String msg = env == null || env.getMessage() == null ? "未知错误" : env.getMessage();
                throw new IllegalStateException("乐享检索失败：" + msg);
            }
            if (env.getData() == null || env.getData().getList() == null) {
                return List.of();
            }
            return env.getData().getList();
        } catch (IllegalStateException e) {
            throw e;
        } catch (WebClientResponseException e) {
            log.warn("乐享检索失败：status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException(describeSearchError(e), e);
        } catch (Exception e) {
            log.warn("乐享检索异常：{}", e.getMessage());
            throw new IllegalStateException("检索乐享知识库失败：" + e.getMessage(), e);
        }
    }

    private String describeSearchError(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return "凭证已失效，请重新保存 AppKey / AppSecret。";
        }
        if (status == 403) {
            return "没有权限访问该知识库：请确认 AppKey 的授权范围是否包含它。";
        }
        if (status == 429) {
            return "乐享接口调用超限，请稍后重试。";
        }
        return "乐享返回错误（HTTP " + status + "）。";
    }

    /**
     * 解析 JSON:API 风格的 data 数组。
     */
    private <T> List<T> parseDataArray(String raw, String what) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        try {
            var envelope = JSONUtil.toBean(raw,
                    new cn.hutool.core.lang.TypeReference<LexiangApi.Envelope<java.util.List<T>>>() {
                    }, true);
            if (envelope == null || envelope.getData() == null) {
                return List.of();
            }
            return envelope.getData();
        } catch (Exception e) {
            log.warn("解析乐享{}列表失败：{}", what, e.getMessage());
            return List.of();
        }
    }
}
