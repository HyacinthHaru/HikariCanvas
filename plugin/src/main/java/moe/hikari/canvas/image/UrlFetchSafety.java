package moe.hikari.canvas.image;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URL 上传的最小化校验（0.4.9 hotfix-3 起：用户选择"所有域名均可粘贴"）。
 *
 * <p>合法 URL 必须：</p>
 * <ul>
 *   <li>scheme = http 或 https（仍拒 file:// / ftp:// / javascript: / data: / ...）</li>
 *   <li>URI 语法合法 + host 非空</li>
 * </ul>
 *
 * <p><b>已删除（按用户要求）</b>：DNS 解析 / 私有地址检查 / 回环 / link-local /
 * CGNAT / IPv6 unique-local / localhost 字符串过滤。SSRF 风险由用户自己承担——
 * 本地 HikariCanvas 是单玩家或可信玩家场景，不是公网服务。</p>
 *
 * <p><b>线程安全</b>：静态方法，无状态。</p>
 */
public final class UrlFetchSafety {

    private UrlFetchSafety() {}

    public enum Reason {
        OK,
        INVALID_URL,
        UNSUPPORTED_SCHEME,
        /** 保留 enum 项防外部 caller 模式匹配崩；新代码不再返回此 reason。 */
        UNRESOLVABLE_HOST,
        /** 保留 enum 项防外部 caller 模式匹配崩；新代码不再返回此 reason。 */
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
        // 0.4.9 hotfix-3：删除 DNS 解析 + 私有地址 / 回环 / link-local 黑名单。
        // 用户选择"所有域名均可粘贴"——SSRF 由用户自担。
        return CheckResult.success();
    }
}
