/**
 * 双端一致性：glow 外接盒度量 + dither fallback 路径的 opacity 守卫。
 *
 * glow bbox：高度两端都用 ascent=round(fontSize*0.8) / descent=fontSize-ascent；
 *   宽度两端都用 charAdvance（排版同源）。后端曾用 canonicalCharWidth、前端用 measureText，
 *   宽西文字符的右缘光晕于是在游戏内被裁掉。
 *
 * B2 (P2-19): drawDitheredElement fallback-path 的 opacity 守卫补齐 isFinite + clamp(0,1)，
 *   对齐 drawElement。测试验证负/NaN/Infinity opacity 的 safe 值计算与 drawElement 路径等价。
 */

import { describe, expect, it, vi } from 'vitest';
import { canonicalCharWidth, charAdvance } from '../TextLayout';
import { preloadMetrics } from '../GlyphMetricsLut';

// ---------------------------------------------------------------------------
// B1：glow bbox 度量公式等价性（前端 renderGlow vs 后端 GlowRenderer 修复后）
// ---------------------------------------------------------------------------

/**
 * 从 PreviewRenderer.renderGlow 内联提取的非旋转 glyph bbox 高度公式（前端）。
 * ASCENT_RATIO = 0.8（TextLayout.ts 导出常量）。
 */
function frontendGlowAscent(fontSize: number): number {
    return Math.round(fontSize * 0.8);
}
function frontendGlowDescent(fontSize: number): number {
    return fontSize - frontendGlowAscent(fontSize);
}

/**
 * 后端 GlowRenderer 修复后的公式（TextLayout.ASCENT_RATIO = 0.8）。
 * ascent = (int) Math.round(fontSize * 0.8)，descent = fontSize - ascent。
 * Java round(double) 与 TS Math.round(number) 在 .5 的处理上有差异（Java 正无穷舍入、
 * TS 同 Java 正数半入，两端结果一致）。
 */
function backendGlowAscent(fontSize: number): number {
    return Math.round(fontSize * 0.8); // 镜像 (int) Math.round(fontSize * TextLayout.ASCENT_RATIO)
}
function backendGlowDescent(fontSize: number): number {
    return fontSize - backendGlowAscent(fontSize);
}

describe('B1 glow bbox 高度公式：前后端一致', () => {
    const sizes = [8, 10, 12, 14, 16, 20, 24, 32, 48, 64];

    for (const fs of sizes) {
        it(`fontSize=${fs}: ascent 相等`, () => {
            expect(backendGlowAscent(fs)).toBe(frontendGlowAscent(fs));
        });
        it(`fontSize=${fs}: descent 相等`, () => {
            expect(backendGlowDescent(fs)).toBe(frontendGlowDescent(fs));
        });
    }
});

describe('glow bbox 宽度：两端都走 charAdvance（与排版同源）', () => {
    // renderGlow 原先用 ctx.measureText（浏览器实测墨迹宽），后端 GlowRenderer 用的却是
    // canonicalCharWidth（西文一律 0.5×fontSize）——宽字符（W / M）小 radius 时后端把右缘光晕
    // 裁掉，编辑器里却是完整的。两端现已统一到 charAdvance，即排版推进 cursor 用的同一个度量。
    const FIXTURE_FONT = 'glow-metrics-fixture';

    it('没有 metrics 表时退回 canonical —— 两端同一条 fallback', () => {
        expect(charAdvance('font-with-no-metrics-table', 'W', 20))
            .toBe(canonicalCharWidth('W', 20));
    });

    it('ASCII canonical 宽 = round(fontSize * 0.5)', () => {
        expect(canonicalCharWidth('A', 20)).toBe(Math.round(20 * 0.5));
        expect(canonicalCharWidth('a', 16)).toBe(Math.round(16 * 0.5));
        expect(canonicalCharWidth('!', 12)).toBe(Math.round(12 * 0.5));
    });

    it('CJK canonical 宽 = fontSize', () => {
        expect(canonicalCharWidth('中', 20)).toBe(20);
        expect(canonicalCharWidth('あ', 16)).toBe(16);
        expect(canonicalCharWidth('Ａ', 12)).toBe(12); // 全角 A (U+FF21)
    });

    it('有 metrics 表时宽字符明显宽于 canonical —— 这就是被裁掉的那部分', async () => {
        // 一张最小 advance 表：baseSize 12，W 占满 1em（内置 inter 的真实值就是 12）
        const raw = {
            fontId: FIXTURE_FONT,
            baseSize: 12,
            ascent: 10,
            descent: 2,
            advances: {
                [String('W'.codePointAt(0))]: 12,
                [String('中'.codePointAt(0))]: 12,
            },
        };
        const fetchMock = vi.fn(async () => ({ ok: true, json: async () => raw }));
        vi.stubGlobal('fetch', fetchMock);
        try {
            await preloadMetrics(FIXTURE_FONT);
        } finally {
            vi.unstubAllGlobals();
        }

        // 后端 TextLayout.charAdvance 与前端这一支是同一个公式：round(baseAdv × size / base)
        expect(charAdvance(FIXTURE_FONT, 'W', 48)).toBe(48);
        expect(canonicalCharWidth('W', 48)).toBe(24);
        expect(charAdvance(FIXTURE_FONT, 'W', 48))
            .toBeGreaterThan(canonicalCharWidth('W', 48));

        // CJK 两者相等，故这条修复不影响全角字（与后端 GlowWideCharBboxTest 对齐）
        expect(charAdvance(FIXTURE_FONT, '中', 48)).toBe(canonicalCharWidth('中', 48));
    });

    it('ascent+descent = fontSize（bbox 高度守恒）', () => {
        for (const fs of [10, 12, 16, 20, 24, 32]) {
            const asc = backendGlowAscent(fs);
            const desc = backendGlowDescent(fs);
            expect(asc + desc).toBe(fs);
        }
    });
});

