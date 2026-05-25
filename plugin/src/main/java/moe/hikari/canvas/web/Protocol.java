package moe.hikari.canvas.web;

/**
 * 协议版本常量（M16 P6.2 引入）。
 *
 * <p>区分两层版本号：</p>
 * <ul>
 *   <li><b>envelope schema version</b>：{@code Envelope.v}（消息壳结构版本，{@code Envelope.of}
 *       中固定写 {@code 2}）。改 envelope 字段（{@code v / op / id / ts / payload}）
 *       才会动这个号。</li>
 *   <li><b>business protocol version</b>：本类的 {@code SUPPORTED_MIN / MAX}（业务 op
 *       / payload schema 的版本）。client 在 auth 帧携 {@code client_v}，server 在 ready
 *       帧回 {@code accepted_v}；不匹配 close 4002。新增 op / 改字段语义就升这个号。</li>
 * </ul>
 *
 * <p>升级流程：v1 → v2 时把 {@code SUPPORTED_MIN=2 SUPPORTED_MAX=2} 改成
 * {@code SUPPORTED_MIN=2 SUPPORTED_MAX=3} 即可在过渡期同时接 v2 + v3 client；
 * 待 v2 client 全部升级再把 MIN 提到 3。</p>
 */
public final class Protocol {

    private Protocol() {}

    /** 服务端可接受的最小 business protocol 版本。 */
    public static final int SUPPORTED_MIN = 2;

    /** 服务端可接受的最大 business protocol 版本。 */
    public static final int SUPPORTED_MAX = 2;

    /** 协议版本不匹配时关闭 WS 的 close code（与 4001 auth_timeout 同 4xxx 段）。 */
    public static final int CLOSE_PROTOCOL_VERSION_UNSUPPORTED = 4002;

    /**
     * Token 暴力枚举超过限流阈值时关闭 WS 的 close code（2026-05-25 引入，
     * 配合 {@link TokenRateLimiter}）。沿用 HTTP 429 语义号段，client 看到 4429 应
     * 显示"请稍后再试"而不是自动重连。
     */
    public static final int CLOSE_TOKEN_RATE_LIMITED = 4429;

    /** 版本是否在可接受范围内（含边界）。 */
    public static boolean isSupported(int clientVersion) {
        return clientVersion >= SUPPORTED_MIN && clientVersion <= SUPPORTED_MAX;
    }
}
