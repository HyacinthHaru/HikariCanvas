import { computed, onScopeDispose, ref } from 'vue';

/**
 * 积木画布的无限平移 / 缩放 composable。
 *
 * <p><b>坐标模型</b>：viewport（overflow hidden）内一个 world div，
 * `transform: translate(panX,panY) scale(zoom)`、`transform-origin: 0 0`。积木堆
 * `position:absolute` 定位在 world 坐标系。屏幕坐标 {@code s} 与 world 坐标 {@code w}
 * 关系为 {@code s = pan + w * zoom}，逆变换 {@code w = (s - pan) / zoom}。</p>
 *
 * <p><b>缩放锚点</b>：ctrl/meta + wheel 缩放，<b>以光标为锚</b>——光标下 world 点缩放前后
 * 不变（复用 usePanScroll 的"以鼠标为中心"思路，但作用于 transform 而非 scroll）。zoom
 * clamp 0.4..2。pan 由空格按住拖 / 中键拖 / 拖空白触发，PointerEvent + setPointerCapture，
 * 清理借 useBrushHost 范式（pointercancel / blur / visibilitychange / onScopeDispose）。</p>
 *
 * <p>缩放锚点数学、screenToWorld 逆变换、zoom clamp 抽成纯函数（{@link computeZoomPan} /
 * {@link screenToWorldPure} / {@link clampZoom}）便于 vitest 独立测试。</p>
 */

export const ZOOM_MIN = 0.4;
export const ZOOM_MAX = 2;

/** zoom clamp 到 [ZOOM_MIN, ZOOM_MAX]。 */
export function clampZoom(z: number): number {
    if (!Number.isFinite(z)) return 1;
    return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
}

/** 当前 pan/zoom 视图状态（屏幕像素 + 缩放系数）。 */
export interface ViewState {
    panX: number;
    panY: number;
    zoom: number;
}

/**
 * 纯函数：以锚点（viewport 内坐标 {@code anchorX/anchorY}）缩放后求新 pan/zoom。
 *
 * <p>保持锚点下的 world 点不变：{@code w = (anchor - pan) / zoomOld}，缩放后
 * {@code pan' = anchor - w * zoomNew}。zoom 经 clamp；若 clamp 后无变化（已到边界）
 * 则 pan 也不变，避免边界处抖动。</p>
 */
export function computeZoomPan(prev: ViewState, anchorX: number, anchorY: number, factor: number): ViewState {
    const newZoom = clampZoom(prev.zoom * factor);
    if (newZoom === prev.zoom) return prev;
    const ratio = newZoom / prev.zoom;
    // pan' = anchor - (anchor - pan) * ratio
    const panX = anchorX - (anchorX - prev.panX) * ratio;
    const panY = anchorY - (anchorY - prev.panY) * ratio;
    return { panX, panY, zoom: newZoom };
}

/**
 * 纯函数：viewport 内屏幕坐标 → world 坐标。
 * {@code clientX/clientY} 已是相对 viewport 左上的坐标（调用方先减去 viewport rect）。
 */
export function screenToWorldPure(view: ViewState, vpX: number, vpY: number): { x: number; y: number } {
    return {
        x: (vpX - view.panX) / view.zoom,
        y: (vpY - view.panY) / view.zoom,
    };
}

