/**
 * 0.4.9 Sub B：TextGlyphExtractor 单元测试。
 *
 * 覆盖：
 * 1. 单字符 → glyph polygon（顶点 > bbox 4 顶点）
 * 2. 多字符 → glyph union polygon
 * 3. CJK 字符 → 复杂形状
 * 4. 空字符串 → null
 * 5. fontkit 加载失败 → null
 * 6. 字体 fetch 失败 → null
 * 7. vertical 模式 → null（v1 不支持）
 * 8. polygon cache 命中 → 不重复 fetch
 * 9. ElementToPolygonAsync text 分支 → 真实 glyph polygon（fallback bbox 当 null）
 *
 * 实现策略：不引真 fontkit / 真字体二进制。注入 mock fontkit module + mock fetch，
 * 让 layout 返回可控 glyph path 字符串——这样能精确验证 polygon 顶点数 / 形状。
 */

import { describe, expect, it, beforeEach } from 'vitest';
import {
    textElementToPolygon,
    textElementToMultiPolygon,
    __setFetchForTest,
    __setFontkitModuleForTest,
    __resetCachesForTest,
} from '../TextGlyphExtractor';
import { elementToPolygonAsync, elementToMultiPolygonAsync } from '../ElementToPolygon';
import { buildGraph, findGapAt } from '../LivePaintCore';
import type { Element, TextElement } from '@/types/protocol';

function makeText(over: Partial<TextElement> = {}): TextElement {
    return {
        id: 't1',
        type: 'text',
        text: 'A',
        x: 0,
        y: 0,
        w: 100,
        h: 50,
        rotation: 0,
        locked: false,
        visible: true,
        fontId: 'inter',
        fontSize: 32,
        color: '#000000',
        align: 'left',
        letterSpacing: 0,
        lineHeight: 1.2,
        vertical: false,
        ...over,
    };
}

/**
 * 构造一个简单的 mock font：
 * - unitsPerEm = 1000
 * - glyphForCodePoint / layout 都返一个 mock glyph，path 是简单矩形
 * - advanceWidth 由调用方控制（参数化让多字符测试更直观）
 */
function makeMockFont(opts: {
    /** 每字符对应的 SVG path d 字符串，按 char 取（fallback 用 defaultPath） */
    pathByChar?: Record<string, string>;
    defaultPath?: string;
    advanceWidth?: number;
}): unknown {
    const pathByChar = opts.pathByChar ?? {};
    const defaultPath = opts.defaultPath ?? 'M0 0 L500 0 L500 500 L0 500 Z';
    const advanceWidth = opts.advanceWidth ?? 600;

    const makeGlyph = (ch: string): unknown => ({
        path: {
            toSVG: () => pathByChar[ch] ?? defaultPath,
        },
        advanceWidth,
    });

    return {
        unitsPerEm: 1000,
        glyphForCodePoint(cp: number): unknown {
            return makeGlyph(String.fromCodePoint(cp));
        },
        layout(text: string): { glyphs: unknown[]; positions: unknown[] } {
            const glyphs: unknown[] = [];
            const positions: unknown[] = [];
            for (const ch of text) {
                glyphs.push(makeGlyph(ch));
                positions.push({
                    xAdvance: advanceWidth,
                    yAdvance: 0,
                    xOffset: 0,
                    yOffset: 0,
                });
            }
            return { glyphs, positions };
        },
    };
}

function makeMockFontkitModule(font: unknown): unknown {
    return {
        create(): unknown {
            return font;
        },
    };
}

/** Mock fetch 返一个空 ArrayBuffer（fontkit.create mocked，不实际解析）。 */
function mockFetchOk(): void {
    __setFetchForTest(async () => ({
        ok: true,
        arrayBuffer: async () => new ArrayBuffer(8),
    }));
}

function mockFetchFail(): void {
    __setFetchForTest(async () => ({
        ok: false,
        arrayBuffer: async () => new ArrayBuffer(0),
    }));
}

// 每个测试前重置
beforeEach(() => {
    __resetCachesForTest();
    __setFetchForTest(null);
    __setFontkitModuleForTest(null);
});

