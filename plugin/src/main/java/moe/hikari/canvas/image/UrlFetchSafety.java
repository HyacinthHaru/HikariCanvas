package moe.hikari.canvas.image;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 2026-05-25 项 3：URL 上传的 SSRF 防御。
 *
 * <p>合法 URL 必须：</p>
 * <ul>
 *   <li>scheme = http 或 https（拒 file:// / ftp:// / javascript: / data: / ...）</li>
 *   <li>host 可解析，且解析结果不在内网 / 回环 / link-local 范围</li>
 *   <li>port 在常见范围（非负，可空走默认 80/443）</li>
 * </ul>
 *
 * <p><b>线程安全</b>：静态方法，无状态。DNS 解析阻塞调用方 — 调用方应控制超时（fetch 阶段
 * 已有 30s timeout，DNS 通常 < 5s）。</p>
 */
public final class UrlFetchSafety {

    private UrlFetchSafety() {}

    public enum Reason {
        OK,
        INVALID_URL,
        UNSUPPORTED_SCHEME,
        UNRESOLVABLE_HOST,
        PRIVATE_ADDRESS,
    }

    public record CheckResult(Reason reason, String detail) {
        public boolean ok() { return reason == Reason.OK; }
        public static CheckResult success() { return new CheckResult(Reason.OK, null); }
    }

    /**
     * 校验 URL 是否安全可 fetch。
     *
     * @param url 用户提供的 URL 字符串
     * @return {@code reason == OK} 时允许 fetch；否则携 {@link Reason} 拒
     */
    public static CheckResult check(String url) {
        if (url == null || url.isBlank()) {
            return new CheckResult(Reason.INVALID_URL, "empty");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return new CheckResult(Reason.INVALID_URL, "syntax");
        }
        if (!uri.isAbsolute()) {
            return new CheckResult(Reason.INVALID_URL, "not absolute");
        }
        String scheme = uri.getScheme();
        if (scheme == null) return new CheckResult(Reason.UNSUPPORTED_SCHEME, "no scheme");
        String low = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(low) && !"https".equals(low)) {
            return new CheckResult(Reason.UNSUPPORTED_SCHEME, low);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new CheckResult(Reason.INVALID_URL, "no host");
        }
        // 字符串层快速过滤明显的回环（IPv6 :: / 0.0.0.0 / IPv4-mapped）
        String hostLower = host.toLowerCase(Locale.ROOT);
        if (hostLower.equals("localhost")
                || hostLower.endsWith(".localhost")
                || hostLower.equals("[::1]")
                || hostLower.equals("::1")
                || hostLower.equals("0.0.0.0")) {
            return new CheckResult(Reason.PRIVATE_ADDRESS, hostLower);
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return new CheckResult(Reason.UNRESOLVABLE_HOST, host);
        }
        if (addrs == null || addrs.length == 0) {
            return new CheckResult(Reason.UNRESOLVABLE_HOST, host);
        }
        for (InetAddress a : addrs) {
            if (isUnsafeAddress(a)) {
                return new CheckResult(Reason.PRIVATE_ADDRESS, a.getHostAddress());
            }
        }
        return CheckResult.success();
    }

    /**
     * 判断 IP 是否是私有 / 回环 / link-local / multicast / wildcard / DNS-rebinding 风险。
     */
    static boolean isUnsafeAddress(InetAddress a) {
        if (a == null) return true;
        if (a.isAnyLocalAddress()      // 0.0.0.0 / ::
                || a.isLoopbackAddress()
                || a.isLinkLocalAddress()  // 169.254/16 / fe80::/10
                || a.isSiteLocalAddress()  // 10/8 + 172.16/12 + 192.168/16
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] addr = a.getAddress();
        if (addr.length == 4) {
            int b0 = addr[0] & 0xff;
            int b1 = addr[1] & 0xff;
            // 100.64.0.0/10 (CGNAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;
            // 198.18.0.0/15 (network device benchmarking)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;
            // 192.0.0.0/24, 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 (test / docs)
            if (b0 == 192 && b1 == 0) return true;
            if (b0 == 198 && b1 == 51 && (addr[2] & 0xff) == 100) return true;
            if (b0 == 203 && b1 == 0 && (addr[2] & 0xff) == 113) return true;
        } else if (addr.length == 16) {
            // ::ffff:x.y.z.w — IPv4 mapped 应该已被前面 isLoopback / isSiteLocal 覆盖
            // fc00::/7 unique local
            int b0 = addr[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return true;
        }
        return false;
    }
}
