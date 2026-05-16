import { computed, ref } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import type { Element } from '@/types/protocol';

/**
 * M8-F：marquee 拖框多选状态机 + bbox 相交计算。
 *
 * - start(pos, additive)：在空白处 mousedown 启动 marquee（shift=additive）
 * - move(pos)：mousemove 更新当前 marquee 矩形
 * - end()：mouseup 结算（< 3px = click 空白；否则做 selection）
 * - cancel()：手动取消（拖出窗口 / 工具切换）
 * - marqueeConfig：暴露给 v-rect 的 config
 */
export function useMarqueeSelection() {
    const project = useProjectStore();
    const ui = useUiStore();

    interface MarqueeState {
        x1: number; y1: number; x2: number; y2: number;
        additive: boolean;
    }
    const marquee = ref<MarqueeState | null>(null);

    function start(pos: { x: number; y: number }, additive: boolean): void {
        marquee.value = { x1: pos.x, y1: pos.y, x2: pos.x, y2: pos.y, additive };
    }

    function move(pos: { x: number; y: number }): boolean {
        if (!marquee.value) return false;
        marquee.value = { ...marquee.value, x2: pos.x, y2: pos.y };
        return true;
    }

    /**
     * end 返回 outcome：
     *   - 'idle' 当前没有活动 marquee
     *   - 'click-empty' 拖动距离 < 3px → 调用方应清编辑态 + 清选中
     *   - 'committed' 已提交多选
     * additive flag 也透出，供调用方判断要不要保留现有选中。
     */
    function end(): { outcome: 'idle' | 'click-empty' | 'committed'; additive: boolean } {
        const m = marquee.value;
        if (!m) return { outcome: 'idle', additive: false };
        marquee.value = null;

        const dx = Math.abs(m.x2 - m.x1);
        const dy = Math.abs(m.y2 - m.y1);
        if (dx < 3 && dy < 3) {
            return { outcome: 'click-empty', additive: m.additive };
        }

        const minX = Math.min(m.x1, m.x2);
        const minY = Math.min(m.y1, m.y2);
        const maxX = Math.max(m.x1, m.x2);
        const maxY = Math.max(m.y1, m.y2);

        const layer = project.activeLayer;
        const hits: string[] = [];
        for (const el of layer.elements) {
            if (!el.visible) continue;
            if (bboxIntersects(el, minX, minY, maxX, maxY)) hits.push(el.id);
        }

        if (m.additive) {
            for (const id of hits) ui.addToSelection(id);
        } else {
            ui.selectMany(hits);
        }
        return { outcome: 'committed', additive: m.additive };
    }

    function cancel(): void {
        marquee.value = null;
    }

    function isActive(): boolean { return marquee.value !== null; }

    function bboxIntersects(el: Element, x1: number, y1: number, x2: number, y2: number): boolean {
        // axis-aligned；rotation 用外接 axis-aligned bbox 近似（M8-F MVP）
        return !(el.x + el.w < x1 || el.x > x2 || el.y + el.h < y1 || el.y > y2);
    }

    const marqueeConfig = computed(() => {
        const m = marquee.value;
        if (!m) return null;
        return {
            x: Math.min(m.x1, m.x2),
            y: Math.min(m.y1, m.y2),
            width: Math.abs(m.x2 - m.x1),
            height: Math.abs(m.y2 - m.y1),
            fill: 'rgba(96, 165, 250, 0.12)',
            stroke: '#60a5fa',
            strokeWidth: 1,
            dash: [4, 3],
            listening: false,
        };
    });

    return {
        marquee,
        marqueeConfig,
        start,
        move,
        end,
        cancel,
        isActive,
    };
}