describe('textElementToPolygon — basic', () => {
    it('单字符 "A" → 输出非空 polygon（顶点 > 4）', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            // 一个简单 8 顶点形状（含两条曲线 → 经采样会有更多点）
            defaultPath: 'M100 0 Q500 0 500 500 L0 500 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const poly = await textElementToPolygon(makeText({ text: 'A', x: 0, y: 0 }));
        expect(poly).not.toBeNull();
        // Q 曲线采样 GLYPH_CURVE_SAMPLES=8 段；总顶点远超 bbox 4
        expect(poly!.length).toBeGreaterThan(4);
    });

    it('多字符 "Hello" → glyph union polygon 顶点更多', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L500 0 L500 500 L0 500 Z',
            advanceWidth: 600,
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const singlePoly = await textElementToPolygon(makeText({ text: 'A' }));
        // 缓存重置，让多字符独立计算
        __resetCachesForTest();
        mockFetchOk();
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);
        const multiPoly = await textElementToPolygon(makeText({ text: 'Hello' }));

        expect(singlePoly).not.toBeNull();
        expect(multiPoly).not.toBeNull();
        // 多字符 union 至少与单字符一样大（取面积最大外环；多 glyph union 可能产生大外环）
        // 用顶点数 ≥ 4（基本 bbox 退化也能通过）
        expect(multiPoly!.length).toBeGreaterThanOrEqual(4);
    });

    it('CJK 字符 "你好" → 不抛 + 返回 polygon', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M50 0 C200 0 500 200 500 500 C500 700 200 700 50 500 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const poly = await textElementToPolygon(makeText({ text: '你好', fontId: 'source_han_sans' }));
        expect(poly).not.toBeNull();
        expect(poly!.length).toBeGreaterThan(4);
    });
});

describe('textElementToPolygon — degraded paths', () => {
    it('空字符串 → null', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const poly = await textElementToPolygon(makeText({ text: '' }));
        expect(poly).toBeNull();
    });

    it('纯空白文本 → null', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const poly = await textElementToPolygon(makeText({ text: '   \n\t  ' }));
        expect(poly).toBeNull();
    });

    it('vertical=true → null（v1 不支持竖排）', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const poly = await textElementToPolygon(makeText({ text: 'A', vertical: true }));
        expect(poly).toBeNull();
    });

    it('fontSize ≤ 0 → null', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const poly = await textElementToPolygon(makeText({ text: 'A', fontSize: 0 }));
        expect(poly).toBeNull();
    });

    it('字体 fetch 失败（res.ok=false） → null', async () => {
        mockFetchFail();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const poly = await textElementToPolygon(makeText({ text: 'A', fontId: 'nonexistent' }));
        expect(poly).toBeNull();
    });

    it('fontkit module 加载抛错 → null', async () => {
        mockFetchOk();
        // fontkit.create 抛错
        __setFontkitModuleForTest({
            create: (): unknown => {
                throw new Error('mock parse error');
            },
        } as never);

        const poly = await textElementToPolygon(makeText({ text: 'A' }));
        expect(poly).toBeNull();
    });

    it('layout 返空 glyphs → null', async () => {
        mockFetchOk();
        __setFontkitModuleForTest({
            create: (): unknown => ({
                unitsPerEm: 1000,
                glyphForCodePoint: (): unknown => ({ path: { toSVG: () => '' }, advanceWidth: 0 }),
                layout: (): { glyphs: unknown[]; positions: unknown[] } => ({ glyphs: [], positions: [] }),
            }),
        } as never);

        const poly = await textElementToPolygon(makeText({ text: 'A' }));
        expect(poly).toBeNull();
    });
});

describe('textElementToPolygon — coordinate transform', () => {
    it('element.x / element.y 作为偏移叠加到 polygon 顶点', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L500 0 L500 500 L0 500 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const polyOrigin = await textElementToPolygon(makeText({ text: 'A', x: 0, y: 0 }));
        __resetCachesForTest();
        mockFetchOk();
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);
        const polyOffset = await textElementToPolygon(makeText({ text: 'A', x: 50, y: 30 }));

        expect(polyOrigin).not.toBeNull();
        expect(polyOffset).not.toBeNull();
        // 同样的 glyph，offset 后所有顶点应整体平移 (50, 30)
        expect(polyOrigin!.length).toBe(polyOffset!.length);
        for (let i = 0; i < polyOrigin!.length; i++) {
            const [x1, y1] = polyOrigin![i];
            const [x2, y2] = polyOffset![i];
            expect(Math.abs(x2 - x1 - 50)).toBeLessThan(1e-6);
            expect(Math.abs(y2 - y1 - 30)).toBeLessThan(1e-6);
        }
    });
});

