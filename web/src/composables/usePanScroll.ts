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
    /**
     * 返 true 时这次 mousedown 不起 pan。给"同一手势已经被别人接管"的场景用
     * （目前是 Alt+左键画套索蒙版——它和 Alt+左键 pan 是同一个手势）。
     */
    blockPan?: () => boolean;
}) {
    const { outerRef } = opts;
    const ui = useUiStore();
    /** 暴露给 CanvasView 用于 grabbing cursor。 */
    const isPanning = ref(false);

    function onWheel(e: WheelEvent) {
        if (!(e.ctrlKey || e.metaKey)) {
            // Shift+wheel → 水平滚动（PS / Figma 标准）。
            //
            // 增量取 deltaX 优先、没有才退 deltaY：Windows / Linux 上鼠标滚轮只给 deltaY，
            // Shift 由我们负责改投到水平方向；而 macOS 的 Chrome / Safari 在事件层就把
            // Shift+滚轮换成了 deltaX（deltaY 恒为 0）——只看 deltaY 的话这里加 0 等于没滚，
            // 却又把浏览器自己的横滚 preventDefault 掉了，横向彻底动不了。
            // 触控板原生 deltaX 横滚不按 Shift，不经此分支，走浏览器默认处理。
            if (e.shiftKey) {
                const dx = e.deltaX || e.deltaY;
                if (dx !== 0) {
                    e.preventDefault();
                    const outer = outerRef.value;
                    if (outer) outer.scrollLeft += dx;
                }
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
        // 这一手势已经被别人接管（Alt+左键画套索）→ 不抢过来当 pan。
        // 套索那边靠 pointerdown 的 preventDefault 压掉兼容 mousedown，这里是第二道保险，
        // 不指望浏览器一定按预期抑制兼容事件。
        if (opts.blockPan?.()) return;
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
