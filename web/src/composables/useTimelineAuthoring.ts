/**
 * 整体关键帧"打 / 更新"的统一执行器。
 *
 * <p>dock 元素行的 + 按钮（{@code addTransformKeyframe}）和画布拖动自动加帧（CanvasView
 * onDragEnd / onElementTransformEnd）共用此 composable，消除两处 upsert 逻辑分叉。纯计划由
 * {@link planTransformUpsert} 算（可测），这里负责发 WS + 乐观本地 mutate——后者消除"改已有
 * 帧"的 WS 往返闪烁（patch 回来值一致，无害）。</p>
 */
import { getWsClient } from '@/network/wsClient';
import { useNetworkStore } from '@/stores/network';
import { useProjectStore } from '@/stores/project';
import { useI18n } from '@/i18n';
import { planTransformUpsert } from '@/components/timeline/timelineLogic';
import type { Timeline } from '@/types/protocol';

const LINEAR_EASING = { type: 'linear' as const };

/**
 * 已经发出去、还没等到服务端回执的 keyframe.add，键 =
 * {@code timelineId|elementId|property|timeMs}。
 *
 * <p>为什么要有它：同一时刻连着打两次帧（双击 + 按钮，或自动加帧下连拖两次），第二次跑
 * {@link planTransformUpsert} 时第一次的 patch 还没回来，本地镜像里查不到那一帧，于是又发
 * 一条 add —— 同一 (属性, 时刻) 就有了两帧。后端不做去重，而取值规则是"取最后一帧"，
 * 更新却打在第一帧上，结果是<b>这个时刻怎么改都不生效</b>，用户只能整组删掉重打。</p>
 *
 * <p>模块级而非实例级：composable 每次调用都新建实例（dock 一个、画布一个），
 * 进行中的 add 必须跨实例共享才拦得住。</p>
 */
const pendingAdds = new Set<string>();

/** 测试用：清掉进行中的 add 记录。 */
export function __resetPendingAddsForTest(): void {
    pendingAdds.clear();
}

export function useTimelineAuthoring() {
    const ws = getWsClient();
    const project = useProjectStore();
    const net = useNetworkStore();
    const { t } = useI18n();

    /** 一条关键帧 op 没成功时提示用户。业务失败走 lastError，不染红连接指示灯。 */
    function reportFailed(err: unknown): void {
        const reason = err instanceof Error ? err.message : String(err);
        const msg = t.value.timeline.opFailed;
        net.lastError = msg;
        net.pushLog('err', `${msg} (${reason})`);
    }

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
        // 整体帧 = 一个用户动作。6 个属性 op 共享同一 coalesceKey → 后端合并成一步撤销
        // （修"撤回只回收 1/6 属性、整体帧块不消失"）。
        const coalesceKey = `integ:${elementId}:${timeMs}`;
        for (const u of plan.updates) {
            // 乐观：直接改 mirror 里该 kf 的 value（reactive → 触发画布重绘）；patch 回来值一致无害
            const kf = track?.find(k => k.id === u.keyframeId);
            const before = kf ? (kf as { value: unknown }).value : undefined;
            if (kf) (kf as { value: number }).value = u.value;
            ws.sendKeyframeUpdate(timeline.id, u.keyframeId, { value: u.value }, coalesceKey)
                .catch((e: unknown) => {
                    // 服务端没收下就把本地值放回去——否则画面显示新值、服务端还是旧值，
                    // 且没有任何提示。只在本地值仍是我们写进去的那个时才回滚（期间落了
                    // 新 patch 的话那才是最新真相）。
                    if (kf && (kf as { value: unknown }).value === u.value) {
                        (kf as { value: unknown }).value = before;
                    }
                    reportFailed(e);
                });
        }
        for (const a of plan.adds) {
            const key = `${timeline.id}|${elementId}|${a.property}|${timeMs}`;
            if (pendingAdds.has(key)) continue;   // 上一条 add 还没落地，别再打一帧
            pendingAdds.add(key);
            ws.sendKeyframeAdd(timeline.id, elementId, a.property, timeMs, a.value, LINEAR_EASING, coalesceKey)
                .catch((e: unknown) => reportFailed(e))
                .finally(() => { pendingAdds.delete(key); });
        }
    }

    return { upsertTransformKeyframe };
}