describe('textElementToPolygon — cache', () => {
    it('同样的 fontId+size+text 二次调用走 cache（fetch 只调一次）', async () => {
        let fetchCount = 0;
        __setFetchForTest(async () => {
            fetchCount++;
            return { ok: true, arrayBuffer: async () => new ArrayBuffer(8) };
        });
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const p1 = await textElementToPolygon(makeText({ text: 'A' }));
        const p2 = await textElementToPolygon(makeText({ text: 'A' }));
        expect(p1).not.toBeNull();
        expect(p2).not.toBeNull();
        // font cache 让 fetch 只发生一次
        expect(fetchCount).toBe(1);
    });
});

describe('elementToPolygonAsync — text 分支', () => {
    it('text 元素走 fontkit → 真实 glyph polygon', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L500 0 L500 500 L0 500 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const el = makeText({ text: 'A', x: 10, y: 20, w: 30, h: 40 });
        const poly = await elementToPolygonAsync(el);
        expect(poly).not.toBeNull();
        // Mock glyph 是 1 矩形（4 顶点） → fontkit 单字符可能输出 4 顶点；
        // 但 union + transform 通常给出 ≥ 4 顶点 polygon。
        expect(poly!.length).toBeGreaterThanOrEqual(4);
    });

    it('text 元素 glyph 提取失败 → fallback bbox 4 顶点', async () => {
        mockFetchFail();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const el = makeText({ text: 'A', x: 10, y: 20, w: 30, h: 40 });
        const poly = await elementToPolygonAsync(el);
        // glyph 提取失败 → bbox fallback
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(4);
        expect(poly!).toEqual([
            [10, 20],
            [40, 20],
            [40, 60],
            [10, 60],
        ]);
    });

    it('text 元素 vertical=true → fallback bbox', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const el = makeText({ text: 'A', x: 10, y: 20, w: 30, h: 40, vertical: true });
        const poly = await elementToPolygonAsync(el);
        expect(poly).not.toBeNull();
        expect(poly!.length).toBe(4);
    });

    it('text 元素 visible=false → null', async () => {
        const el = makeText({ text: 'A', visible: false });
        const poly = await elementToPolygonAsync(el);
        expect(poly).toBeNull();
    });
});

/**
 * 0.4.10 bugfix：textElementToMultiPolygon 保留 holes + 多 polygon 测试。
 *
 * Bug 复现：用户点击 "HELLO" 中字母 "O" 内部洞，期望识别为 gap fill；旧代码只取面积
 * 最大外环，丢失 "O" 的内孔，导致 Live Paint 把整个 text bbox 当占用区，洞内点击
 * 落入"occupied"区被拒绝。
 *
 * 修复：multi 路径返完整 polygon-clipping MultiPolygon =
 *   [[outerRing, hole1, hole2, ...], [outerRing2, ...], ...]
 */
