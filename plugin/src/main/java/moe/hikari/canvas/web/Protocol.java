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
 * <p>升级流程支持两种：过渡双轨（如 {@code MIN=2 MAX=3} 同时接两版 client）或干净切换
 * （MIN=MAX 同步提升）。<b>0.6 v3 取干净切换</b>（docs/protocol.md「v2 → v3 变更总览」）：
 * 前端 bundle 由插件自带分发，客户端与服务端版本在实际部署中永远匹配；且 v2 编辑器打开含
 * timeline 的工程会在保存时丢弃 {@code timelines}（数据丢失），不留双轨窗口。</p>
 *
 * <p>v4 = 0.7.0 script.*（2026-06-10）：墙脚本 5 op + ready payload {@code scripts} 字段，
 * 同样干净切换。</p>
 *
 * <p>v5 = 0.7.1（2026-06-12）：3 个新触发器（rightClickWall / playerLeaveRange /
 * playerQuit）+ Repeat 动作（有界循环）。Trigger / Action wire union 扩展，同样干净切换。</p>
 *
 * <p>v6 = tween（2026-06-13）：{@link moe.hikari.canvas.script.Action.TweenBlock} 补间动画
 * 包裹积木。Action wire union 新增 {@code "tweenBlock"}（durationMs + easing + body）。
 * 干净切换（MIN=MAX=6），设计总纲 {@code docs/scripting-tween.md} T1-T6。</p>
 */
public final class Protocol {

    private Protocol() {}

    /** 服务端可接受的最小 business protocol 版本。 */
    public static final int SUPPORTED_MIN = 7;

    /** 服务端可接受的最大 business protocol 版本。 */
    public static final int SUPPORTED_MAX = 7;

    /** 协议版本不匹配时关闭 WS 的 close code（与 4001 auth_timeout 同 4xxx 段）。 */
    public static final int CLOSE_PROTOCOL_VERSION_UNSUPPORTED = 4002;

    /**
     * Token 暴力枚举超过限流阈值时关闭 WS 的 close code（2026-05-25 引入，
     * 配合 {@link TokenRateLimiter}）。沿用 HTTP 429 语义号段，client 看到 4429 应
     * 显示"请稍后再试"而不是自动重连。
     */
    public static final int CLOSE_TOKEN_RATE_LIMITED = 4429;

    /**
     * 单会话在 1 分钟内反复触发输入限流（≥5 次 RATE_LIMITED）→ 主动断连的 close code
     * （0.9.3 引入）。用 WebSocket 标准的 1008（policy violation）。client 看到 1008 应
     * 显示"操作过于频繁，已断开"并停止自动重连（与 §3.3 契约一致）。
     */
    public static final int CLOSE_RATE_LIMIT_VIOLATION = 1008;

    /** 版本是否在可接受范围内（含边界）。 */
    public static boolean isSupported(int clientVersion) {
        return clientVersion >= SUPPORTED_MIN && clientVersion <= SUPPORTED_MAX;
    }
}
