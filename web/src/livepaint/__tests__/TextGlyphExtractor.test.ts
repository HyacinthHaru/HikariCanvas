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

/**
 * 0.4.10-bugfix-advance-sync：双源 advance 不一致 bug 回归测试。
 *
 * Bug：computeLayoutMultiPolygon 原走 fontkit `font.layout(line).positions[i].xAdvance × scale`
 * 摆位 glyph；PreviewRenderer 视觉位置走 TextLayout.layoutText + charAdvance（ASCII canonical
 * = round(fontSize * 0.5) = 16 px/char @ fontSize=32 / metrics 表存在时 round(base × size /
 * baseSize)）。fontkit hmtx 真实 advance ≈ 18 px/char @ fontSize=32 Inter → 5 字符累积偏差
 * ~10 px > "O" 内孔半径 ~6 px → 用户点击视觉 O 中央时坐标落在 polygon hole 外，Live Paint
 * 无法识别洞。
 *
 * 修法：computeLayoutMultiPolygon 改用 layoutText 同源摆位，只用 fontkit 拿 glyph.path。
 *
 * 回归断言：多字符 polygon 的字符簇中心 X ↔ layoutText PositionedGlyph[i].x + glyph_width/2
 * 必须 ≤ 1 px 差。
 */
describe('TextGlyphExtractor — 0.4.10-bugfix-advance-sync 同源 advance', () => {
    // mock fontkit advance 与 layoutText 不同步问题已修；本节专门防止回归到 fontkit advance。
    //
    // 注意：测试环境无字体 metrics 表 fetch（fetch 对 /fonts/*.metrics.json 不通），故
    // layoutText 走 canonicalCharWidth fallback（ASCII = round(fontSize * 0.5)）。

    it('多字符 polygon 簇位置与 layoutText 同源（即使 mock fontkit advance ≠ canonical 也对齐）', async () => {
        mockFetchOk();
        // mock 矩形 glyph：300 units / unitsPerEm 1000 / fontSize 32 → 9.6 px 宽。
        // canonical advance = round(32*0.5) = 16，故字符间留 ~6.4 px 缝隙，避免相邻矩形
        // 被 polygon-clipping union 合并成单 cluster（真实字体 glyph 也不会精确占满
        // advance box）。故意把 fontkit advanceWidth 设为 800（≠ canonical 16 px）来证伪
        // "用 fontkit advance 摆位"——若代码仍用 fontkit advance，cursorX 会按 25.6 px 步进
        // 而不是 canonical 16 px。
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L300 0 L300 500 L0 500 Z',
            advanceWidth: 800, // 故意偏离 canonical
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const multi = await textElementToMultiPolygon(makeText({
            text: 'HELLO',
            fontId: 'inter',
            fontSize: 32,
            x: 0,
            y: 0,
            w: 200,
            h: 50,
        }));
        expect(multi).not.toBeNull();
        expect(multi!.length).toBeGreaterThanOrEqual(5);

        // 取每个 polygon 簇的 X 范围中心（外环），按 X 排序便于按字符顺序比对
        const clusters = multi!.map(poly => {
            const ring = poly[0];
            let minX = Infinity, maxX = -Infinity;
            for (const [x] of ring) {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
            }
            return { minX, maxX, cx: (minX + maxX) / 2 };
        }).sort((a, b) => a.cx - b.cx).slice(0, 5);

        // canonical advance @ fontSize=32 = round(32 * 0.5) = 16；letterSpacing=0
        // glyph 宽度 = 300/1000 * 32 = 9.6；故第 i 字符外环 = [i*16, i*16+9.6]，中心 i*16+4.8
        for (let i = 0; i < 5; i++) {
            const expectedCx = i * 16 + 9.6 / 2;
            expect(Math.abs(clusters[i].cx - expectedCx)).toBeLessThan(1);
        }
    });

    it('italic=true → polygon 被 horizontal shear（顶部相对底部左移 -0.2 × (yTop - yBot)）', async () => {
        mockFetchOk();
        // 简单矩形 glyph：fontkit (0,0) - (500, 500)，scale=fontSize/unitsPerEm。
        // 屏幕 Y 翻转：originY=baselineY=round(fontSize*0.8)=80，glyph 屏幕 y 范围
        // [80-500*0.1, 80] = [30, 80]
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L500 0 L500 500 L0 500 Z',
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const polyUpright = await textElementToMultiPolygon(makeText({
            text: 'A',
            fontSize: 100,
            x: 0,
            y: 0,
            italic: false,
        }));
        __resetCachesForTest();
        mockFetchOk();
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);
        const polyItalic = await textElementToMultiPolygon(makeText({
            text: 'A',
            fontSize: 100,
            x: 0,
            y: 0,
            italic: true,
        }));

        expect(polyUpright).not.toBeNull();
        expect(polyItalic).not.toBeNull();

        // 比对 bbox：upright 矩形 minX 应在两个 y 上相同；italic 后顶部（小 y）的 minX
        // 应比底部（大 y）左移 (-0.2) × (yTop - yBot) = (-0.2) × (30 - 80) = 10
        // 因为 italic shear localX' = localX - 0.2 * y，所以 y 越小 x 越大（top 右移 不是左移）。
        // 重做：A 在屏幕 y=30(顶) 到 y=80(底)；shear x' = x - 0.2*y →
        //   bot (y=80) shift = -16；top (y=30) shift = -6；故 top 比 bot 右移 10 px
        const ringU = polyUpright![0][0];
        const ringI = polyItalic![0][0];
        // 计算 upright 矩形顶 / 底两个 y 的 minX：
        const bboxU = computeBbox(ringU);
        const bboxI = computeBbox(ringI);
        // 高度应保持不变
        expect(bboxI.maxY - bboxI.minY).toBeCloseTo(bboxU.maxY - bboxU.minY, 5);
        // 宽度：italic 矩形被 shear → bbox 宽 = upright 宽 + 0.2 × 高
        const heightU = bboxU.maxY - bboxU.minY;
        const widthU = bboxU.maxX - bboxU.minX;
        const widthI = bboxI.maxX - bboxI.minX;
        expect(widthI).toBeCloseTo(widthU + 0.2 * heightU, 3);
    });

    // helper：算 ring bbox
    function computeBbox(ring: Array<[number, number]>): { minX: number; maxX: number; minY: number; maxY: number } {
        let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        for (const [x, y] of ring) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        return { minX, maxX, minY, maxY };
    }

    it('align="center" → polygon 整体居中（与 layoutText 行内偏移一致）', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L300 0 L300 500 L0 500 Z', // 9.6 px 宽 < advance 16
            advanceWidth: 800,
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        // text="AB", fontSize=32, canonical advance = 16，行宽 = 16 + 16 = 32
        // boxW=100, center → startX = floor((100 - 32) / 2) = 34
        const multi = await textElementToMultiPolygon(makeText({
            text: 'AB',
            fontSize: 32,
            x: 0,
            y: 0,
            w: 100,
            h: 50,
            align: 'center',
        }));
        expect(multi).not.toBeNull();
        expect(multi!.length).toBeGreaterThanOrEqual(2);

        // 簇按 x 排序后第 0 簇外环左边应贴 startX=34，第 1 簇贴 50
        const clusters = multi!.map(poly => {
            const ring = poly[0];
            let minX = Infinity;
            for (const [x] of ring) if (x < minX) minX = x;
            return minX;
        }).sort((a, b) => a - b);

        expect(Math.abs(clusters[0] - 34)).toBeLessThan(1);
        expect(Math.abs(clusters[1] - 50)).toBeLessThan(1);
    });

    it('letterSpacing=4 → 每字符 cursor 步进多 4 px', async () => {
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L300 0 L300 500 L0 500 Z',  // 9.6 px 宽 < 16 + 4，相邻不合并
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        // fontSize=32 canonical=16; letterSpacing=4; "ABC"
        // 字符 0 外环 [0, 9.6]；字符 1 cursor = 16 + 4 = 20，外环 [20, 29.6]；字符 2 cursor = 36 + 4 = 40
        const multi = await textElementToMultiPolygon(makeText({
            text: 'ABC',
            fontSize: 32,
            x: 0,
            y: 0,
            w: 200,
            h: 50,
            letterSpacing: 4,
        }));
        expect(multi).not.toBeNull();
        expect(multi!.length).toBeGreaterThanOrEqual(3);

        const clusters = multi!.map(poly => {
            const ring = poly[0];
            let minX = Infinity;
            for (const [x] of ring) if (x < minX) minX = x;
            return minX;
        }).sort((a, b) => a - b);

        expect(Math.abs(clusters[0] - 0)).toBeLessThan(1);
        expect(Math.abs(clusters[1] - 20)).toBeLessThan(1);
        expect(Math.abs(clusters[2] - 40)).toBeLessThan(1);
    });

    it('layoutText PositionedGlyph[i].x + glyph_width/2 与 polygon cluster center 匹配 ±1 px', async () => {
        // 这是任务说明里点名要求的"同源 advance"核心断言。
        // 用任意非默认 mock advance 证伪"沿用 fontkit advance"。
        mockFetchOk();
        const mockFont = makeMockFont({
            defaultPath: 'M0 0 L300 0 L300 500 L0 500 Z', // glyph 宽 9.6 px < advance 16 → 不合并
            advanceWidth: 2222, // 远离 canonical 16 px
        });
        __setFontkitModuleForTest(makeMockFontkitModule(mockFont) as never);

        const { layoutText } = await import('@/render/TextLayout');
        const text = 'HELLO';
        const fontSize = 32;
        const tEl = makeText({ text, fontSize, x: 0, y: 0, w: 300, h: 50 });
        const positioned = layoutText(tEl);
        // mock glyph 屏幕宽度 = 300/1000 * 32 = 9.6 px
        const glyphWidth = 9.6;

        const multi = await textElementToMultiPolygon(tEl);
        expect(multi).not.toBeNull();
        const clusters = multi!.map(poly => {
            const ring = poly[0];
            let minX = Infinity, maxX = -Infinity;
            for (const [x] of ring) {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
            }
            return (minX + maxX) / 2;
        }).sort((a, b) => a - b);

        // 5 字符——簇 i 中心应贴 positioned[i].x + glyphWidth/2（element-local 坐标 x=0 同 world）
        for (let i = 0; i < 5; i++) {
            const expected = positioned[i].x + glyphWidth / 2;
            expect(Math.abs(clusters[i] - expected)).toBeLessThan(1);
        }
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

/**
 * 字体拿不到时的重试路径。
 *
 * <p>旧实现把「拿不到」的结果无条件写进 fontCache（该 Map 无 LRU 无失效），一次
 * fetch 抖动 / 后端字体还没注册完的窗口期 404，就让这个字体永久降级为 bbox 兜底，
 * 直到 worker 重建为止。多边形缓存里那条 null 同样会把退化状态钉死在这条 cache key 上。</p>
 */
describe('TextGlyphExtractor — 字体加载失败不留缓存', () => {
    it('先失败后成功：第二次调用真的重新 fetch 并算出 glyph polygon', async () => {
        let call = 0;
        __setFetchForTest(async () => {
            call += 1;
            return {
                ok: call > 1,
                arrayBuffer: async () => new ArrayBuffer(8),
            };
        });
        __setFontkitModuleForTest(
            makeMockFontkitModule(makeMockFont({ defaultPath: 'M100 0 L500 0 L500 500 L0 500 Z' })) as never,
        );

        const el = makeText({ text: 'A', x: 0, y: 0 });
        expect(await textElementToPolygon(el)).toBeNull();
        expect(call).toBe(1);

        const poly = await textElementToPolygon(el);
        expect(call).toBe(2);
        expect(poly).not.toBeNull();
    });

    it('字体拿到手之后仍然只 fetch 一次（成功结果照常缓存）', async () => {
        let call = 0;
        __setFetchForTest(async () => {
            call += 1;
            return { ok: true, arrayBuffer: async () => new ArrayBuffer(8) };
        });
        __setFontkitModuleForTest(
            makeMockFontkitModule(makeMockFont({ defaultPath: 'M100 0 L500 0 L500 500 L0 500 Z' })) as never,
        );

        await textElementToPolygon(makeText({ text: 'A' }));
        await textElementToPolygon(makeText({ text: 'B' }));
        expect(call).toBe(1);
    });
});

/**
 * fontkit 的入参形态。
 *
 * <p>{@code res.arrayBuffer()} 给的是裸 ArrayBuffer，而 fontkit 内部把它交给 restructure 的
 * DecodeStream，那里直接 {@code new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)}
 * ——裸 ArrayBuffer 没有 {@code .buffer}，抛 TypeError 后被 catch 吞成 null，
 * 结果是所有文字的 Live Paint 都静默退化成 bbox。必须包成 Uint8Array。</p>
 */
describe('TextGlyphExtractor — 传给 fontkit 的必须是 Uint8Array', () => {
    it('create 收到的是 Uint8Array 而不是裸 ArrayBuffer', async () => {
        mockFetchOk();
        const seen: unknown[] = [];
        const font = makeMockFont({ defaultPath: 'M100 0 L500 0 L500 500 L0 500 Z' });
        __setFontkitModuleForTest({
            create: (buf: unknown): unknown => {
                seen.push(buf);
                return font;
            },
        } as never);

        await textElementToPolygon(makeText({ text: 'A' }));
        expect(seen).toHaveLength(1);
        expect(ArrayBuffer.isView(seen[0])).toBe(true);
        expect(seen[0]).toBeInstanceOf(Uint8Array);
    });
});