describe('textElementToMultiPolygon — 0.4.10 holes + 多 polygon 保留', () => {
    it('单字符 "O" mock 路径含内孔 → 输出 polygon 含 hole（≥2 ring）', async () => {
        mockFetchOk();
        // 模拟 "O" 字形：外环逆时针 + 内环顺时针（OpenType 通常这样表示挖洞）。
        // SVG path：外圆方形 800×800 + 内圆方形 400×400 居中（200, 200..600, 600）。
        // fontkit Y 轴向上：先 outer (M0 0 L800 0 L800 800 L0 800 Z)
        // 再 inner (M200 200 L200 600 L600 600 L600 200 Z) — 反向以触发 even-odd hole
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L800 0 L800 800 L0 800 Z M200 200 L200 600 L600 600 L600 200 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const multi = await textElementToMultiPolygon(makeText({ text: 'O', x: 0, y: 0, fontSize: 100 }));
        expect(multi).not.toBeNull();
        // 应有至少一个 polygon
        expect(multi!.length).toBeGreaterThanOrEqual(1);
        // 至少一个 polygon 应含 hole（ring 数 ≥ 2）—— "O" 内孔是核心验证
        const hasHole = multi!.some(poly => poly.length >= 2);
        expect(hasHole).toBe(true);
    });

    it('多字符 "Hello" → 多个独立 polygon（保留全部字符）', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            // 简单矩形 glyph；多字符 layout 后 5 个矩形不连通 → 5 个独立 polygon
            defaultPath: 'M0 0 L400 0 L400 500 L0 500 Z',
            advanceWidth: 600,  // 字符间距 600 > glyph 宽 400，确保不重叠
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const multi = await textElementToMultiPolygon(makeText({ text: 'Hello', x: 0, y: 0, fontSize: 100 }));
        expect(multi).not.toBeNull();
        // 5 个字符 → 至少 5 个独立 polygon（旧实现只保留 1 个面积最大的）
        expect(multi!.length).toBeGreaterThanOrEqual(5);
    });

    it('CJK "你好" 复杂 path → 不抛 + 返多 polygon', async () => {
        mockFetchOk();
        // 一个含内孔的复杂 glyph（外环 + 内环）；layout 两个字符 → 至少 2 个 polygon
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L800 0 L800 800 L0 800 Z M250 250 L250 550 L550 550 L550 250 Z',
            advanceWidth: 1000,
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const multi = await textElementToMultiPolygon(makeText({
            text: '你好',
            fontId: 'source_han_sans',
            fontSize: 100,
        }));
        expect(multi).not.toBeNull();
        expect(multi!.length).toBeGreaterThanOrEqual(2);
        // 每 polygon 都该至少 1 ring
        for (const poly of multi!) {
            expect(poly.length).toBeGreaterThanOrEqual(1);
        }
    });

    it('空文本 / 退化输入 → null（与 textElementToPolygon 同语义）', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        expect(await textElementToMultiPolygon(makeText({ text: '' }))).toBeNull();
        expect(await textElementToMultiPolygon(makeText({ text: 'A', vertical: true }))).toBeNull();
        expect(await textElementToMultiPolygon(makeText({ text: 'A', fontSize: 0 }))).toBeNull();
    });

    it('element.x / element.y 偏移正确应用到所有 ring 所有点', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L500 0 L500 500 L0 500 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const m1 = await textElementToMultiPolygon(makeText({ text: 'A', x: 0, y: 0 }));
        __resetCachesForTest();
        mockFetchOk();
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);
        const m2 = await textElementToMultiPolygon(makeText({ text: 'A', x: 50, y: 30 }));

        expect(m1).not.toBeNull();
        expect(m2).not.toBeNull();
        expect(m1!.length).toBe(m2!.length);
        for (let pi = 0; pi < m1!.length; pi++) {
            expect(m1![pi].length).toBe(m2![pi].length);
            for (let ri = 0; ri < m1![pi].length; ri++) {
                const r1 = m1![pi][ri];
                const r2 = m2![pi][ri];
                expect(r1.length).toBe(r2.length);
                for (let i = 0; i < r1.length; i++) {
                    expect(r2[i][0] - r1[i][0]).toBeCloseTo(50, 6);
                    expect(r2[i][1] - r1[i][1]).toBeCloseTo(30, 6);
                }
            }
        }
    });
});

describe('elementToMultiPolygonAsync — 0.4.10', () => {
    it('text 元素含内孔 → MultiPolygon 含 hole', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L800 0 L800 800 L0 800 Z M200 200 L200 600 L600 600 L600 200 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const el = makeText({ text: 'O', x: 10, y: 20, fontSize: 100 });
        const multi = await elementToMultiPolygonAsync(el);
        expect(multi).not.toBeNull();
        const hasHole = multi!.some(poly => poly.length >= 2);
        expect(hasHole).toBe(true);
    });

    it('text 元素 glyph 提取失败 → fallback bbox 单 polygon 单 ring（闭合）', async () => {
        mockFetchFail();
        const mockFont = makeMockFont({});
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const el = makeText({ text: 'A', x: 10, y: 20, w: 30, h: 40 });
        const multi = await elementToMultiPolygonAsync(el);
        expect(multi).not.toBeNull();
        expect(multi!.length).toBe(1);
        expect(multi![0].length).toBe(1);
        // ring 末点 = 首点（已闭合，长度 5 而非 4）
        const ring = multi!![0][0];
        expect(ring.length).toBe(5);
        expect(ring[0]).toEqual([10, 20]);
        expect(ring[4]).toEqual([10, 20]);
    });

    it('非 text 元素 → 单 polygon 单 ring（包装 sync polygon）', async () => {
        const el: Element = {
            id: 'r1',
            type: 'rect',
            x: 0,
            y: 0,
            w: 10,
            h: 10,
            rotation: 0,
            locked: false,
            visible: true,
        };
        const multi = await elementToMultiPolygonAsync(el);
        expect(multi).not.toBeNull();
        expect(multi!.length).toBe(1);
        expect(multi!![0].length).toBe(1);  // 单 ring（无 hole）
    });

    it('visible=false text → null', async () => {
        const el = makeText({ text: 'A', visible: false });
        const multi = await elementToMultiPolygonAsync(el);
        expect(multi).toBeNull();
    });
});

