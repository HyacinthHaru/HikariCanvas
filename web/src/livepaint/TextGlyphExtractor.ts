/**
 * 0.4.9 Sub B — Text glyph 真实形状提取（Live Paint）。
 *
 * 输入 TextElement，输出每个字符 glyph 的真实 path union 后的单 ring polygon。
 * 升级前：ElementToPolygon 对 text 走 bbox 兜底——Live Paint 把字与字之间空隙也当占用，
 * 点字间空白会误判为 element 内部。
 * 升级后：fontkit 解析字体 → layout glyphs → 每个 glyph SVG path → 采样多边形 →
 * polygon-clipping union → 单外环 polygon。
 *
 * 设计决策：
 *   1. **dynamic import fontkit**：避免 ~150 kB 进主 bundle；Live Paint + text 触发时才加载。
 *   2. **字体二进制缓存**：fontkit Font 实例按 fontId 内存常驻；同一 fontId 不重复 fetch。
 *   3. **polygon 结果缓存**：按 `fontId|size|text|letterSpacing|lineHeight` 缓存 polygon。
 *      用户拖动 text 元素时坐标变了但 cache key 一致——只需平移即可，但 cache 命中后
 *      调用方拿到的是 element-local 偏移 + 已 baked global 坐标的 polygon，所以这里
 *      cache 存"以 (0,0) 为原点的 layout polygon"，调用方加偏移。
 *   4. **v1 限制**：仅单行 / 多行（按 `\n` 拆）水平 layout；不支持 wrap by box-width、
 *      不支持 vertical 模式、不支持 letterSpacing 完整复刻 TextLayout.ts。无法处理时
 *      返回 null 让上层 fallback bbox。这是用户已知的"glyph v1"行为。
 *   5. **退化兜底**：fontkit 加载失败 / 字体 fetch 失败 / glyph path 为空 → 返回 null。
 *
 * 性能：
 *   - 短文本 (≤20 字符) 全流程 ~5-15ms（worker 内隔离）
 *   - 长 / CJK 文本：glyph 路径采样 + union 可能 50-100ms；缓存后第二次 < 1ms
 *
 * 双端镜像：不需要后端镜像（CLAUDE.md M18 §1 — Live Paint 是前端独占功能）。
 */

import polygonClipping from 'polygon-clipping';
import type { Pair, MultiPolygon as PCMultiPolygon, Polygon as PCPolygon, Ring as PCRing } from 'polygon-clipping';
import type { TextElement } from '@/types/protocol';
import type { Polygon } from './types';

/** glyph path bezier 采样段数（与 ElementToPolygon PATH_CURVE_SAMPLES 一致）。 */
export const GLYPH_CURVE_SAMPLES = 8;

/** 多行文本 lineHeight 默认倍率，与 TextLayout.ts 一致。 */
const DEFAULT_LINE_HEIGHT_MUL = 1.2;

// ---------- fontkit 最小类型声明 ----------
// fontkit 不提供 d.ts；仅声明本模块用到的部分接口。

interface FontkitGlyph {
    /** 已解析的 path 对象 */
    path: FontkitPath;
    /** 设计单元下的 advanceWidth */
    advanceWidth: number;
}

interface FontkitPath {
    /** SVG path d 字符串。fontkit 文档：path.toSVG() 返 d string */
    toSVG(): string;
    /** 直接得到 commands 数组（fontkit Path API） */
    commands?: Array<{ command: string; args: number[] }>;
}

interface FontkitGlyphPosition {
    xAdvance: number;
    yAdvance: number;
    xOffset: number;
    yOffset: number;
}

interface FontkitGlyphRun {
    glyphs: FontkitGlyph[];
    positions: FontkitGlyphPosition[];
}

interface FontkitFont {
    unitsPerEm: number;
    /** 单位 = font design units */
    glyphForCodePoint(cp: number): FontkitGlyph;
    /**
     * 复杂字形 layout（处理 GSUB/GPOS）。返回 GlyphRun。
     */
    layout(text: string, features?: string[]): FontkitGlyphRun;
}

interface FontkitModule {
    create(buffer: ArrayBuffer | Uint8Array): FontkitFont;
}