export function useBlockCanvas(opts: {
    /** viewport DOM（overflow hidden 容器），用于读 getBoundingClientRect。 */
    viewportRef: { value: HTMLElement | null };
    /** 空格是否按住（pan 触发条件之一）。由组件维护 keydown/keyup。 */
    isSpaceDown: () => boolean;
}) {
    const panX = ref(0);
    const panY = ref(0);
    const zoom = ref(1);

    const worldStyle = computed(() => ({
        transform: `translate(${panX.value}px, ${panY.value}px) scale(${zoom.value})`,
        transformOrigin: '0 0',
    }));

    /** ctrl/meta + wheel 以光标为锚缩放。非 ctrl/meta 滚轮不拦（留给原生滚动 / 上层）。 */
    function onWheel(e: WheelEvent): void {
        if (!(e.ctrlKey || e.metaKey)) return;
        e.preventDefault();
        const vp = opts.viewportRef.value;
        if (!vp) return;
        const rect = vp.getBoundingClientRect();
        const anchorX = e.clientX - rect.left;
        const anchorY = e.clientY - rect.top;
        const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
        const next = computeZoomPan(
            { panX: panX.value, panY: panY.value, zoom: zoom.value },
            anchorX, anchorY, factor,
        );
        panX.value = next.panX;
        panY.value = next.panY;
        zoom.value = next.zoom;
    }

    /** viewport 内屏幕坐标（clientX/clientY）→ world 坐标。 */
    function screenToWorld(clientX: number, clientY: number): { x: number; y: number } {
        const vp = opts.viewportRef.value;
        const rect = vp ? vp.getBoundingClientRect() : { left: 0, top: 0 };
        return screenToWorldPure(
            { panX: panX.value, panY: panY.value, zoom: zoom.value },
            clientX - rect.left, clientY - rect.top,
        );
    }

    function resetView(): void {
        panX.value = 0;
        panY.value = 0;
        zoom.value = 1;
    }

    // ---------- pan（PointerEvent + capture，清理借 useBrushHost 范式）----------
    interface PanDrag { startX: number; startY: number; panX0: number; panY0: number; }
    // active 用 ref（响应式），让 isPanning 能驱动 cursor: grabbing 切换。
    const panActive = ref(false);
    const drag: PanDrag = { startX: 0, startY: 0, panX0: 0, panY0: 0 };
    let capturedTarget: HTMLElement | null = null;
    let capturedPointerId: number | null = null;

    function tryCapture(target: HTMLElement, pointerId: number): boolean {
        try {
            target.setPointerCapture(pointerId);
            capturedTarget = target;
            capturedPointerId = pointerId;
            return true;
        } catch {
            capturedTarget = null;
            capturedPointerId = null;
            return false;
        }
    }

    function tryRelease(): void {
        if (!capturedTarget || capturedPointerId === null) return;
        try {
            capturedTarget.releasePointerCapture(capturedPointerId);
        } catch { /* capture 已被 OS 收走时 release 会抛；safe 吞掉 */ }
        capturedTarget = null;
        capturedPointerId = null;
    }

    /** idempotent 终止 pan。pointerup / pointercancel / blur / visibilitychange 可重复调。 */
    function abortPan(): void {
        panActive.value = false;
        tryRelease();
    }

    /**
     * pan 触发条件：中键（button===1）或 空格按住时左键（button===0）。
     * 在空白处拖由组件判定（传 onBlank=true 即按左键也 pan）。
     * 返回 true 表示已接管为 pan（组件据此 e.stopPropagation 拦后续拖块逻辑）。
     */
    function onPanPointerDown(e: PointerEvent, onBlank = false): boolean {
        const middle = e.button === 1;
        const spaceLeft = e.button === 0 && opts.isSpaceDown();
        const blankLeft = e.button === 0 && onBlank;
        if (!middle && !spaceLeft && !blankLeft) return false;
        const ok = tryCapture(e.target as HTMLElement, e.pointerId);
        if (!ok) return false;
        e.preventDefault();
        panActive.value = true;
        drag.startX = e.clientX;
        drag.startY = e.clientY;
        drag.panX0 = panX.value;
        drag.panY0 = panY.value;
        return true;
    }

    function onPanPointerMove(e: PointerEvent): void {
        if (!panActive.value) return;
        panX.value = drag.panX0 + (e.clientX - drag.startX);
        panY.value = drag.panY0 + (e.clientY - drag.startY);
    }

    function onPanPointerUp(): void {
        if (!panActive.value) {
            tryRelease();
            return;
        }
        abortPan();
    }

    function onWindowBlur(): void { abortPan(); }
    function onVisibilityChange(): void {
        if (document.visibilityState === 'hidden') abortPan();
    }

    window.addEventListener('blur', onWindowBlur);
    document.addEventListener('visibilitychange', onVisibilityChange);
    onScopeDispose(() => {
        window.removeEventListener('blur', onWindowBlur);
        document.removeEventListener('visibilitychange', onVisibilityChange);
        abortPan();
    });

    /** 当前是否正在 pan（供组件切 cursor: grabbing）。 */
    const isPanning = panActive;

    return {
        panX, panY, zoom,
        worldStyle,
        onWheel,
        onPanPointerDown,
        onPanPointerMove,
        onPanPointerUp,
        onPanPointerCancel: abortPan,
        screenToWorld,
        resetView,
        isPanning,
    };
}
