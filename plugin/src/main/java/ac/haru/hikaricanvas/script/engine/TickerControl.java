package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.state.ProjectState;

/**
 * {@link AnimationTicker} 的脚本侧门面 seam。
 *
 * <p>{@code AnimationTicker} 是 final 具象类，引擎侧只用到 8 个入口——抽接口让
 * {@code ActionExecutor}（play/pause/seek）与 {@code ElementPropertyApplier}
 * （isWallAnimating/invalidate/refreshAutoPlay）以及 {@code TweenScheduler}
 * （renderStatic/clearStaticDiff）可注 fake 断言调用，生产装配
 * {@link #of(AnimationTicker)} 一行包装。所有方法沿用 Ticker 的线程契约：任意线程可调。</p>
 */
public interface TickerControl {

    AnimationTicker.Result play(String wallId, String timelineId);

    void pause(String wallId);

    AnimationTicker.Result seek(String wallId, String timelineId, long atMs);

    boolean isWallAnimating(String wallId);

    void invalidate(String wallId);

    void refreshAutoPlay(String wallId);

    /**
     * 补间引擎用：渲染某 wall 的一帧补间中间态（不落 DB）。任意线程可调。
     * 详见 {@link AnimationTicker#renderStatic}。
     */
    void renderStatic(String wallId, ProjectState frame);

    /**
     * 补间引擎用：清除 per-wall 静态 diff（补间结束时调）。任意线程可调。
     * 详见 {@link AnimationTicker#clearStaticDiff}。
     */
    void clearStaticDiff(String wallId);

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

            @Override
            public void renderStatic(String wallId, ProjectState frame) {
                ticker.renderStatic(wallId, frame);
            }

            @Override
            public void clearStaticDiff(String wallId) {
                ticker.clearStaticDiff(wallId);
            }
        };
    }
}
