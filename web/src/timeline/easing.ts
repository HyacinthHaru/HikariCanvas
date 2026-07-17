/**
 * 缓动求解器——后端 `render/EasingSolver.java` 的前端镜像。
 *
 * 数学权威定义见 `docs/rendering.md §9.3`：算法（WebKit UnitBezier 系数形式 + 固定步数
 * 牛顿迭代 + 二分兜底）、常量（NEWTON_ITER / BISECT_MAX / EPS）、边界捷径与 EASE 预设
 * 控制点**两端写死相同**，由共享测试向量 `rendering-test/easing-vectors.json`（容差 1e-6）
 * 在两端 CI 各自校验。禁引第三方 easing 库（timeline.md D8）。
 */
import type { Easing } from '../types/protocol';

/** 牛顿迭代固定步数（两端相同）。 */
export const NEWTON_ITER = 8;

/** 二分兜底固定上限（两端相同）。 */
export const BISECT_MAX = 32;

/** 求解终止阈值（两端相同）。 */
export const EPS = 1e-6;

/**
 * 按 Easing 把线性局部进度 local 映射成 eased 进度。
 * easing 缺失 / 坏 bezier（长度 ≠ 4）按 LINEAR 求值（与后端宽容路径一致）。
 */
export function ease(easing: Easing | undefined | null, local: number): number {
    if (!easing || !easing.type || easing.type === 'linear') return local;
    switch (easing.type) {
        case 'easeIn':
            return cubicBezier(0.42, 0.0, 1.0, 1.0, local);
        case 'easeOut':
            return cubicBezier(0.0, 0.0, 0.58, 1.0, local);
        case 'easeInOut':
            return cubicBezier(0.42, 0.0, 0.58, 1.0, local);
        case 'cubicBezier': {
            const b = easing.bezier;
            if (!b || b.length !== 4) return local;
            return cubicBezier(b[0], b[1], b[2], b[3], local);
        }
        default:
            return local;
    }
}

/**
 * 标准 CSS cubic-bezier 求值：端点 P0=(0,0)、P3=(1,1)，控制点 (x1,y1)、(x2,y2)，
 * 输入 local 为横轴（时间）进度。照 rendering.md §9.3 伪码逐行实现，与 Java 端逐位对齐。
 */
export function cubicBezier(x1: number, y1: number, x2: number, y2: number, local: number): number {
    // 边界捷径（两端相同；§9.3）：保证端点精确
    if (local <= 0.0) return 0.0;
    if (local >= 1.0) return 1.0;

    const cx = 3.0 * x1;
    const bx = 3.0 * (x2 - x1) - cx;
    const ax = 1.0 - cx - bx;
    const cy = 3.0 * y1;
    const by = 3.0 * (y2 - y1) - cy;
    const ay = 1.0 - cy - by;

    const u = solveU(ax, bx, cx, local);
    return ((ay * u + by) * u + cy) * u;
}

/** Bx(u) = ((ax·u + bx)·u + cx)·u */
function sampleX(ax: number, bx: number, cx: number, u: number): number {
    return ((ax * u + bx) * u + cx) * u;
}

/** 解 Bx(u) = x：牛顿 NEWTON_ITER 步 → 二分 BISECT_MAX 步兜底。 */
function solveU(ax: number, bx: number, cx: number, x: number): number {
    let u = x;
    for (let i = 0; i < NEWTON_ITER; i++) {
        const d = sampleX(ax, bx, cx, u) - x;
        if (Math.abs(d) <= EPS) return u;
        const dx = (3.0 * ax * u + 2.0 * bx) * u + cx;
        if (Math.abs(dx) < 1e-6) break;   // 导数退化 → 转二分
        u = u - d / dx;
    }
    let lo = 0.0;
    let hi = 1.0;
    u = x;
    for (let i = 0; i < BISECT_MAX; i++) {
        const s = sampleX(ax, bx, cx, u);
        if (Math.abs(s - x) <= EPS) return u;
        if (s < x) {
            lo = u;
        } else {
            hi = u;
        }
        u = (lo + hi) / 2.0;
    }
    return u;
}
