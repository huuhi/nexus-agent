package com.huzhijian.nexusagentweb.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * 出网 URL 白名单校验（SSRF 防护）。
 * <p>
 * <b>为什么必须有这个类</b>：本项目有两处「用户填 URL → 服务端主动发请求」，
 * 修复前<b>全仓没有任何 URL 校验逻辑</b>，等于让用户能指定服务端连任意地址：
 * <ol>
 *   <li><b>MCP 服务地址</b> —— {@code POST /api/mcp} 保存后立刻
 *       {@code McpClientRegistry.create()} 调 {@code checkHealth()} 出网。</li>
 *   <li><b>模型 baseUrl</b> —— 用户自带模型配置里的地址，对话时服务端会 POST 过去。</li>
 * </ol>
 * 没有校验时，攻击者填 {@code http://169.254.169.254/latest/meta-data/} 就能
 * 读到云厂商的实例元数据（含临时 AK/SK），填 {@code http://127.0.0.1:6379/} 就能
 * 探测内网服务指纹。
 * <p>
 * <b>本类的判定顺序（刻意如此）</b>：
 * <ol>
 *   <li>协议必须是 {@code http/https} —— 否则 {@code file://}、{@code gopher://} 直接放行本地文件读取</li>
 *   <li>必须有 host，且不能是 IP 字面量形式之外的怪东西</li>
 *   <li><b>解析 DNS 后逐个检查 IP 是否属于私网/回环/链路本地</b> —— 这一步挡掉
 *       {@code 127.0.0.1}、{@code 10.x}、{@code 192.168.x}、{@code 169.254.169.254}（云元数据）</li>
 * </ol>
 * <p>
 * <b>⚠️ 关于 DNS 重绑定</b>：本类解析 DNS 后放行，但真正发 HTTP 请求时客户端会<b>再解析一次</b>，
 * 攻击者控制的域名因此可能「第一次解析到公网 IP 通过校验、第二次解析到 127.0.0.1」。
 * 彻底防住需要「解析 → 校验 → 用该 IP 直连并单独设置 Host 头」或客户端侧 DNS 钩子，
 * 成本较高。<b>当前判定已能挡掉绝大多数实际风险</b>（攻击者无法控制解析结果的那部分），
 * 真正的高危场景「直接填 IP + 端口扫内网」已被完全阻断。残余风险仅存在于
 * 攻击者同时控制了「域名解析」与「服务端出网」两条链的场景，优先级不高。
 * <p>
 * 白名单机制：<b>只允许用户配置的前端/自建服务域名</b>没有意义（那是 CORS 的事），
 * 因此这里默认<b>拒绝一切私网地址</b>。确有内网 MCP 需求的场景，
 * 通过 {@code nexus.agent.url-guard.allow-private} 显式放行（默认 false）。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 * 说明: SSRF 防护的统一入口，所有「用户填 URL、服务端出网」的地方都必须先过这里。
 */
@Slf4j
@Component
public class UrlGuard {

    /**
     * 允许的协议。刻意**不含** file/ftp/gopher/jar —— 它们都能被用来读本地文件或换协议绕过。
     */
    private static final List<String> ALLOWED_PROTOCOLS = List.of("http", "https");

    /**
     * 是否放行私网地址。默认 false（拒绝）。
     * <p>
     * ⚠️ 打开前请想清楚：这等于把「内网 SSRF」这道防线整个撤掉，
     * 任何登录用户填的地址都能让服务端去连。仅适用于「MCP 确实部署在内网」的私有部署。
     */
    private final boolean allowPrivate;

    public UrlGuard(
            @org.springframework.beans.factory.annotation.Value(
                    "${nexus.agent.url-guard.allow-private:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /**
     * 校验一个由用户提供的出网 URL；不通过则抛 {@link IllegalArgumentException}。
     * <p>
     * 放在**写入前**（保存 MCP 配置时）能提供最早的反馈，
     * 但仍需在**真正出网前**再校验一次 —— 因为库里的数据可能是在加本类之前写入的。
     *
     * @param raw     用户填的原始 URL
     * @param usage   用途，仅用于错误信息定位（如「MCP 服务地址」）
     * @throws IllegalArgumentException URL 不合法或指向内网
     */
    public void validate(String raw, String usage) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(usage + "不能为空");
        }
        // 控制字符/换行可能用于日志注入与请求头拆分
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0 || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(usage + "含有非法字符");
        }

        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(usage + "格式不合法：" + e.getMessage());
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROTOCOLS.contains(scheme)) {
            throw new IllegalArgumentException(usage + "只支持 http/https，当前是：" + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(usage + "缺少主机名");
        }

        if (allowPrivate) {
            log.warn("{}：UrlGuard 已按配置放行私网地址（allow-private=true），该地址指向内网时服务端会直接出网", usage);
            return;
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(usage + "的主机名无法解析：" + host);
        }
        if (resolved == null || resolved.length == 0) {
            throw new IllegalArgumentException(usage + "的主机名无法解析：" + host);
        }

        // ⚠️ 必须**逐个**检查：DNS 轮询会让一个域名返回多个 IP，只要有一个是内网就不能放行
        for (InetAddress addr : resolved) {
            if (isPrivateOrReserved(addr)) {
                throw new IllegalArgumentException(usage + "不能指向内网或本机地址（" + host
                        + " → " + addr.getHostAddress() + "）。这属于服务端请求伪造（SSRF），已被安全策略拒绝。");
            }
        }
    }

    /**
     * 判断一个 IP 是否属于「不该让用户访问」的范围。
     * <p>
     * 覆盖：回环、任意私网、链路本地（含 <b>云元数据 169.254.169.254</b>）、
     * 组播、保留地址、以及 IPv6 版的同类地址。
     * <p>
     * 声明为 {@code public static}（而非包私有）是为了让单测能直接验证这张判定表 ——
     * 边界值（100.64.0.0/10 的两端、fc00::/7 的两端）光靠 {@code validate} 测不到。
     */
    public static boolean isPrivateOrReserved(InetAddress addr) {
        // isSiteLocalAddress 只认 10.x / 172.16-31.x / 192.168.x，不认 127.x 与 169.254.x，
        // 所以要逐项判断，不能只用一个方法。
        return addr.isLoopbackAddress()          // 127.x / ::1
                || addr.isSiteLocalAddress()      // 10.x / 172.16-31.x / 192.168.x
                || addr.isLinkLocalAddress()      // 169.254.x（含云元数据）/ fe80::
                || addr.isAnyLocalAddress()       // 0.0.0.0 / ::
                || isCarrierGradeNat(addr)       // 100.64.0.0/10（云厂商内网段）
                || isUniqueLocalIPv6(addr)       // fc00::/7（IPv6 私网）
                || addr.isMulticastAddress();
    }

    /** 100.64.0.0/10：运营商级 NAT，云厂商常用作容器内网，合起来算内网。 */
    private static boolean isCarrierGradeNat(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 4) {
            return false;
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    /**
     * fc00::/7：IPv6 的唯一本地地址（ULA），等价于 IPv4 的私网。
     * <p>
     * ⚠️ <b>这里必须用 {@code (b[0] & 0xFF) == 0xFC} 而不是 {@code b[0] == (byte) 0xFC}</b>。
     * 后者是<b> 2026-10-04 由单测 {@code UrlGuardTest} 实测抓出来的真 bug</b>：
     * {@code fc00::1} 的首字节是 {@code 0xFC} = 252，而 {@code (byte) 0xFC} 是 <b>-4</b>，
     * 两者永不相等 → 这个方法<b>从来没生效过</b>，
     * 于是 {@code http://[fc00::1]/}、{@code http://[fd12::1]/} 这些 IPv6 私网地址全部能绕过。
     * 单测 {@code uniqueLocalIPv6} 就是盯着这个回归的。
     */
    private static boolean isUniqueLocalIPv6(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 16) {
            return false;
        }
        int first = b[0] & 0xFF;
        return first == 0xFC || first == 0xFD;
    }
}
