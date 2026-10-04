package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.utils.UrlGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UrlGuard} 的纯单测 —— SSRF 防护的回归网。
 * <p>
 * <b>⚠️ 这里不 mock DNS</b>：刻意直接用 IP 字面量做被测输入。
 * 因为 {@code InetAddress.getAllByName("127.0.0.1")} 对 IP 字面量<b>不查 DNS</b>
 * （它直接构造），所以本测试<b>完全离线可跑</b>，也不会因为外网不可用而变红。
 * 换成域名反而会引入"CI 有没有网"这种不稳定因素。
 * <p>
 * 覆盖的都是<b>真实攻击里出现过</b>的形态，不是假想：
 * <ul>
 *   <li>云元数据 169.254.169.254 —— 拿它能换出实例角色的临时 AK/SK</li>
 *   <li>127.0.0.1 + 端口 —— 扫本机服务（Redis 未授权、数据库、K8s API）</li>
 *   <li>100.64.0.0/10 —— 云厂商容器内网，<b>JDK 的 isSiteLocalAddress 不认它</b>，
 *       漏判等于开了个后门</li>
 *   <li>fc00::/7 —— IPv6 私网，同样不被 isSiteLocalAddress 覆盖</li>
 * </ul>
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@DisplayName("UrlGuard —— 出网 URL 的 SSRF 校验")
class UrlGuardTest {

    private final UrlGuard guard = new UrlGuard(false);
    private static final String USAGE = "MCP 服务地址";

    @Nested
    @DisplayName("协议白名单")
    class Protocols {
        @ParameterizedTest(name = "拒绝 {0}")
        @ValueSource(strings = {
                "file:///etc/passwd",
                "gopher://127.0.0.1:6379/_INFO",
                "ftp://example.com/a.txt",
                "jar:file:///x.jar!/a",
                "dict://127.0.0.1:11211/stats"
        })
        void rejectsNonHttp(String url) {
            assertThrows(IllegalArgumentException.class, () -> guard.validate(url, USAGE));
        }

        @Test
        @DisplayName("没有协议的裸字符串也要拒（否则 new URI 的 host 为 null）")
        void rejectsBareHost() {
            assertThrows(IllegalArgumentException.class, () -> guard.validate("example.com", USAGE));
        }
    }

    @Nested
    @DisplayName("内网 / 保留地址")
    class PrivateAddresses {

        @ParameterizedTest(name = "拒绝 {0}")
        @ValueSource(strings = {
                "http://127.0.0.1/",
                "http://127.0.0.1:6379/",
                "http://127.1.2.3/x",          // 127/8 整段
                "http://localhost:8080/",
                "http://10.0.0.5/",
                "http://172.16.0.1/",
                "http://172.31.255.254/",      // 172.16-31 边界内
                "http://192.168.1.1/",
                "http://0.0.0.0/",
                "http://[::1]/",
                "http://[::]/",
                "http://169.254.169.254/latest/meta-data/",   // 🔴 云元数据
                "http://100.64.0.1/",                          // CGN，JDK 不认，必须自己判
                "http://[fc00::1]/",                           // IPv6 ULA
                "http://224.0.0.1/",                           // 组播
                "http://[ff02::1]/"
        })
        void rejectsPrivateAndReserved(String url) {
            assertThrows(IllegalArgumentException.class, () -> guard.validate(url, USAGE));
        }

        @Test
        @DisplayName("172.32.x 是公网段，不能误伤")
        void allowsJustOutsidePrivateRange() {
            // 172.16.0.0/12 的上界是 172.31.255.255，172.32 起就是公网了
            assertDoesNotThrow(() -> guard.validate("http://172.32.0.1/", USAGE));
            assertDoesNotThrow(() -> guard.validate("http://100.63.0.1/", USAGE));
            assertDoesNotThrow(() -> guard.validate("http://100.128.0.1/", USAGE));
        }

