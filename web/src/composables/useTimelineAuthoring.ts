/**
 * 0.6 P4.5b：整体关键帧"打 / 更新"的统一执行器。
 *
 * <p>dock 元素行的 + 按钮（{@code addTransformKeyframe}）和画布拖动自动加帧（CanvasView
 * onDragEnd / onElementTransformEnd）共用此 composable，消除两处 upsert 逻辑分叉。纯计划由
 * {@link planTransformUpsert} 算（可测），这里负责发 WS + 乐观本地 mutate——后者消除"改已有
 * 帧"的 WS 往返闪烁（patch 回来值一致，无害）。</p>
 */
import { getWsClient } from '@/network/wsClient';
import { useProjectStore } from '@/stores/project';
import { planTransformUpsert } from '@/components/timeline/timelineLogic';
import type { Timeline } from '@/types/protocol';

const LINEAR_EASING = { type: 'linear' as const };

export function useTimelineAuthoring() {
    const ws = getWsClient();
    const project = useProjectStore();

    /**
     * 在 timeMs 给 elementId 打一个整体关键帧（6 个 transform 属性，值 = 元素当前值）。
     * 已有帧 → keyframe.update（乐观改本地 value 消闪）；缺的属性 → keyframe.add（该属性此刻本
     * 无帧，interpolate 对它 passthrough 元素当前值，等 patch 回来天然不闪）。
     */
    function upsertTransformKeyframe(timeline: Timeline, elementId: string, timeMs: number): void {
        const el = project.elementById(elementId);
        if (!el) return;
        const plan = planTransformUpsert(timeline, el, timeMs);
        const track = timeline.tracks?.[elementId];
        for (const u of plan.updates) {
            // 乐观：直接改 mirror 里该 kf 的 value（reactive → 触发画布重绘）；patch 回来值一致无害
            const kf = track?.find(k => k.id === u.keyframeId);
            if (kf) (kf as { value: number }).value = u.value;
            ws.sendKeyframeUpdate(timeline.id, u.keyframeId, { value: u.value })
                .catch(() => { /* wsClient 已日志 */ });
        }
        for (const a of plan.adds) {
            ws.sendKeyframeAdd(timeline.id, elementId, a.property, timeMs, a.value, LINEAR_EASING)
                .catch(() => { /* wsClient 已日志 */ });
        }
    }

    return { upsertTransformKeyframe };
}
