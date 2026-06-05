/**
 * 0.6 P3（B3）：缓动求解器双端一致性测试——前端半边。
 *
 * 读仓库根 `rendering-test/easing-vectors.json`（第三方 Python 参照实现生成），逐 case
 * 逐 sample 校验 `ease(easing, t)` 落在容差 `1e-6` 内。Java 端 EasingSolverVectorsTest
 * 跑同一份向量；任一端漂移即红（rendering.md §9.3 / §9.6）。
 *
 * 向量定位：用 `import.meta.url` 锚定本测试文件，再 `../../../../rendering-test` 回仓库根
 * （__tests__ → timeline → src → web → root，4 级）。比 `process.cwd()`（vitest 在 web/）
 * 更稳——不依赖跑测时的工作目录。
 *
 * 容差比对：vitest `toBeCloseTo(expected, digits)` 的判据是 `|diff| < 10^-digits / 2`，
 * 与「≤ 1e-6」语义不一致；这里改用 `expect(Math.abs(diff)).toBeLessThanOrEqual(1e-6)`
 * 精确贴向量文件声明的 tolerance。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ease, cubicBezier } from '../easing';
import type { Easing } from '@/types/protocol';

interface EasingCase {
    easing: { type: string; bezier?: [number, number, number, number] };
    samples: [number, number][];
}
interface EasingVectors {
    tolerance: number;
    cases: EasingCase[];
}

const here = dirname(fileURLToPath(import.meta.url));
const vectorsPath = resolve(here, '../../../../rendering-test/easing-vectors.json');
const vectors = JSON.parse(readFileSync(vectorsPath, 'utf8')) as EasingVectors;

const TOL = vectors.tolerance;

describe('easing vectors (shared with Java EasingSolverVectorsTest)', () => {
    it('loads a non-empty vector set', () => {
        // 防呆：路径解析失败会读到空 / 抛错；至少要有 linear + 3 预设 + 6 自定义 = 10 case。
        expect(vectors.cases.length).toBeGreaterThanOrEqual(10);
        expect(TOL).toBe(1e-6);
    });

    for (const c of vectors.cases) {
        const label = c.easing.type === 'cubicBezier'
            ? `cubicBezier[${c.easing.bezier?.join(',')}]`
            : c.easing.type;
        it(`matches reference for ${label}`, () => {
            const easing = c.easing as Easing;
            for (const [t, expected] of c.samples) {
                const got = ease(easing, t);
                expect(Math.abs(got - expected)).toBeLessThanOrEqual(TOL);
            }
        });
    }
});

describe('ease() boundary shortcuts and fallbacks (rendering.md §9.3)', () => {
    it('returns local unchanged for null / undefined / linear easing', () => {
        expect(ease(null, 0.37)).toBe(0.37);
        expect(ease(undefined, 0.37)).toBe(0.37);
        expect(ease({ type: 'linear' }, 0.37)).toBe(0.37);
    });

    it('falls back to local for cubicBezier with missing / malformed bezier', () => {
        // 坏 blob（持久化宽容路径漏进来的）按 LINEAR 求值——与后端 EasingSolver 一致。
        expect(ease({ type: 'cubicBezier' }, 0.5)).toBe(0.5);
        expect(ease({ type: 'cubicBezier', bezier: [0.1, 0.2, 0.3] as never }, 0.5)).toBe(0.5);
    });

    it('clamps exact endpoints via boundary shortcut', () => {
        // local <= 0 → 0、local >= 1 → 1（端点精确，两端相同）。
        expect(cubicBezier(0.42, 0, 1, 1, 0)).toBe(0);
        expect(cubicBezier(0.42, 0, 1, 1, 1)).toBe(1);
        expect(cubicBezier(0.42, 0, 1, 1, -0.5)).toBe(0);
        expect(cubicBezier(0.42, 0, 1, 1, 1.5)).toBe(1);
    });

    it('treats easeInOut as symmetric around t=0.5', () => {
        // (0.42,0,0.58,1) 关于 (0.5,0.5) 中心对称：ease(0.5)=0.5、ease(t)+ease(1-t)=1。
        expect(Math.abs(ease({ type: 'easeInOut' }, 0.5) - 0.5)).toBeLessThanOrEqual(TOL);
        const a = ease({ type: 'easeInOut' }, 0.3);
        const b = ease({ type: 'easeInOut' }, 0.7);
        expect(Math.abs(a + b - 1)).toBeLessThanOrEqual(1e-5);
    });
});