// ---------- fontkit 模块 / 字体 cache ----------

let fontkitModulePromise: Promise<FontkitModule> | null = null;

/** fontId → fontkit Font 实例（已 fetch + parse）。 */
const fontCache = new Map<string, Promise<FontkitFont | null>>();

/** polygon cache key = "fontId|fontSize|text|letterSpacing|lineHeight"。 */
const polygonCache = new Map<string, Polygon | null>();

/**
 * 0.4.10 bugfix：MultiPolygon 形态缓存（保留 holes + 多 polygon），用于 Live Paint 占用区
 * union 路径。结构以 (0, 0) 为原点；调用方加 element 偏移。
 */
const multiPolygonCache = new Map<string, PCMultiPolygon | null>();

/** polygon cache 大小上限——超时 LRU；text 内容大量变化时防爆。 */
const POLYGON_CACHE_MAX = 256;

/**
 * fetch 函数注入 seam——worker / 测试可替换。默认走全局 fetch（main + worker scope 都有）。
 */
type FetchFn = (url: string) => Promise<{ ok: boolean; arrayBuffer(): Promise<ArrayBuffer> }>;
let fetchImpl: FetchFn = (url) => fetch(url) as ReturnType<FetchFn>;

/** 测试用：注入 mock fetch / 重置缓存。 */
export function __setFetchForTest(fn: FetchFn | null): void {
    fetchImpl = fn ?? ((url) => fetch(url) as ReturnType<FetchFn>);
}

/** 测试用：注入 fontkit 模块 mock，绕过 dynamic import。 */
export function __setFontkitModuleForTest(mod: FontkitModule | null): void {
    if (mod === null) {
        fontkitModulePromise = null;
    } else {
        fontkitModulePromise = Promise.resolve(mod);
    }
}

/** 测试用：清空所有缓存。 */
export function __resetCachesForTest(): void {
    fontCache.clear();
    polygonCache.clear();
    multiPolygonCache.clear();
}

/** 加载 fontkit 模块（dynamic import；首次调用触发 chunk download）。 */
async function loadFontkit(): Promise<FontkitModule> {
    if (fontkitModulePromise) return fontkitModulePromise;
    fontkitModulePromise = (async (): Promise<FontkitModule> => {
        const mod = await import('fontkit');
        // fontkit 2.x 走 named export `create`；mod 自身即模块对象
        return mod as unknown as FontkitModule;
    })();
    return fontkitModulePromise;
}

/** fetch + parse 单个字体；缓存按 fontId。失败返 null。 */
function loadFont(fontId: string): Promise<FontkitFont | null> {
    const existing = fontCache.get(fontId);
    if (existing) return existing;
    const p = (async (): Promise<FontkitFont | null> => {
        try {
            const url = `/api/font/file?id=${encodeURIComponent(fontId)}`;
            const res = await fetchImpl(url);
            if (!res.ok) return null;
            const buf = await res.arrayBuffer();
            const fk = await loadFontkit();
            const font = fk.create(buf);
            return font ?? null;
        } catch (e) {
            if (typeof console !== 'undefined' && import.meta.env && import.meta.env.DEV) {
                console.warn(`[TextGlyphExtractor] loadFont ${fontId} failed:`, e);
            }
            return null;
        }
    })();
    fontCache.set(fontId, p);
    return p;
}

/**
 * 主入口：TextElement → polygon (单 ring)，以 element-local (el.x, el.y) 为原点偏移。
 *
 * 返回 null = 退化（未加载 / 空文本 / vertical / 字体不可用）→ 调用方 fallback bbox。
 *
 * **重要**：这是"取面积最大外环 + 丢 holes"的简化版本，仅适合点击命中检测等不需要
 * 精确 hole / 多 glyph 拓扑的场景。Live Paint 占用区计算请用
 * {@link textElementToMultiPolygon}（0.4.10 起新增）——它保留 holes（如 "O" 内孔）
 * 和所有独立 glyph polygon。
 */
