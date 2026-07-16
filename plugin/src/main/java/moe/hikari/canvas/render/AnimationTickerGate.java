package moe.hikari.canvas.render;

/**
 * 分流 gate 查询面（docs/architecture.md §5.1「两条产帧路径」）。
 *
 * <p>{@link ProjectionThrottler} 在 {@code submit} 入口按 wallId 查询：动画接管期间，
 * 编辑 op 产生的 reactive flush 退让给 AnimationTicker（由 Ticker 统一出帧，
 * 避免两条路径对同一 mapId 交错写造成帧序错乱）。</p>
 */
public interface AnimationTickerGate {

    /** 某 wall 当前是否由 AnimationTicker 主动产帧（播放中；暂停 / 未注册 = false）。 */
    boolean isWallAnimating(String wallId);

    /**
     * reactive 产帧意图因动画接管被丢弃时回调。
     * Ticker 实现 = {@code invalidate(wallId)}：下一帧重载 + 全量补发，吸收被丢弃的
     * 渲染意图（编辑 flush / wall.refresh 重挂 ItemFrame 等）。默认 no-op（测试 fake 友好）。
     */
    default void onReactiveYield(String wallId) {
    }
}
