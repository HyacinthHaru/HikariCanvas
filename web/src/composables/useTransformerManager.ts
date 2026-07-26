import { nextTick, watch, type Ref } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import type { Element, PathElement } from '@/types/protocol';
import { scalePathD } from '@/render/pathScale';
import { useLockGuard } from './useLockGuard';

interface TransformEvt { target: {
    x: () => number; y: () => number;
    width: () => number; height: () => number;
    scaleX: () => number; scaleY: () => number;
    rotation: () => number;
    scaleX(v: number): void; scaleY(v: number): void;
    width(v: number): void; height(v: number): void;
    rotation(v: number): void;
} }

function normalizeRotation(deg: number): number {
    return ((Math.round(deg) % 360) + 360) % 360;
}

/**
 * Konva Transformer 挂载逻辑 + onTransformEnd 实现（含 PathElement 缩放 d / stroke.width 的特殊路径）。
 *
 * 调用方需提供：
 *   - transformerRef / layerRef：Konva 节点 ref
 *   - elementsWatchSource：getter 返回当前 elements 数组（用于 deep watch 触发 reattach）
 */
export function useTransformerManager(opts: {
    transformerRef: Ref<{ getNode(): unknown } | null>;
    layerRef: Ref<{ getNode(): unknown } | null>;
    elementsWatchSource: () => Element[];
    /**
     * 画面上这个元素<b>现在显示</b>的几何（时间轴预览态下 = 插值结果）。返 null / 不传
     * 表示"显示的就是元素本身的值"。只用来判断"用户到底有没有真的动过锚点"——
     * 预览态下元素基础值与画面不一致，拿基础值比会把"碰一下没动"误判成改动过。
     */
    displayGeometryOf?: (id: string) => { x: number; y: number; w: number; h: number; rotation: number } | null;
}) {
    const project = useProjectStore();
    const ui = useUiStore();
    const ws = getWsClient();
    // transform 完成时再 guard 一次（mid-transform 远端 lock 兜底）
    const lockGuard = useLockGuard();

    function attachTransformer(): void {
        const t = opts.transformerRef.value?.getNode() as undefined | { nodes(ns: unknown[]): void };
        const l = opts.layerRef.value?.getNode() as undefined | { findOne(sel: string): unknown };
        if (!t || !l) return;
        // hand 工具下同样隐藏 transformer（pan 模式不显示锚点遮挡）
        // paint-bucket 工具下隐藏 transformer（click-only 工具，不需要 resize/rotate 锚点）
        if (ui.activeTool === 'move' || ui.activeTool === 'hand' || ui.activeTool === 'paint-bucket' || ui.selectedCount === 0) { t.nodes([]); return; }
        const nodes: unknown[] = [];
        for (const id of ui.selectedIds) {
            const el = project.elementById(id);
            if (!el) continue;
            // 笔触元素不挂 transformer：它的宽高是采样点算出来的派生值，服务端
            // （EditSession.applyBrushPatch）根本没有 w / h 分支，收到就抛
            // "unknown brush field: w" 整条 patch 被拒。而 Transformer 只要挂上就一定会
            // 发出带 w/h 的几何 op（纯旋转也带），前端却已经乐观改过本地 w/h ——
            // 结果是浏览器显示缩放后的样子、游戏里还是原样，双端一直分叉到重新拉快照。
            // 笔触的旋转仍可在右侧属性面板改（那里按字段单发，不会带上 w/h）。
            if (el.type === 'brush') continue;
            // 上锁的元素 / 上锁图层里的元素不给锚点。元素锁后端根本不看，锚点一挂就能拉；
            // 图层锁后端会拒，但前端已经乐观改过本地几何，两边就此对不上。
            if (!project.isElementEditable(id)) continue;
            // 隐藏的元素不给锚点：画面上根本看不见的东西，挂一圈锚点在那儿既莫名其妙、
            // 又能被拖着改（后端不看 visible，改动照样落地）。
            if (!el.visible) continue;
            if (project.layerOfElement(id)?.visible === false) continue;
            const n = l.findOne(`#${id}`);
            if (n) nodes.push(n);
        }
        t.nodes(nodes);
    }

    // Transformer attach：selection 变化 / elements 变化 / 工具切换都 reattach
    // watch selectedIds 用 Array.from(...).join(',')，Set 引用每次 selectMany 都换新
    watch(() => Array.from(ui.selectedIds).join(','), () => nextTick(attachTransformer));
    watch(opts.elementsWatchSource, () => nextTick(attachTransformer), { deep: true });
    watch(() => ui.activeTool, () => nextTick(attachTransformer));

    function onTransformEnd(ev: TransformEvt, id: string): void {
        const node = ev.target;
        const sx = node.scaleX();
        const sy = node.scaleY();
        const newW = Math.max(1, Math.round(node.width() * sx));
        const newH = Math.max(1, Math.round(node.height() * sy));
        const newX = Math.round(node.x() - newW / 2);
        const newY = Math.round(node.y() - newH / 2);
        const newRot = normalizeRotation(node.rotation());
        // 重置 scale 避免累乘；同时把新 w/h 写回 node 让 Transformer 重新 layout。
        node.scaleX(1); node.scaleY(1);
        node.width(newW); node.height(newH);
        node.rotation(newRot);
        const el = project.elementById(id);
        if (!el) return;
        // 几何一点没变就别发 op：碰一下锚点又原地松手也会走到这里，发出去只是往
        // 撤销栈里灌空操作、白占一次限流额度。比的是"画面上显示的几何"——时间轴预览态下
        // 元素基础值和画面不是一回事，拿基础值比会把没动过也当成动过。
        const shown = opts.displayGeometryOf?.(id) ?? el;
        if (shown.x === newX && shown.y === newY && shown.w === newW && shown.h === newH
            && shown.rotation === newRot) {
            return;
        }
        // 内层防线：node 状态已视觉重置；wall 远端被 lock 时不发 op
        if (!lockGuard.guardMutation('transform')) return;

        // 笔触兜底：正常路径 attachTransformer 已经不给它挂锚点，这里再挡一次，
        // 保证任何情况下都不会给服务端发出笔触的 w / h（发了整条 patch 会被拒）。
        if (el.type === 'brush') {
            el.x = newX; el.y = newY;
            el.rotation = newRot;
            ws.send('element.transform', { elementId: id, x: newX, y: newY, rotation: newRot });
            return;
        }

        // PathElement 的几何完全由 d 字符串 + stroke.width 决定。
        if (el.type === 'path') {
            const path = el as PathElement;
            const oldW = Math.max(1, path.w);
            const oldH = Math.max(1, path.h);
            const scaleX = newW / oldW;
            const scaleY = newH / oldH;
            const newD = scalePathD(path.d, scaleX, scaleY);
            const oldStroke = path.stroke;
            const linearScale = Math.max(scaleX, scaleY);
            const newStrokeWidth = oldStroke
                ? Math.max(1, Math.round(oldStroke.width * linearScale))
                : null;
            path.x = newX; path.y = newY; path.w = newW; path.h = newH;
            path.rotation = newRot;
            path.d = newD;
            if (oldStroke && newStrokeWidth !== null) {
                path.stroke = { ...oldStroke, width: newStrokeWidth };
            }
            const patch: Record<string, unknown> = {
                x: newX, y: newY, w: newW, h: newH, rotation: newRot,
                d: newD,
            };
            if (oldStroke && newStrokeWidth !== null) {
                patch.stroke = { ...oldStroke, width: newStrokeWidth };
            }
            ws.send('element.update', { elementId: id, patch });
            return;
        }

        // 其他 element 类型：bbox 即几何（rect/circle/shape）或字号自描述（text/icon）
        el.x = newX; el.y = newY;
        el.w = newW; el.h = newH;
        el.rotation = newRot;
        ws.send('element.transform', {
            elementId: id,
            x: newX, y: newY, w: newW, h: newH, rotation: newRot,
        });
    }

    return {
        attachTransformer,
        onTransformEnd,
    };
}