export async function textElementToPolygon(textEl: TextElement): Promise<Polygon | null> {
    // 早退：vertical 模式 v1 不支持（layout 逻辑复杂）
    if (textEl.vertical) return null;
    // 空文本（或纯空白）→ 走 bbox 即可
    if (!textEl.text || textEl.text.trim().length === 0) return null;
    // fontSize ≤ 0 防御
    if (!Number.isFinite(textEl.fontSize) || textEl.fontSize <= 0) return null;

    const key = `${textEl.fontId}|${textEl.fontSize}|${textEl.text}|${textEl.letterSpacing ?? 0}|${textEl.lineHeight ?? 0}`;
    let cached: Polygon | null | undefined = polygonCache.get(key);
    if (cached === undefined) {
        // 走 multi 路径再降级为"面积最大外环"——避免与 textElementToMultiPolygon 重复
        // 跑昂贵的 fontkit layout + union。
        const multi = await getOrComputeMultiLayout(textEl);
        cached = multi === null ? null : pickLargestOuterRing(multi);
        // LRU 简单实现：超阈值清最早 entry
        if (polygonCache.size >= POLYGON_CACHE_MAX) {
            const firstKey = polygonCache.keys().next().value;
            if (firstKey !== undefined) polygonCache.delete(firstKey);
        }
        polygonCache.set(key, cached);
    }
    if (cached === null) return null;

    // cached 是以 (0,0) 为原点的 layout polygon；调用方需要全局坐标 → 加 (el.x, el.y)
    const out: Polygon = cached.map(([x, y]) => [x + textEl.x, y + textEl.y] as [number, number]);
    // 校验
    for (const [x, y] of out) {
        if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    }
    return out;
}

/**
 * 0.4.10 bugfix 新入口：TextElement → 完整 MultiPolygon（保留 holes + 多 polygon），
 * 以 element-local (el.x, el.y) 为原点偏移。
 *
 * 对比 {@link textElementToPolygon}：
 * - 旧函数只取面积最大外环——单字符 "O" 会丢内孔（用户点不出洞）；多字符 "Hello"
 *   会丢 4 个字符
 * - 本函数保留 polygon-clipping union 输出的完整结构 = 每 Polygon = [outer, ...holes]，
 *   多个独立 Polygon 表示字与字间不连通的形状
 *
 * 用途：Live Paint 占用区计算（LivePaintCore.buildGraph）。union 时多 polygon 直接
 * spread 即可（polygon-clipping union 支持 GeoJSON-style MultiPolygon 输入）。
 *
 * 返回 null = 退化（与 textElementToPolygon 同语义）。
 */
export async function textElementToMultiPolygon(textEl: TextElement): Promise<PCMultiPolygon | null> {
    if (textEl.vertical) return null;
    if (!textEl.text || textEl.text.trim().length === 0) return null;
    if (!Number.isFinite(textEl.fontSize) || textEl.fontSize <= 0) return null;

    const multi = await getOrComputeMultiLayout(textEl);
    if (multi === null) return null;

    // 平移到全局坐标（每 ring 每点 +offset）
    const ox = textEl.x;
    const oy = textEl.y;
    const out: PCMultiPolygon = [];
    for (const poly of multi) {
        const newPoly: PCPolygon = [];
        for (const ring of poly) {
            const newRing: PCRing = ring.map(([x, y]) => [x + ox, y + oy] as Pair);
            // 校验 NaN/Infinity
            let ringOk = true;
            for (const [x, y] of newRing) {
                if (!Number.isFinite(x) || !Number.isFinite(y)) { ringOk = false; break; }
            }
            if (ringOk) newPoly.push(newRing);
        }
        if (newPoly.length > 0) out.push(newPoly);
    }
    return out.length === 0 ? null : out;
}

/**
 * 内部：取或算 MultiPolygon layout cache（以 (0,0) 为原点）。失败 / 空 → null。
 * 与 polygonCache 共用同 key（fontId|size|text|letterSpacing|lineHeight）。
 */
