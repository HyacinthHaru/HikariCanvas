/**
 * M18 Live Paint — 油漆桶工具配置 store。
 *
 * 范围：用户偏好（当前 fill / tolerance）跨 wall 持久化，不在 M16 P4.2 reset() 范围内
 *  ——剪贴板 / 笔刷 fill 选择同款语义。
 *
 * 字段：
 *   - currentFill：FillCompat 联合类型（solid / linear / radial）；FillInput 组件直接绑定。
 *   - tolerance：v1.x 新增。Live Paint RDP 简化的基础 tolerance（px）。范围 [0.25, 5]，
 *     默认 0.5（与 PolygonToPath.maybeSimplify 历史 baseline 一致）。
 *     - 低 = 更精细多顶点（接近原 polygon-clipping 输出）
 *     - 高 = 平滑少顶点（路径数据量更小，但视觉边缘更圆滑）
 *     PolygonToPath.maybeSimplify 把该值作为初始 tolerance；如果首轮简化后顶点仍超
 *     VERTEX_HARD_LIMIT，会自动阶梯翻倍 fallback。
 *
 * 持久化：localStorage('hikari-canvas:paint-bucket')。Read 失败 / 空 → 默认黑色实心 + 0.5。
 */

import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import type { FillCompat } from '@/types/protocol';

const KEY = 'hikari-canvas:paint-bucket';

const DEFAULT_FILL: FillCompat = { type: 'solid', color: '#000000' };
/** RDP 基础 tolerance 默认值；保持与 PolygonToPath M18-P4 历史 baseline 一致。 */
export const DEFAULT_TOLERANCE = 0.5;
/** Slider 范围：低端精细 / 高端平滑。 */
export const TOLERANCE_MIN = 0.25;
export const TOLERANCE_MAX = 5;
export const TOLERANCE_STEP = 0.25;

interface PersistedState {
    fill: FillCompat;
    tolerance: number;
}

export const usePaintBucketStore = defineStore('paint-bucket', () => {
    const loaded = loadState();
    const currentFill = ref<FillCompat>(loaded.fill);
    const tolerance = ref<number>(loaded.tolerance);

    function persist(): void {
        try {
            const payload: PersistedState = {
                fill: currentFill.value,
                tolerance: tolerance.value,
            };
            localStorage.setItem(KEY, JSON.stringify(payload));
        } catch {
            /* ignore (private mode etc) */
        }
    }

    watch(currentFill, () => persist(), { deep: true });
    watch(tolerance, () => persist());

    return { currentFill, tolerance };
});

function clampTolerance(v: unknown): number {
    if (typeof v !== 'number' || !Number.isFinite(v)) return DEFAULT_TOLERANCE;
    if (v < TOLERANCE_MIN) return TOLERANCE_MIN;
    if (v > TOLERANCE_MAX) return TOLERANCE_MAX;
    return v;
}

function loadState(): { fill: FillCompat; tolerance: number } {
    try {
        const raw = localStorage.getItem(KEY);
        if (raw) {
            const parsed = JSON.parse(raw) as unknown;
            // v1 旧格式：直接是 FillCompat（string 或带 type 的对象）；升级到新对象格式
            if (typeof parsed === 'string') {
                return { fill: parsed as FillCompat, tolerance: DEFAULT_TOLERANCE };
            }
            if (parsed && typeof parsed === 'object') {
                const obj = parsed as Record<string, unknown>;
                // 新格式：{ fill, tolerance }
                if ('fill' in obj) {
                    const f = obj.fill;
                    const fill = (typeof f === 'string' || (f && typeof f === 'object' && 'type' in (f as object)))
                        ? f as FillCompat
                        : ({ ...DEFAULT_FILL } as FillCompat);
                    return { fill, tolerance: clampTolerance(obj.tolerance) };
                }
                // 兼容 v1 老格式：原 FillCompat 对象（带 type）；升级
                if ('type' in obj) {
                    return { fill: obj as unknown as FillCompat, tolerance: DEFAULT_TOLERANCE };
                }
            }
        }
    } catch {
        /* ignore */
    }
    return { fill: { ...(DEFAULT_FILL as object) } as FillCompat, tolerance: DEFAULT_TOLERANCE };
}
