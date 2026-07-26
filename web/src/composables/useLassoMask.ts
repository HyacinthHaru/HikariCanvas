import { ref, onScopeDispose, type Ref } from 'vue';
import { useEventListener } from '@vueuse/core';
import { rdpSimplify } from '@/livepaint/RdpSimplifier';
import type { ImageElement, Mask } from '@/types/protocol';

/**
 * lasso 自由绘制 mask。
 *
 * <h3>触发条件</h3>
 * 当满足以下条件时进入 lasso 模式：
 * <ul>
 *   <li>activeTool === 'select'（不与 brush / draw / paint-bucket 冲突）</li>
 *   <li>当前选中的单一 element 是 image（{@link #selectedImageGetter} 返非空）</li>
 *   <li>Alt 键按住</li>
 *   <li>pointer down 在该 image element 的 bbox 内（屏幕 → 画布坐标转换由 caller 提供）</li>
 * </ul>
 *
 * <h3>数据模型</h3>
 * lasso 输出的 path d 是相对 element bbox 的坐标（与现有 mask.d 约定一致：[0..w, 0..h]）。
 * 采用 M/L/Z 子集（PathDValidator 强制约束），起点 {@code M x0 y0}，中间 {@code L xi yi}，
 * 末尾 {@code Z}。
 *
 * <h3>简化</h3>
 * pointer move 收集的原始点串往往 100+ 顶点，用 {@link rdpSimplify} 阶梯化简
 * （容差逐级翻倍）直到不超过 {@link MAX_VERTICES}。
 *
 * <p><b>上限必须是 64</b>：服务端蒙版走 ElementValidator.validateMaskPathBounds，
 * 数 d 里的 M/L/Q/C 命令个数，超过 64 直接 INVALID_PAYLOAD。这与 PathDValidator 给
 * 普通路径的 240 是两套限制，别混。以前这里写 200，于是稍微复杂点的自由套索
 * 简化完还有七八十个点，element.update 100% 被服务端拒绝，而前端没有任何提示 ——
 * 用户看到的现象就是"画完了，没反应"。</p>
 *
 * <h3>退出</h3>
 * pointer up / pointercancel / window blur / Alt 松开（drawing 中途）→ 终止绘制。
 *
 * <p><b>调用方</b>：CanvasView 把以下 callback 注入：</p>
 * <ul>
 *   <li>{@code selectedImageGetter}：返当前选中的 image element 或 null</li>
 *   <li>{@code activeToolGetter}：返当前 activeTool 字符串</li>
 *   <li>{@code clientToCanvasLocal}：把屏幕坐标转换为 element 局部坐标 [0..w, 0..h]，
 *     超出 element bbox 时返 null（用于 pointer down 判定 + path 点采样）</li>
 *   <li>{@code onUpdateMask}：lasso 完成时调用，传入新 {@link Mask}（保留 featherPx）</li>
 *   <li>{@code onError}：点数不足 / 顶点超限被强行抽稀时的提示</li>
 * </ul>
 */

const MIN_POINTS = 3;
/** 服务端 ElementValidator.MAX_MASK_VERTICES，双端必须一致。 */
export const MAX_VERTICES = 64;
/** RDP 容差阶梯（像素）。逐级翻倍，越往后轮廓越粗糙但顶点越少。 */
const TOLERANCE_LADDER = [1, 2, 4, 8, 16, 32, 64];

/**
 * 阶梯 RDP 压不到上限时的兜底：等间隔抽点，硬砍到 max 个。
 * 首点保留，末点不重复首点（polylineToSvgPathD 自己补 Z）。
 */
function decimate(pts: Array<[number, number]>, max: number): Array<[number, number]> {
    if (pts.length <= max) return pts;
    const out: Array<[number, number]> = [];
    for (let i = 0; i < max; i++) {
        out.push(pts[Math.floor((i * pts.length) / max)]);
    }
    return out;
}