async function getOrComputeMultiLayout(textEl: TextElement): Promise<PCMultiPolygon | null> {
    const key = `${textEl.fontId}|${textEl.fontSize}|${textEl.text}|${textEl.letterSpacing ?? 0}|${textEl.lineHeight ?? 0}`;
    if (multiPolygonCache.has(key)) {
        return multiPolygonCache.get(key) ?? null;
    }
    const result = await computeLayoutMultiPolygon(textEl);
    if (multiPolygonCache.size >= POLYGON_CACHE_MAX) {
        const firstKey = multiPolygonCache.keys().next().value;
        if (firstKey !== undefined) multiPolygonCache.delete(firstKey);
    }
    multiPolygonCache.set(key, result);
    return result;
}

/**
 * 从 MultiPolygon 中取面积最大的外环（丢 holes 与其他 polygon）。
 * 仅供 {@link textElementToPolygon} 的向后兼容路径用。
 */
function pickLargestOuterRing(multi: PCMultiPolygon): Polygon | null {
    let bestRing: PCRing | null = null;
    let bestArea = -1;
    for (const poly of multi) {
        if (poly.length === 0) continue;
        const ring = poly[0];
        const polyOut = fromPCRing(ring);
        if (polyOut.length < 3) continue;
        const area = polygonArea(polyOut);
        if (area > bestArea) {
            bestArea = area;
            bestRing = ring;
        }
    }
    if (bestRing === null) return null;
    const out = fromPCRing(bestRing);
    if (out.length < 3) return null;
    for (const [x, y] of out) {
        if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    }
    return out;
}

/**
 * 真正干活：fontkit layout → glyph paths → polygon-clipping union → 完整 MultiPolygon。
 * 输出以 (0,0) 为原点（baseline 起点）；caller 加 element 偏移。
 *
 * 0.4.10 bugfix：原 computeLayoutPolygon 在 union 后取面积最大外环，丢失了 "O" / "你"
 * 等含内孔字符的 hole 以及多字符场景的非最大 polygon。改为返完整 MultiPolygon，调用方
 * 决定要降级为单环还是直接用 union。
 */