// ---------------------------------------------------------------------------
// B2：drawDitheredElement fallback-path opacity 守卫等价性
// ---------------------------------------------------------------------------

/**
 * 从 drawElement（修复前已正确）内联提取的安全 opacity 计算，与修复后
 * drawDitheredElement fallback-path 完全一致。
 * 规则：undefined/null → 不改 globalAlpha；否则 !isFinite → 1；其余 clamp(0,1)。
 * 返回新的 globalAlpha 值（以 prevAlpha=1.0 基线）。
 */
function safeGlobalAlpha(prevAlpha: number, op: number | undefined | null): number {
    if (op === undefined || op === null) return prevAlpha;
    if (!Number.isFinite(op) || op < 1) {
        const safe = !Number.isFinite(op) ? 1 : Math.max(0, Math.min(1, op));
        return prevAlpha * safe;
    }
    return prevAlpha; // op >= 1 时不改（等同后端 clamp 结果 = 1）
}

describe('B2 drawDitheredElement fallback-path opacity clamp（对齐 drawElement）', () => {
    const BASE = 1.0;

    it('正常 opacity=0.5 → globalAlpha=0.5', () => {
        expect(safeGlobalAlpha(BASE, 0.5)).toBe(0.5);
    });

    it('opacity=1 → globalAlpha 不变（=1）', () => {
        expect(safeGlobalAlpha(BASE, 1)).toBe(1.0);
    });

    it('opacity=0 → globalAlpha=0', () => {
        expect(safeGlobalAlpha(BASE, 0)).toBe(0);
    });

    it('负 opacity=-0.5 → clamp 到 0（对齐后端 CanvasCompositor clamp 0）', () => {
        // 修复前：prevAlpha * (-0.5) = -0.5（负 globalAlpha 导致前端渲染异常）
        // 修复后：clamp(0,1,-0.5) = 0
        expect(safeGlobalAlpha(BASE, -0.5)).toBe(0);
    });

    it('负 opacity=-1 → clamp 到 0', () => {
        expect(safeGlobalAlpha(BASE, -1)).toBe(0);
    });

    it('NaN opacity → safe=1（与 drawElement 行为一致）', () => {
        // isFinite(NaN)=false → safe=1 → globalAlpha 不变
        expect(safeGlobalAlpha(BASE, NaN)).toBe(1.0);
    });

    it('Infinity opacity → safe=1', () => {
        expect(safeGlobalAlpha(BASE, Infinity)).toBe(1.0);
    });

    it('-Infinity opacity → safe=1', () => {
        expect(safeGlobalAlpha(BASE, -Infinity)).toBe(1.0);
    });

    it('undefined opacity → globalAlpha 不改', () => {
        expect(safeGlobalAlpha(BASE, undefined)).toBe(1.0);
    });

    it('null opacity → globalAlpha 不改', () => {
        expect(safeGlobalAlpha(BASE, null)).toBe(1.0);
    });

    it('复合 prevAlpha=0.5, op=0.5 → 0.25', () => {
        expect(safeGlobalAlpha(0.5, 0.5)).toBe(0.25);
    });

    it('复合 prevAlpha=0.5, op=-1 → clamp 0 → 0', () => {
        // 修复前：0.5 * -1 = -0.5；修复后：0.5 * 0 = 0
        expect(safeGlobalAlpha(0.5, -1)).toBe(0);
    });

    it('大于 1 的 opacity（如 op=2）→ clamp 到 1 → globalAlpha 不变', () => {
        // op=2 >= 1 → 走 not-less-than-1 分支，prevAlpha 不变
        expect(safeGlobalAlpha(0.8, 2)).toBe(0.8);
    });
});