        @Test
        @DisplayName("公网 IP 字面量放行（不该把正常用户挡在门外）")
        void allowsPublicIpLiteral() {
            assertDoesNotThrow(() -> guard.validate("http://8.8.8.8:8080/mcp", USAGE));
        }
    }

    @Nested
    @DisplayName("输入卫生")
    class InputHygiene {

        @Test
        @DisplayName("空 / 空白")
        void rejectsBlank() {
            assertThrows(IllegalArgumentException.class, () -> guard.validate(null, USAGE));
            assertThrows(IllegalArgumentException.class, () -> guard.validate("   ", USAGE));
        }

        @ParameterizedTest(name = "拒绝含控制字符的 {0}")
        @ValueSource(strings = {
                "http://a.com/\r\nHost: evil",
                "http://a.com/\nX-Injected: 1",
                "http://a.com/\u0000"
        })
        void rejectsControlCharacters(String url) {
            // 这些字符能污染日志、也能被下游 HTTP 库当成请求头分隔符
            assertThrows(IllegalArgumentException.class, () -> guard.validate(url, USAGE));
        }

        @Test
        @DisplayName("只有一个 host 没别的（file:///etc/passwd 那类）")
        void rejectsMissingHost() {
            assertThrows(IllegalArgumentException.class, () -> guard.validate("http:///etc/passwd", USAGE));
        }
    }

    @Nested
    @DisplayName("isPrivateOrReserved 的判定表")
    class Predicate {

        @Test
        @DisplayName("CGN 100.64.0.0/10 的两个边界都要算内网")
        void cgnBoundaries() throws Exception {
            assertTrue(UrlGuard.isPrivateOrReserved(InetAddress.getByName("100.64.0.0")));
            assertTrue(UrlGuard.isPrivateOrReserved(InetAddress.getByName("100.127.255.255")));
            // 段外两侧不能算，否则会把真实公网地址误杀
            assertTrue(!UrlGuard.isPrivateOrReserved(InetAddress.getByName("100.63.255.255")));
            assertTrue(!UrlGuard.isPrivateOrReserved(InetAddress.getByName("100.128.0.0")));
        }

        @Test
        @DisplayName("IPv6 ULA fc00::/7 覆盖 fc/fd 两段；fe80:: 是链路本地，同样要拒")
        void uniqueLocalIPv6() throws Exception {
            assertTrue(UrlGuard.isPrivateOrReserved(InetAddress.getByName("fc00::1")));
            assertTrue(UrlGuard.isPrivateOrReserved(InetAddress.getByName("fd12:3456::1")));
            assertTrue(UrlGuard.isPrivateOrReserved(InetAddress.getByName("fe80::1")));
        }

        @Test
        @DisplayName("公网地址不能被判成内网（误杀会让功能直接不可用）")
        void publicAddressesAreNotBlocked() throws Exception {
            assertTrue(!UrlGuard.isPrivateOrReserved(InetAddress.getByName("8.8.8.8")));
            assertTrue(!UrlGuard.isPrivateOrReserved(InetAddress.getByName("140.82.121.4")));
            assertTrue(!UrlGuard.isPrivateOrReserved(InetAddress.getByName("2001:4860:4860::8888")));
        }
    }

    @Nested
    @DisplayName("allow-private 开关")
    class AllowPrivateSwitch {

        @Test
        @DisplayName("开启后内网放行，但仍保留协议白名单")
        void allowPrivateSkipsIpCheckButNotProtocolCheck() {
            UrlGuard lax = new UrlGuard(true);
            assertDoesNotThrow(() -> lax.validate("http://127.0.0.1:6379/", USAGE));
            // 开关只管"内网不拦"，不管"协议放行" —— 放行 file:// 同样是洞
            assertThrows(IllegalArgumentException.class, () -> lax.validate("file:///etc/passwd", USAGE));
            assertThrows(IllegalArgumentException.class, () -> lax.validate(null, USAGE));
        }
    }
}
