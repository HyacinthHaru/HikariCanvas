import { nextTick, watch, type Ref } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { getWsClient } from '@/network/wsClient';
import type { Element, PathElement } from '@/types/protocol';
import { scalePathD } from '@/render/pathScale';

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
}) {
    const project = useProjectStore();
    const ui = useUiStore();
    const ws = getWsClient();

    function attachTransformer(): void {
        const t = opts.transformerRef.value?.getNode() as undefined | { nodes(ns: unknown[]): void };
        const l = opts.layerRef.value?.getNode() as undefined | { findOne(sel: string): unknown };
        if (!t || !l) return;
        // M17 F4：hand 工具下同样隐藏 transformer（pan 模式不显示锚点遮挡）
        // M18 Live Paint：paint-bucket 工具下隐藏 transformer（click-only 工具，不需要 resize/rotate 锚点）
        if (ui.activeTool === 'move' || ui.activeTool === 'hand' || ui.activeTool === 'paint-bucket' || ui.selectedCount === 0) { t.nodes([]); return; }
        const nodes: unknown[] = [];
        for (const id of ui.selectedIds) {
            const n = l.findOne(`#${id}`);
            if (n) nodes.push(n);
        }
        t.nodes(nodes);
    }

    // Transformer attach：selection 变化 / elements 变化 / 工具切换都 reattach
    // M8-F：watch selectedIds 用 Array.from(...).join(',')，Set 引用每次 selectMany 都换新
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

        // 2026-05-14 Bug 修：PathElement 的几何完全由 d 字符串 + stroke.width 决定。
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
