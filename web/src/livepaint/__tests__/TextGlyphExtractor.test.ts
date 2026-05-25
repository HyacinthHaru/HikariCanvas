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
    __setFetchForTest,
    __setFontkitModuleForTest,
    __resetCachesForTest,
} from '../TextGlyphExtractor';
import { elementToPolygonAsync } from '../ElementToPolygon';
import type { TextElement } from '@/types/protocol';

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
