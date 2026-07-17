import { onScopeDispose } from 'vue';
import { useTimelineStore } from '@/stores/timeline';
import { mapTime } from '@/timeline/interpolation';

/**
 * 时间轴本地预览的自动播放循环（rAF）。
 *
 * <p>scrubber 手动拖播放头在 dock 组件内直接处理（setPointerCapture + pxToMs）；本 composable
 * 只管"播放按钮 → rAF 按真实墙钟推进 playheadMs（经 loopMode 映射）→ 喂本地预览"。纯前端、
 * 不发 WS（编辑期预览，docs/timeline.md §6.3 / §8.2）；游戏内输出永远后端 Ticker 权威。</p>
 *
 * <p>播放态写进 timeline store（{@code playheadMs}/{@code playing}/{@code previewActive}），
 * 这些刻意不放 project.state，使 scrubber/播放每帧只触发 CanvasView 的浅 watch 而非 deep watch。</p>
 */
export function useTimelinePlayback() {
    const store = useTimelineStore();
    let rafId: number | null = null;
    let lastTs = 0;
    let accMs = 0;   // 从播放起点单调累计的位置（未经 loop 映射）

    function tick(ts: number): void {
        const tl = store.activeTimeline;
        if (!tl || !store.playing) { stop(); return; }
        const dt = lastTs ? ts - lastTs : 0;
        lastTs = ts;
        accMs += dt;
        // ONCE：播到末帧即停（停在 durationMs，不回绕）
        if (tl.loopMode === 'once' && accMs >= tl.durationMs) {
            store.setPlayhead(tl.durationMs);
            stop();
            return;
        }
        store.setPlayhead(mapTime(accMs, tl.durationMs, tl.loopMode));
        rafId = requestAnimationFrame(tick);
    }

    /** 从当前播放头续播（停在末帧时从 0 重起）。 */
    function play(): void {
        if (store.playing && rafId !== null) return;
        const tl = store.activeTimeline;
        if (!tl || tl.durationMs <= 0) return;
        // pingPong 续播相位按正程近似（同一 playhead 在回程相位会跳到正程同位置——可接受小瑕疵）
        accMs = store.playheadMs >= tl.durationMs ? 0 : store.playheadMs;
        lastTs = 0;
        store.setPreviewActive(true);
        store.setPlaying(true);
        if (rafId === null) rafId = requestAnimationFrame(tick);
    }

    /** 暂停：停 rAF，停在当前帧（保留 previewActive 仍显示插值态）。 */
    function pause(): void {
        stop();
    }

    function stop(): void {
        if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
        store.setPlaying(false);
        lastTs = 0;
    }

    /** 退出预览回到基值 state（停 + previewActive=false + playhead 归 0）。 */
    function exitPreview(): void {
        stop();
        store.setPreviewActive(false);
        store.setPlayhead(0);
    }

    onScopeDispose(() => { if (rafId !== null) cancelAnimationFrame(rafId); });

    return { play, pause, stop, exitPreview };
}