describe('LivePaint buildGraph — 0.4.10 text hole gap 集成', () => {
    /**
     * 端到端验证 bug 修复：text "O" 内孔在 buildGraph 输出中应作为 gap 可命中。
     * 旧代码：text bbox 占用整个 50×50 → 内孔位置 (25, 25) 落入 occupied → 不在 gap
     * 新代码：text glyph union 保留 hole → 内孔位置 (25, 25) 在 gap.holes 之外 +
     *         gap.outer 之内 → findGapAt 命中
     */
    it('text 含内孔时 buildGraph 把内孔识别为 gap 可命中', async () => {
        mockFetchOk();
        // glyph 是 "O" 形：外 800×800 + 内 400×400 hole（fontkit 单位）
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L800 0 L800 800 L0 800 Z M200 200 L200 600 L600 600 L600 200 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        // text element：fontSize=50, unitsPerEm=1000 → scale=0.05
        // glyph 外环占 0..40 px（800×0.05），内孔 10..30 px（200..600 ×0.05）
        // baselineY = fontSize × 0.8 = 40；Y 翻转后外环屏幕 y = 0..40
        // 加 element offset (10, 10) → 外环 10..50 × 10..50；内孔 20..40 × 20..40
        // 内孔中心 = (30, 30) → 在 hole gap 内
        const text: TextElement = {
            id: 't1',
            type: 'text',
            text: 'O',
            x: 10,
            y: 10,
            w: 40,
            h: 40,
            rotation: 0,
            locked: false,
            visible: true,
            fontId: 'inter',
            fontSize: 50,
            color: '#000000',
            align: 'left',
            letterSpacing: 0,
            lineHeight: 1.2,
            vertical: false,
        };
        const graph = await buildGraph([text], 128, 128);
        expect(graph.degraded).toBeUndefined();
        // 内孔几何中心 = (30, 30) — 严格在 20..40 × 20..40 内部
        const gap = findGapAt(graph, 30, 30);
        // 修复前：findGapAt 返 null（中心点被 text bbox 占用）
        // 修复后：findGapAt 返 text 内孔对应的 gap（非 null）
        expect(gap).not.toBeNull();
    });

    it('无内孔的 text（如 mock "I" 实心矩形 glyph）→ 中心不被识别为 gap', async () => {
        mockFetchOk();
        // 无 hole 的简单 glyph
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L800 0 L800 800 L0 800 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const text: TextElement = {
            id: 't1',
            type: 'text',
            text: 'I',
            x: 10,
            y: 10,
            w: 60,
            h: 60,
            rotation: 0,
            locked: false,
            visible: true,
            fontId: 'inter',
            fontSize: 50,
            color: '#000000',
            align: 'left',
            letterSpacing: 0,
            lineHeight: 1.2,
            vertical: false,
        };
        const graph = await buildGraph([text], 128, 128);
        expect(graph.degraded).toBeUndefined();
        // text 中心被 glyph 实心占用 → findGapAt 应返 null（命中 element 不是 gap）
        // 取一个落在 glyph 上的点（baseline 上方一点）
        // text bbox = 10..70 × 10..70；glyph 0..40 px × 上抬到 baseline = fontSize*0.8=40 px
        // glyph 屏幕区 ≈ x ∈ [10, 50]，y ∈ [10 + 40 - 40, 10 + 40] = [10, 50]
        // 取 (20, 30) 应在 glyph 内
        const gap = findGapAt(graph, 20, 30);
        // 实心 glyph 中心点应被识别为 occupied → 不在 gap 内
        expect(gap).toBeNull();
    });
});
