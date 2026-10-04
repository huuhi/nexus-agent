package com.huzhijian.nexusagentweb.utils;

import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.properties.AliOssProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 附件 URL 的归属校验。
 * <p>
 * <b>⚠️ 为什么必须有这个类（2026-10-04 安全修复 P1）</b>
 * <p>
 * 聊天的附件地址是<b>前端传过来的</b>（{@code metadata.fileUrl}），
 * 而 {@link FileUtils#getDocument} 会拿这个地址去 OSS <b>用服务端凭证</b>把文件拉下来、
 * 解析成文本喂给模型。修复前 {@code extractOssKeyFromUrl} 只做了一件事：
 * 从 URL 里抠出 path 当作 object key。于是：
 * <pre>
 *   攻击者填 fileUrl = https://{自己的bucket}.oss.../user/file/user_1/2026/10/04/别人的秘密.pdf
 *   → 抠出 key = user/file/user_1/2026/10/04/别人的秘密.pdf
 *   → 服务端用自己的 AccessKey 去下载
 *   → 解析成文本，塞进给模型的 prompt
 * </pre>
 * 只要 bucket 是<b>私有</b>的（本来应该是），用户就能读到他人的文件 —— 因为
 * 校验的是"服务端有没有权限"，而不是"这个文件是不是你的"。
 * <p>
 * 修复分两档，刻意区分开是因为<b>两种附件的目录结构本来就不一样</b>：
 * <ul>
 *   <li><b>文档</b>（{@code fileDir}/user_{userId}/yyyy/MM/dd/xxx.ext，见
 *       {@link AliOssUtil#uploadDocument}）—— 路径里<b>带 userId</b>，
 *       所以能做「这个文件属于你吗」的强校验。</li>
 *   <li><b>图片</b>（{@code imageDir}/{uuid}.ext，见 {@link AliOssUtil#uploadImage}）——
 *       路径里<b>没有 userId</b>（就是靠 UUID 防撞名），
 *       硬要做归属校验只能靠查库，用不着。因此只做 host 校验，
 *       堵住「拿任意外站 URL 让服务端/模型去取」这条路。</li>
 * </ul>
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Slf4j
@Component
public class OssUrlGuard {

    private final AliOssProperties props;

    public OssUrlGuard(AliOssProperties props) {
        this.props = props;
    }

    /**
     * 只校验 host：地址必须落在「我们自己的 OSS」上。
     * <p>
     * 挡的是 SSRF / 数据外带：攻击者不能把
     * {@code http://169.254.169.254/latest/meta-data/} 或
     * {@code http://evil.com/x.pdf} 这种地址塞进来。
     */
    public void validateHost(String url, String usage) {
        if (url == null || url.isBlank()) {
            throw new ValidationException(usage + "：文件地址为空");
        }
        URI uri = parse(url, usage);
        String expectedHost = expectedHost();
        String actualHost = uri.getHost();
        if (actualHost == null) {
            throw new ValidationException(usage + "：文件地址里没有域名（" + url + "）");
        }
        if (!actualHost.equalsIgnoreCase(expectedHost)) {
            log.warn("附件地址 host 不匹配，已拒绝：usage={} 期望={} 实际={}",
                    usage, expectedHost, actualHost);
            throw new ValidationException(usage + "：文件地址不在允许的存储域名下");
        }
    }

    /**
     * 校验 host + 用户归属：文档必须位于 {@code {fileDir}/user_{userId}/} 之下。
     * <p>
     * 路径比较用<b>不区分大小写的精确前缀</b>，而不是 {@code contains(userId)} ——
     * 后者能被 {@code user_1_evil/}、{@code user_12/}（userId=1 的邻居）之类的
     * 构造绕过。
     */
    public void validateOwnedBy(String url, Long userId, String usage) {
        validateHost(url, usage);
        if (userId == null) {
            throw new ValidationException(usage + "：未登录，无法校验文件归属");
        }
        String key = objectKeyOf(url, usage);
        String prefix = props.getFileDir() + "/user_" + userId + "/";
        if (!key.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            log.warn("附件不属于当前用户，已拒绝：userId={} key={}", userId, key);
            // 刻意不说"你不能访问别人的文件"：只回一句"附件不存在"，
            // 不给攻击者一个可以拿来枚举的信号
            throw new ValidationException(usage + "：附件不存在或不属于当前用户");
        }
    }

    /** 期望的访问域名：{@code {bucket}.{endpoint 的 host}}（与 {@link AliOssUtil#getUrl} 拼法一致） */
    private String expectedHost() {
        String endpoint = props.getEndpoint();
        String bucket = props.getBucketName();
        if (endpoint == null || endpoint.isBlank() || bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "spring.aliyun.endpoint / bucket-name 未配置，无法校验附件地址归属");
        }
        try {
            String host = new URI(endpoint).getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("endpoint 不是合法 URL：" + endpoint);
            }
            return bucket + "." + host;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("endpoint 不是合法 URL：" + endpoint, e);
        }
    }

    /** 从 URL 取 object key（去掉开头的 /），并去掉 query / fragment */
    private String objectKeyOf(String url, String usage) {
        URI uri = parse(url, usage);
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new ValidationException(usage + "：文件地址里没有对象路径");
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private URI parse(String url, String usage) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ValidationException(usage + "：文件地址格式非法");
        }
    }
}