export function useLassoMask(opts: {
    selectedImageGetter: () => ImageElement | null;
    activeToolGetter: () => string;
    /** 把 client (clientX, clientY) 转 element 局部 (lx, ly)；超出 element bbox 返 null */
    clientToElementLocal: (clientX: number, clientY: number) => { lx: number; ly: number } | null;
    onUpdateMask: (mask: Mask) => void;
    onError?: (kind: 'too-few-points' | 'simplified') => void;
    /** 投影模式标志：lasso 进行时 true，CanvasView 可据此画虚线 path 提示 */
    drawingGuide?: Ref<{ active: boolean; points: Array<[number, number]>; w: number; h: number } | null>;
}) {
    const points = ref<Array<[number, number]>>([]);
    const active = ref(false);
    const elementSnapshotRef = ref<ImageElement | null>(null);
    const isAltDown = ref(false);

    function publishGuide(): void {
        if (!opts.drawingGuide) return;
        const el = elementSnapshotRef.value;
        if (active.value && el) {
            opts.drawingGuide.value = { active: true, points: points.value.slice(), w: el.w, h: el.h };
        } else {
            opts.drawingGuide.value = null;
        }
    }

    function start(ev: PointerEvent): boolean {
        if (!isAltDown.value) return false;
        if (opts.activeToolGetter() !== 'select') return false;
        const el = opts.selectedImageGetter();
        if (!el) return false;
        const local = opts.clientToElementLocal(ev.clientX, ev.clientY);
        if (!local) return false;
        active.value = true;
        elementSnapshotRef.value = el;
        points.value = [[local.lx, local.ly]];
        publishGuide();
        return true;
    }

    function move(ev: PointerEvent): boolean {
        if (!active.value) return false;
        const el = elementSnapshotRef.value;
        if (!el) return false;
        const local = opts.clientToElementLocal(ev.clientX, ev.clientY);
        if (!local) {
            // 越出 element bbox：clamp 到边界（保持连续 path）
            return false;
        }
        const last = points.value[points.value.length - 1];
        if (last && Math.abs(last[0] - local.lx) < 1 && Math.abs(last[1] - local.ly) < 1) {
            return true;  // 过近点忽略，避免顶点爆炸
        }
        points.value.push([local.lx, local.ly]);
        publishGuide();
        return true;
    }

    function end(): boolean {
        if (!active.value) return false;
        const collected = points.value.slice();
        const el = elementSnapshotRef.value;
        active.value = false;
        points.value = [];
        elementSnapshotRef.value = null;
        publishGuide();

        if (!el) return true;
        if (collected.length < MIN_POINTS) {
            opts.onError?.('too-few-points');
            return true;
        }
        // RDP 阶梯化简到 MAX_VERTICES 以下。每轮都从原始点串重算，避免误差累积。
        let simplified = collected;
        for (const tol of TOLERANCE_LADDER) {
            if (simplified.length <= MAX_VERTICES) break;
            simplified = rdpSimplify(collected, tol);
        }
        // 阶梯走完还超（长而绕的套索会这样）→ 等间隔抽稀硬砍到上限，并告诉用户边缘被简化了。
        // 宁可轮廓糙一点也要让蒙版真的生效——直接发出去只会被服务端整条拒掉。
        if (simplified.length > MAX_VERTICES) {
            simplified = decimate(simplified, MAX_VERTICES);
            opts.onError?.('simplified');
        }
        // 强制末点闭合（不重复首点）
        const d = polylineToSvgPathD(simplified);
        if (!d) {
            opts.onError?.('too-few-points');
            return true;
        }
        const existing = opts.selectedImageGetter()?.mask;
        opts.onUpdateMask({
            d,
            inverted: existing?.inverted ?? false,
            featherPx: existing?.featherPx ?? null,
        });
        return true;
    }

    function cancel(): void {
        if (!active.value) return;
        active.value = false;
        points.value = [];
        elementSnapshotRef.value = null;
        publishGuide();
    }

    // 全局 Alt 状态追踪
    useEventListener(window, 'keydown', (e: KeyboardEvent) => {
        if (e.key === 'Alt' || e.altKey) isAltDown.value = true;
    });
    useEventListener(window, 'keyup', (e: KeyboardEvent) => {
        if (e.key === 'Alt' || !e.altKey) isAltDown.value = false;
    });
    useEventListener(window, 'blur', () => {
        isAltDown.value = false;
        if (active.value) cancel();
    });

    onScopeDispose(() => cancel());

    return {
        active,
        isAltDown,
        start,
        move,
        end,
        cancel,
    };
}

/**
 * 把点串转 SVG path d 字符串（M/L/Z 子集，整数坐标）。
 * 至少 3 个点才返回，否则返 null。坐标 ≤ 0 / ≥ w/h 不 clamp，由 PathDValidator
 * 自己拒；本函数职责是格式化，clamp 留给 caller。
 */
function polylineToSvgPathD(pts: Array<[number, number]>): string | null {
    if (pts.length < 3) return null;
    const parts: string[] = [];
    parts.push(`M ${Math.round(pts[0][0])} ${Math.round(pts[0][1])}`);
    for (let i = 1; i < pts.length; i++) {
        parts.push(`L ${Math.round(pts[i][0])} ${Math.round(pts[i][1])}`);
    }
    parts.push('Z');
    return parts.join(' ');
}
