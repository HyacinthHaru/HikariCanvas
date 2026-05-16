import { watch, type Ref } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useBrushStore } from '@/stores/brush';
import { BrushController, extractPressure } from '@/brush/BrushController';

/**
 * M12-C 笔刷接入：pointerdown/move/up/cancel + BrushController 实例化。
 *
 * 调用方提供 brushHostRef（容器 DOM）+ widthPx/heightPx getter（动态 canvas 尺寸）。
 * 返回 4 个事件 handler 直接绑到 brushHost 元素。
 */
export function useBrushHost(opts: {
    brushHostRef: Ref<HTMLElement | null>;
    widthPx: () => number;
    heightPx: () => number;
}) {
    const project = useProjectStore();
    const ui = useUiStore();
    const brushStore = useBrushStore();

    const brushController = new BrushController({
        container: () => opts.brushHostRef.value,
        canvasWidth: () => opts.widthPx(),
        canvasHeight: () => opts.heightPx(),
        getLayerId: () => null,
        onStrokeFinished: () => { /* 保持 brush 工具激活，连续画 */ },
    });

    function brushPropsFromUi(): import('@/brush/BrushController').BrushProps {
        return brushStore.snapshot;
    }

    function pointerEventToStagePoint(e: PointerEvent): { x: number; y: number; pressure: number } {
        const host = opts.brushHostRef.value;
        if (!host) return { x: 0, y: 0, pressure: 0.5 };
        const rect = host.getBoundingClientRect();
        const x = (e.clientX - rect.left) / ui.zoom;
        const y = (e.clientY - rect.top) / ui.zoom;
        return { x, y, pressure: extractPressure(e) };
    }

    function onBrushPointerDown(e: PointerEvent) {
        if (ui.activeTool !== 'brush') return;
        if (project.isLocked) return;
        e.preventDefault();
        e.stopPropagation();
        (e.target as HTMLElement).setPointerCapture(e.pointerId);
        brushController.pointerDown(pointerEventToStagePoint(e), brushPropsFromUi());
    }

    function onBrushPointerMove(e: PointerEvent) {
        if (ui.activeTool !== 'brush') return;
        if (!brushController.isActive()) return;
        e.preventDefault();
        brushController.pointerMove(pointerEventToStagePoint(e));
    }

    function onBrushPointerUp(e: PointerEvent) {
        if (ui.activeTool !== 'brush') return;
        if (!brushController.isActive()) return;
        e.preventDefault();
        brushController.pointerUp(pointerEventToStagePoint(e));
    }

    function onBrushPointerCancel() {
        if (ui.activeTool !== 'brush') return;
        brushController.pointerCancel();
    }

    watch(() => ui.activeTool, (tool) => {
        if (tool !== 'brush' && brushController.isActive()) {
            brushController.pointerCancel();
        }
    });

    return {
        brushController,
        onBrushPointerDown,
        onBrushPointerMove,
        onBrushPointerUp,
        onBrushPointerCancel,
    };
}
