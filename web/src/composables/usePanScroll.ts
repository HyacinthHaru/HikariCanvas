import { nextTick, ref, type Ref } from 'vue';
import { useUiStore } from '@/stores/ui';

/**
 * Ctrl+wheel zoom（以鼠标为中心）+ 中键 / Alt+左键拖拽 pan。
 * hand 工具（左键直接 pan）+ 暴露 isPanning 供 cursor 状态切换。
 * outerRef 是带 overflow:auto 的滚动容器；widthPx/heightPx 用于 fitToViewport 计算。
 */
export function usePanScroll(opts: {
    outerRef: Ref<HTMLElement | null>;
    widthPx: () => number;
    heightPx: () => number;
}) {
    const { outerRef } = opts;
    const ui = useUiStore();
    /** 暴露给 CanvasView 用于 grabbing cursor。 */
    const isPanning = ref(false);

    function onWheel(e: WheelEvent) {
        if (!(e.ctrlKey || e.metaKey)) {
            // Shift+wheel → 水平滚动（PS / Figma 标准）。
            // 鼠标 wheel 只有 deltaY；Shift 在此把 Y 轴增量重定向到水平方向。
            // 触控板原生 deltaX 横滚不经此分支（未按 Shift），浏览器默认处理。
            if (e.shiftKey) {
                e.preventDefault();
                const outer = outerRef.value;
                if (outer) outer.scrollLeft += e.deltaY;
            }
            return;
        }
        e.preventDefault();
        const outer = outerRef.value;
        if (!outer) return;
        const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
        const oldZoom = ui.zoom;
        const newZoomClamped = Math.max(0.25, Math.min(4, oldZoom * factor));
        if (newZoomClamped === oldZoom) return;
        const rect = outer.getBoundingClientRect();
        const mouseX = e.clientX - rect.left;
        const mouseY = e.clientY - rect.top;
        ui.setZoom(newZoomClamped);
        const ratio = newZoomClamped / oldZoom;
        nextTick(() => {
            outer.scrollLeft = (outer.scrollLeft + mouseX) * ratio - mouseX;
            outer.scrollTop = (outer.scrollTop + mouseY) * ratio - mouseY;
        });
    }

    function fitToViewport() {
        const outer = outerRef.value;
        if (!outer) return;
        const availW = outer.clientWidth - 64;
        const availH = outer.clientHeight - 64;
        const fitW = availW / opts.widthPx();
        const fitH = availH / opts.heightPx();
        const fit = Math.min(fitW, fitH);
        ui.setZoom(Math.max(0.25, Math.min(4, fit)));
        nextTick(() => {
            outer.scrollLeft = (outer.scrollWidth - outer.clientWidth) / 2;
            outer.scrollTop = (outer.scrollHeight - outer.clientHeight) / 2;
        });
    }

    interface PanState { active: boolean; startX: number; startY: number; scrollX: number; scrollY: number; }
    const pan: PanState = { active: false, startX: 0, startY: 0, scrollX: 0, scrollY: 0 };

    function onMouseDown(e: MouseEvent) {
        const middleBtn = e.button === 1;
        const altLeft = e.button === 0 && e.altKey;
        // hand 工具下左键直接 pan（含点击元素 / 空白处任意位置）。
        const handLeft = e.button === 0 && ui.activeTool === 'hand';
        if (!middleBtn && !altLeft && !handLeft) return;
        if (!outerRef.value) return;
        e.preventDefault();
        pan.active = true;
        isPanning.value = true;
        pan.startX = e.clientX;
        pan.startY = e.clientY;
        pan.scrollX = outerRef.value.scrollLeft;
        pan.scrollY = outerRef.value.scrollTop;
    }

    function onMouseMove(e: MouseEvent) {
        if (!pan.active || !outerRef.value) return;
        outerRef.value.scrollLeft = pan.scrollX - (e.clientX - pan.startX);
        outerRef.value.scrollTop = pan.scrollY - (e.clientY - pan.startY);
    }

    function onMouseUpOrLeave() {
        pan.active = false;
        isPanning.value = false;
    }

    return {
        onWheel,
        onMouseDown,
        onMouseMove,
        onMouseUpOrLeave,
        fitToViewport,
        isPanning,
    };
}
