/**
 * 0.6 P3（B3）：sRGB 线性空间色彩 / Fill 插值双端一致性测试——前端半边。
 *
 * 两段防线：
 * 1. 读仓库根 `rendering-test/color-lerp-vectors.json`（Python 参照生成），`lerpHex` 输出
 *    与 expected **精确字符串匹配**（大写 hex、alpha 规则）。Java 端 ColorLerpVectorsTest
 *    跑同一份；任一端漂移即红（rendering.md §9.4 / §9.6）。
 * 2. `lerpFill` 细则 case 清单——与 B1 Java 侧 ColorLerpTest 双端对称：同类型同 stop 数逐
 *    stop 插值、类型 / stop 数不一致 / null → step（返左端帧）。
 *
 * 向量定位同 easing.test.ts：`import.meta.url` 锚定 + `../../../../rendering-test` 回仓库根。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { lerpHex, lerpFill, srgbDecode, srgbEncode } from '../colorLerp';
import type { Fill, LinearGradient, RadialGradient, SolidFill } from '@/types/protocol';

interface ColorCase {
    a: string;
    b: string;
    t: number;
    expected: string;
}
interface ColorVectors {
    cases: ColorCase[];
}

const here = dirname(fileURLToPath(import.meta.url));
const vectorsPath = resolve(here, '../../../../rendering-test/color-lerp-vectors.json');
const vectors = JSON.parse(readFileSync(vectorsPath, 'utf8')) as ColorVectors;

describe('color-lerp vectors (shared with Java ColorLerpVectorsTest)', () => {
    it('loads a non-empty vector set', () => {
        expect(vectors.cases.length).toBeGreaterThanOrEqual(7 * 5);
    });

    for (const c of vectors.cases) {
        it(`lerpHex(${c.a}, ${c.b}, ${c.t}) === ${c.expected}`, () => {
            expect(lerpHex(c.a, c.b, c.t)).toBe(c.expected);
        });
    }
});

describe('srgb transfer functions round-trip (rendering.md §9.4)', () => {
    it('decode/encode are inverses within float epsilon', () => {
        for (const c of [0, 0.04045, 0.1, 0.5, 0.9, 1]) {
            expect(Math.abs(srgbEncode(srgbDecode(c)) - c)).toBeLessThanOrEqual(1e-7);
        }
    });

    it('mid-gray of black→white is brighter than naive 0x80 (linear-space proof)', () => {
        // 8-bit 线性插值中点会是 #BCBCBC（向量已含），证明走的是线性空间不是裸 sRGB。
        expect(lerpHex('#000000', '#FFFFFF', 0.5)).toBe('#BCBCBC');
    });
});

describe('lerpHex alpha + parse rules (rendering.md §9.4)', () => {
    it('emits alpha channel iff either input carries alpha', () => {
        expect(lerpHex('#FFFFFF', '#000000', 0.5)).toBe('#BCBCBC');           // 双无 alpha → 无
        expect(lerpHex('#FFFFFFFF', '#000000', 0)).toBe('#FFFFFFFF');         // 任一含 → 输出含
        expect(lerpHex('#FFFFFF', '#00000000', 1)).toBe('#00000000');
    });

    it('treats missing alpha as FF when the other side has alpha', () => {
        // a=#FFFFFF（隐式 alpha FF），b=#00000000：t=0 取 a，alpha 应是 FF。
        expect(lerpHex('#FFFFFF', '#00000000', 0)).toBe('#FFFFFFFF');
    });

    it('interpolates alpha linearly (no gamma)', () => {
        expect(lerpHex('#FF000000', '#FF0000FF', 0.5)).toBe('#FF000080');
    });

    it('is case-insensitive on input, uppercase on output', () => {
        expect(lerpHex('#ff0000', '#0000ff', 0)).toBe('#FF0000');
        expect(lerpHex('#ff0000', '#0000ff', 1)).toBe('#0000FF');
    });

    it('steps (returns a) on either side parse failure', () => {
        expect(lerpHex('not-a-color', '#000000', 0.5)).toBe('not-a-color');
        expect(lerpHex('#000000', '#zzzzzz', 0.5)).toBe('#000000');
        expect(lerpHex('#12345', '#000000', 0.5)).toBe('#12345');   // 长度非 6/8
    });
});

// ---------- lerpFill 细则（与 B1 Java ColorLerpTest 双端对称） ----------

function solid(color: string): SolidFill {
    return { type: 'solid', color };
}
function linear(angle: number, stops: { position: number; color: string }[]): LinearGradient {
    return { type: 'linear', angle, stops };
}
function radial(cx: number, cy: number, r: number, stops: { position: number; color: string }[]): RadialGradient {
    return { type: 'radial', cx, cy, r, stops };
}

describe('lerpFill type / stop rules (rendering.md §9.4)', () => {
    it('lerps two solids via lerpHex', () => {
        const out = lerpFill(solid('#000000'), solid('#FFFFFF'), 0.5) as SolidFill;
        expect(out.type).toBe('solid');
        expect(out.color).toBe('#BCBCBC');
    });

    it('steps (returns a) when solid color is missing', () => {
        const a = { type: 'solid' } as unknown as Fill;
        expect(lerpFill(a, solid('#FFFFFF'), 0.5)).toBe(a);
    });

    it('lerps two linear gradients: angle linear + per-stop', () => {
        const a = linear(0, [{ position: 0, color: '#000000' }, { position: 1, color: '#000000' }]);
        const b = linear(100, [{ position: 0, color: '#FFFFFF' }, { position: 0.5, color: '#FFFFFF' }]);
        const out = lerpFill(a, b, 0.5) as LinearGradient;
        expect(out.type).toBe('linear');
        expect(out.angle).toBeCloseTo(50, 9);
        expect(out.stops[0].color).toBe('#BCBCBC');
        expect(out.stops[1].position).toBeCloseTo(0.75, 9);
    });

    it('lerps two radial gradients: cx/cy/r linear + per-stop', () => {
        const a = radial(0, 0, 0.5, [{ position: 0, color: '#000000' }, { position: 1, color: '#000000' }]);
        const b = radial(1, 1, 1.5, [{ position: 0, color: '#FFFFFF' }, { position: 1, color: '#FFFFFF' }]);
        const out = lerpFill(a, b, 0.5) as RadialGradient;
        expect(out.type).toBe('radial');
        expect(out.cx).toBeCloseTo(0.5, 9);
        expect(out.cy).toBeCloseTo(0.5, 9);
        expect(out.r).toBeCloseTo(1.0, 9);
        expect(out.stops[0].color).toBe('#BCBCBC');
    });

    it('steps (returns a) on type mismatch', () => {
        const a = solid('#000000');
        expect(lerpFill(a, linear(0, [{ position: 0, color: '#fff' }, { position: 1, color: '#fff' }]), 0.5)).toBe(a);
    });

    it('steps (returns a) on stop-count mismatch', () => {
        const a = linear(0, [{ position: 0, color: '#000000' }, { position: 1, color: '#000000' }]);
        const b = linear(0, [{ position: 0, color: '#FFFFFF' }]);
        expect(lerpFill(a, b, 0.5)).toBe(a);
    });

    it('steps (returns a) when either side is null/undefined', () => {
        const a = solid('#000000');
        expect(lerpFill(a, null as unknown as Fill, 0.5)).toBe(a);
        expect(lerpFill(null as unknown as Fill, a, 0.5)).toBe(null as unknown as Fill);
    });

    it('endpoints t=0 / t=1 reproduce the endpoint fills', () => {
        const a = linear(0, [{ position: 0, color: '#FF0000' }, { position: 1, color: '#00FF00' }]);
        const b = linear(90, [{ position: 0, color: '#0000FF' }, { position: 1, color: '#FFFF00' }]);
        const at0 = lerpFill(a, b, 0) as LinearGradient;
        const at1 = lerpFill(a, b, 1) as LinearGradient;
        expect(at0.angle).toBeCloseTo(0, 9);
        expect(at0.stops[0].color).toBe('#FF0000');
        expect(at1.angle).toBeCloseTo(90, 9);
        expect(at1.stops[1].color).toBe('#FFFF00');
    });
});
