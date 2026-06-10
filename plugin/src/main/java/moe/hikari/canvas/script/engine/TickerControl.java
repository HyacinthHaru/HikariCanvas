package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.AnimationTicker;

/**
 * {@link AnimationTicker} 的脚本侧门面 seam（T4）。
 *
 * <p>{@code AnimationTicker} 是 final 具象类，引擎侧只用到 6 个入口——抽接口让
 * {@code ActionExecutor}（play/pause/seek）与 {@code ElementPropertyApplier}
 * （isWallAnimating/invalidate/refreshAutoPlay）可注 fake 断言调用，生产装配
 * {@link #of(AnimationTicker)} 一行包装。所有方法沿用 Ticker 的线程契约：任意线程可调。</p>
 */
public interface TickerControl {

    AnimationTicker.Result play(String wallId, String timelineId);

    void pause(String wallId);

    AnimationTicker.Result seek(String wallId, String timelineId, long atMs);

    boolean isWallAnimating(String wallId);

    void invalidate(String wallId);

    void refreshAutoPlay(String wallId);

    /** 生产装配：直通 {@link AnimationTicker}。 */
    static TickerControl of(AnimationTicker ticker) {
        return new TickerControl() {
            @Override
            public AnimationTicker.Result play(String wallId, String timelineId) {
                return ticker.play(wallId, timelineId);
            }

            @Override
            public void pause(String wallId) {
                ticker.pause(wallId);
            }

            @Override
            public AnimationTicker.Result seek(String wallId, String timelineId, long atMs) {
                return ticker.seek(wallId, timelineId, atMs);
            }

            @Override
            public boolean isWallAnimating(String wallId) {
                return ticker.isWallAnimating(wallId);
            }

            @Override
            public void invalidate(String wallId) {
                ticker.invalidate(wallId);
            }

            @Override
            public void refreshAutoPlay(String wallId) {
                ticker.refreshAutoPlay(wallId);
            }
        };
    }
}