async function computeLayoutMultiPolygon(textEl: TextElement): Promise<PCMultiPolygon | null> {
    const font = await loadFont(textEl.fontId);
    if (!font) return null;
    const unitsPerEm = font.unitsPerEm || 1000;
    const fontSize = textEl.fontSize;
    /** font design units → pixel 缩放 */
    const scale = fontSize / unitsPerEm;
    const lineHeightMul = textEl.lineHeight && textEl.lineHeight > 0 ? textEl.lineHeight : DEFAULT_LINE_HEIGHT_MUL;
    const lineHeightPx = fontSize * lineHeightMul;
    // letterSpacing 为额外像素间距（与 TextLayout.ts 一致）
    const letterSpacing = textEl.letterSpacing ?? 0;

    // 按 \n 拆行；每行独立 layout（v1：不做 box-width soft wrap）
    const lines = textEl.text.split(/\r?\n/);

    const subPolygons: PCPolygon[] = [];

    for (let li = 0; li < lines.length; li++) {
        const line = lines[li];
        if (line.length === 0) continue;

        let run: FontkitGlyphRun;
        try {
            run = font.layout(line);
        } catch (e) {
            if (typeof console !== 'undefined' && import.meta.env && import.meta.env.DEV) {
                console.warn('[TextGlyphExtractor] layout failed:', e);
            }
            continue;
        }

        // 基线 Y：第 li 行；baseline 大约在 font ascender 处
        // 简化：取 fontSize × 0.8 作为 baseline 距 top 距离（与 TextLayout.ts drawText 对齐）
        // TextLayout.ts 不指定 baseline，但 Canvas2D textBaseline='alphabetic' 默认下，
        // text 顶部 ≈ baseline - fontSize × 0.8。这里反算 baselineY = lineTopY + fontSize × 0.8。
        const baselineY = li * lineHeightPx + fontSize * 0.8;

        let cursorX = 0;
        const glyphCount = Math.min(run.glyphs.length, run.positions.length);
        for (let gi = 0; gi < glyphCount; gi++) {
            const glyph = run.glyphs[gi];
            const pos = run.positions[gi];
            const advancePx = pos.xAdvance * scale;
            const xOffsetPx = pos.xOffset * scale;
            const yOffsetPx = pos.yOffset * scale;

            const glyphPolygons = glyphToPolygons(glyph, scale, cursorX + xOffsetPx, baselineY - yOffsetPx);
            // 0.4.10 bugfix：把一个 glyph 的所有 subpath 作为**同一 PCPolygon 内的多 ring**
            // 推入 subPolygons。polygon-clipping 只有同 polygon 内的多 ring 才被识别为
            // "外环 + holes"（独立 polygon union 时 inner 总被合并掉）—— 这是 "O"
            // 内孔保留的关键。OpenType glyph 语义本就是"复合形状 = 1 个 polygon 含
            // 外环 + 多 holes"，与 polygon-clipping 输入约定天然吻合。
            //
            // 不同 glyph 之间走独立 PCPolygon，让多字符 union 后保持独立 polygon（不连通），
            // 避免被误合并成单一大外环。
            const glyphRings: PCRing[] = [];
            for (const poly of glyphPolygons) {
                if (poly.length < 3) continue;
                glyphRings.push(closeRing(toPCRing(poly)));
            }
            if (glyphRings.length > 0) {
                subPolygons.push(glyphRings as PCPolygon);
            }

            cursorX += advancePx;
            if (gi < glyphCount - 1) cursorX += letterSpacing;
        }
    }

    if (subPolygons.length === 0) return null;

    let merged: ReturnType<typeof polygonClipping.union>;
    try {
        merged = polygonClipping.union(subPolygons[0], ...subPolygons.slice(1));
    } catch (e) {
        if (typeof console !== 'undefined' && import.meta.env && import.meta.env.DEV) {
            console.warn('[TextGlyphExtractor] union failed:', e);
        }
        return null;
    }
    if (!merged || merged.length === 0) return null;

    // 0.4.10 bugfix：返完整 MultiPolygon = Polygon[] = Ring[][]
    //   - 每 Polygon = [outerRing, hole1, hole2, ...]
    //   - 单字符 "O" → 1 Polygon = [外环, 内孔]
    //   - 多字符 "Hello" → 5 Polygon（每字符 1 个），各自可能含 hole
    //   - CJK "你好" → 多 Polygon + 多 holes
    //
    // 旧实现取面积最大外环导致：(a) "O" 的内孔被丢，Live Paint 无法识别洞为 gap；
    // (b) "Hello" 4 个字符 polygon 被丢，只有面积最大那个字符参与占用计算。
    // 现在直接返 union 输出，由调用方决定是降级为单环还是直接喂给上层 union。
    //
    // 校验：去掉空 ring / NaN ring；ring 长度 < 3 不参与。
    const out: PCMultiPolygon = [];
    for (const poly of merged) {
        if (poly.length === 0) continue;
        const cleanPoly: PCPolygon = [];
        for (const ring of poly) {
            if (ring.length < 3) continue;
            let ok = true;
            for (const [x, y] of ring) {
                if (!Number.isFinite(x) || !Number.isFinite(y)) { ok = false; break; }
            }
            if (ok) cleanPoly.push(ring);
        }
        if (cleanPoly.length > 0) out.push(cleanPoly);
    }
    return out.length === 0 ? null : out;
}

/**
 * fontkit glyph → 0..N 个 polygon（一个 glyph 可能含多 subpath，如 "O" / "B"）。
 *
 * 坐标系转换：
 *   - fontkit glyph path 走 OpenType 习惯：Y 轴向上（baseline 处 y=0，ascender y > 0）
 *   - 屏幕坐标 Y 向下
 *   - 这里把 glyph 局部坐标 (gx, gy) → 屏幕 (originX + gx × scale, originY - gy × scale)
 *     originY 是 baseline 屏幕 y
 */
function glyphToPolygons(glyph: FontkitGlyph, scale: number, originX: number, baselineY: number): Polygon[] {
    let d: string;
    try {
        d = glyph.path.toSVG();
    } catch {
        return [];
    }
    if (!d) return [];
    return parseSvgPathToPolygons(d, scale, originX, baselineY);
}

