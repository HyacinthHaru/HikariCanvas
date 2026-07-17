import { onScopeDispose, watch, type Ref } from 'vue';
import { useProjectStore } from '@/stores/project';
import { useUiStore } from '@/stores/ui';
import { useBrushStore } from '@/stores/brush';
import { BrushController, extractPressure } from '@/brush/BrushController';

/**
 * 笔刷接入：pointerdown/move/up/cancel + BrushController 实例化。
 *
 * 调用方提供 brushHostRef（容器 DOM）+ widthPx/heightPx getter（动态 canvas 尺寸）。
 * 返回 4 个事件 handler 直接绑到 brushHost 元素。
 *
 * <p>setPointerCapture / releasePointerCapture 包 try-catch；监听
 * pointercancel / window blur / document visibilitychange，触发即终止 stroke，
 * 防止"用户切走 → pointerup 永不到达 → BrushController 卡在 isDrawing"。</p>
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

    /** 当前 capture 的 (target, pointerId)；release 时引用以便容错 */
    let capturedTarget: HTMLElement | null = null;
    let capturedPointerId: number | null = null;

    function tryCapture(target: HTMLElement, pointerId: number): boolean {
        try {
            target.setPointerCapture(pointerId);
            capturedTarget = target;
            capturedPointerId = pointerId;
            return true;
        } catch (err) {
            // InvalidStateError / NotFoundError 等；不进入 "已 capture" 状态
            console.warn('[useBrushHost] setPointerCapture failed', err);
            capturedTarget = null;
            capturedPointerId = null;
            return false;
        }
    }

    function tryRelease(): void {
        if (!capturedTarget || capturedPointerId === null) return;
        try {
            capturedTarget.releasePointerCapture(capturedPointerId);
        } catch (err) {
            // capture 已被 OS 收走时 release 会抛；safe 吞掉
            console.warn('[useBrushHost] releasePointerCapture failed', err);
        }
        capturedTarget = null;
        capturedPointerId = null;
    }

    /** 终止 stroke：idempotent，可在 pointerup / pointercancel / blur / visibilitychange 重复调。 */
    function abortStroke(): void {
        if (brushController.isActive()) {
            brushController.pointerCancel();
        }
        tryRelease();
    }

    function onBrushPointerDown(e: PointerEvent) {
        if (ui.activeTool !== 'brush') return;
        if (project.isLocked) return;
        e.preventDefault();
        e.stopPropagation();
        const ok = tryCapture(e.target as HTMLElement, e.pointerId);
        if (!ok) return;  // capture 失败 → 不开始 stroke，避免 pointerup 时仍以为 captured
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
        if (!brushController.isActive()) {
            // 防御：capture 残留时也 release
            tryRelease();
            return;
        }
        e.preventDefault();
        brushController.pointerUp(pointerEventToStagePoint(e));
        tryRelease();
    }

    function onBrushPointerCancel() {
        // PointerEvent pointercancel：浏览器/OS 已主动取消 capture。无条件终止。
        abortStroke();
    }

    function onWindowBlur(): void {
        abortStroke();
    }

    function onVisibilityChange(): void {
        if (document.visibilityState === 'hidden') {
            abortStroke();
        }
    }

    window.addEventListener('blur', onWindowBlur);
    document.addEventListener('visibilitychange', onVisibilityChange);

    watch(() => ui.activeTool, (tool) => {
        if (tool !== 'brush' && brushController.isActive()) {
            abortStroke();
        }
    });

    // 卸载兜底：composable scope 失效时清掉所有监听 + 终止 stroke。
    onScopeDispose(() => {
        window.removeEventListener('blur', onWindowBlur);
        document.removeEventListener('visibilitychange', onVisibilityChange);
        abortStroke();
    });

    return {
        brushController,
        onBrushPointerDown,
        onBrushPointerMove,
        onBrushPointerUp,
        onBrushPointerCancel,
    };
}