/**
 * 解析 SVG path d 字符串（M / L / Q / C / Z 子集）→ 0..N 个 polygon（每 subpath 一个）。
 * 坐标变换：(x, y) → (originX + x × scale, baselineY - y × scale)（Y 翻转）。
 *
 * 与 ElementToPolygon.pathPolygon 的差异：
 *   - 该函数只取第一个 subpath；本函数返所有 subpath
 *   - 该函数 sample 用 PATH_CURVE_SAMPLES=12；本函数用 GLYPH_CURVE_SAMPLES=8（glyph
 *     多曲线 union 需要更轻）
 *   - 应用 Y 翻转
 */
function parseSvgPathToPolygons(d: string, scale: number, originX: number, baselineY: number): Polygon[] {
    const polygons: Polygon[] = [];
    let current: Polygon = [];

    let curX = 0, curY = 0;
    let subStartX = 0, subStartY = 0;

    const pushTransformed = (x: number, y: number): void => {
        const tx = originX + x * scale;
        const ty = baselineY - y * scale;
        const last = current[current.length - 1];
        if (last && Math.abs(last[0] - tx) < 1e-6 && Math.abs(last[1] - ty) < 1e-6) return;
        current.push([tx, ty]);
    };

    const flushSubpath = (): void => {
        if (current.length >= 3) polygons.push(current);
        current = [];
    };

    let curCmd = '';
    let paramsPerCmd = 0;
    const paramBuf: number[] = [];
    let firstGroupForCmd = true;

    let i = 0;
    const n = d.length;
    while (i < n) {
        const c = d[i];
        if (isSep(c)) { i++; continue; }
        if (isCmd(c)) {
            curCmd = c;
            paramsPerCmd = paramsForCommand(c);
            paramBuf.length = 0;
            firstGroupForCmd = true;
            i++;
            if (paramsPerCmd === 0) {
                // Z / z：闭合并 flush 当前 subpath
                flushSubpath();
                curX = subStartX;
                curY = subStartY;
                curCmd = '';
            }
            continue;
        }
        const scanned = scanNumber(d, i);
        if (scanned.endIdx === i) break;
        i = scanned.endIdx;
        if (!curCmd) continue;
        paramBuf.push(scanned.value);
        if (paramBuf.length < paramsPerCmd) continue;

        const relative = curCmd.toLowerCase() === curCmd;
        const op = curCmd.toUpperCase();
        switch (op) {
            case 'M': {
                const x = relative ? curX + paramBuf[0] : paramBuf[0];
                const y = relative ? curY + paramBuf[1] : paramBuf[1];
                if (firstGroupForCmd) {
                    // 新 subpath：先 flush 上一个
                    flushSubpath();
                    pushTransformed(x, y);
                    subStartX = x; subStartY = y;
                    firstGroupForCmd = false;
                } else {
                    pushTransformed(x, y);
                }
                curX = x; curY = y;
                break;
            }
            case 'L': {
                const x = relative ? curX + paramBuf[0] : paramBuf[0];
                const y = relative ? curY + paramBuf[1] : paramBuf[1];
                pushTransformed(x, y);
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
            case 'Q': {
                const cx = relative ? curX + paramBuf[0] : paramBuf[0];
                const cy = relative ? curY + paramBuf[1] : paramBuf[1];
                const x = relative ? curX + paramBuf[2] : paramBuf[2];
                const y = relative ? curY + paramBuf[3] : paramBuf[3];
                sampleQuadratic(curX, curY, cx, cy, x, y, pushTransformed);
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
            case 'C': {
                const c1x = relative ? curX + paramBuf[0] : paramBuf[0];
                const c1y = relative ? curY + paramBuf[1] : paramBuf[1];
                const c2x = relative ? curX + paramBuf[2] : paramBuf[2];
                const c2y = relative ? curY + paramBuf[3] : paramBuf[3];
                const x = relative ? curX + paramBuf[4] : paramBuf[4];
                const y = relative ? curY + paramBuf[5] : paramBuf[5];
                sampleCubic(curX, curY, c1x, c1y, c2x, c2y, x, y, pushTransformed);
                curX = x; curY = y;
                firstGroupForCmd = false;
                break;
            }
        }
        paramBuf.length = 0;
    }
    // 末尾未 Z 的 subpath 也 flush
    flushSubpath();
    return polygons;
}

function sampleQuadratic(
    x0: number, y0: number,
    x1: number, y1: number,
    x2: number, y2: number,
    push: (x: number, y: number) => void,
): void {
    for (let i = 1; i <= GLYPH_CURVE_SAMPLES; i++) {
        const t = i / GLYPH_CURVE_SAMPLES;
        const mt = 1 - t;
        const x = mt * mt * x0 + 2 * mt * t * x1 + t * t * x2;
        const y = mt * mt * y0 + 2 * mt * t * y1 + t * t * y2;
        push(x, y);
    }
}

function sampleCubic(
    x0: number, y0: number,
    x1: number, y1: number,
    x2: number, y2: number,
    x3: number, y3: number,
    push: (x: number, y: number) => void,
): void {
    for (let i = 1; i <= GLYPH_CURVE_SAMPLES; i++) {
        const t = i / GLYPH_CURVE_SAMPLES;
        const mt = 1 - t;
        const mt2 = mt * mt;
        const t2 = t * t;
        const x = mt2 * mt * x0 + 3 * mt2 * t * x1 + 3 * mt * t2 * x2 + t2 * t * x3;
        const y = mt2 * mt * y0 + 3 * mt2 * t * y1 + 3 * mt * t2 * y2 + t2 * t * y3;
        push(x, y);
    }
}

function paramsForCommand(c: string): number {
    switch (c.toLowerCase()) {
        case 'm':
        case 'l': return 2;
        case 'q': return 4;
        case 'c': return 6;
        case 'z': return 0;
        default: return -1;
    }
}

function isCmd(c: string): boolean {
    const lc = c.toLowerCase();
    return lc === 'm' || lc === 'l' || lc === 'q' || lc === 'c' || lc === 'z';
}

function isSep(c: string): boolean {
    return c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',';
}

function scanNumber(s: string, i: number): { value: number; endIdx: number } {
    const n = s.length;
    let j = i;
    if (j < n && (s[j] === '+' || s[j] === '-')) j++;
    let hasDigit = false;
    while (j < n && isDigit(s[j])) { j++; hasDigit = true; }
    if (j < n && s[j] === '.') {
        j++;
        while (j < n && isDigit(s[j])) { j++; hasDigit = true; }
    }
    if (!hasDigit) return { value: 0, endIdx: i };
    if (j < n && (s[j] === 'e' || s[j] === 'E')) {
        const eIdx = j;
        j++;
        if (j < n && (s[j] === '+' || s[j] === '-')) j++;
        let hasExpDigit = false;
        while (j < n && isDigit(s[j])) { j++; hasExpDigit = true; }
        if (!hasExpDigit) {
            const v = parseFloat(s.substring(i, eIdx));
            return { value: Number.isFinite(v) ? v : 0, endIdx: eIdx };
        }
    }
    const v = parseFloat(s.substring(i, j));
    return { value: Number.isFinite(v) ? v : 0, endIdx: Number.isFinite(v) ? j : i };
}

function isDigit(c: string): boolean {
    return c >= '0' && c <= '9';
}

// ---------- polygon-clipping bridge helpers ----------

function toPCRing(poly: Polygon): PCRing {
    return poly.map(([x, y]) => [x, y] as Pair);
}

function fromPCRing(ring: PCRing): Polygon {
    if (ring.length === 0) return [];
    const last = ring.length - 1;
    if (ring.length >= 2) {
        const a = ring[0];
        const b = ring[last];
        if (Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9) {
            return ring.slice(0, last).map(p => [p[0], p[1]] as [number, number]);
        }
    }
    return ring.map(p => [p[0], p[1]] as [number, number]);
}

function closeRing(ring: PCRing): PCRing {
    if (ring.length === 0) return ring;
    const first = ring[0];
    const last = ring[ring.length - 1];
    if (first[0] === last[0] && first[1] === last[1]) return ring;
    return [...ring, [first[0], first[1]] as Pair];
}

function polygonArea(poly: Polygon): number {
    const n = poly.length;
    if (n < 3) return 0;
    let area = 0;
    for (let i = 0; i < n; i++) {
        const [x1, y1] = poly[i];
        const [x2, y2] = poly[(i + 1) % n];
        area += x1 * y2 - x2 * y1;
    }
    return Math.abs(area) / 2;
}
