/**
 * Text glyph 真实形状提取（Live Paint）。
 *
 * 输入 TextElement，输出每个字符 glyph 的真实 path union 后的单 ring polygon。
 * 升级前：ElementToPolygon 对 text 走 bbox 兜底——Live Paint 把字与字之间空隙也当占用，
 * 点字间空白会误判为 element 内部。
 * 升级后：fontkit 解析字体 → layout glyphs → 每个 glyph SVG path → 采样多边形 →
 * polygon-clipping union → 单外环 polygon。
 *
 * 实现约束：
 *   1. **dynamic import fontkit**：避免 ~150 kB 进主 bundle；Live Paint + text 触发时才加载。
 *   2. **字体二进制缓存**：fontkit Font 实例按 fontId 内存常驻；同一 fontId 不重复 fetch。
 *   3. **polygon 结果缓存**：按 `fontId|size|text|letterSpacing|lineHeight` 缓存 polygon。
 *      用户拖动 text 元素时坐标变了但 cache key 一致——只需平移即可，但 cache 命中后
 *      调用方拿到的是 element-local 偏移 + 已 baked global 坐标的 polygon，所以这里
 *      cache 存"以 (0,0) 为原点的 layout polygon"，调用方加偏移。
 *   4. **v1 限制**：仅单行 / 多行（按 `\n` 拆）水平 layout；不支持 wrap by box-width、
 *      不支持 vertical 模式、不支持 letterSpacing 完整复刻 TextLayout.ts。无法处理时
 *      返回 null 让上层 fallback bbox。
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
import { layoutText } from '@/render/TextLayout';
import type { Polygon } from './types';

/** glyph path bezier 采样段数（与 ElementToPolygon PATH_CURVE_SAMPLES 一致）。 */
export const GLYPH_CURVE_SAMPLES = 8;

/** italic shear 系数，与 PreviewRenderer drawText `ctx.transform(1, 0, -0.2, 1, ...)` 一致。 */
const ITALIC_SHEAR = -0.2;

// ---------- fontkit 最小类型声明 ----------
// fontkit 不提供 d.ts；仅声明本模块用到的部分接口。

interface FontkitGlyph {
    /** 已解析的 path 对象 */
    path: FontkitPath;
    /** 设计单元下的 advanceWidth（运行时不再使用——保留兼容声明） */
    advanceWidth?: number;
}

interface FontkitPath {
    /** SVG path d 字符串。fontkit 文档：path.toSVG() 返 d string */
    toSVG(): string;
    /** 直接得到 commands 数组（fontkit Path API） */
    commands?: Array<{ command: string; args: number[] }>;
}

/**
 * 保留 layout 类型仅为 mock 兼容性——本模块运行时不再调用
 * font.layout()（advance 与 PreviewRenderer 不同源 bug 根因），改用 layoutText() 同源摆位。
 */
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
    /** 仅 mock 测试时可能被调用；运行路径走 glyphForCodePoint。 */
    layout?(text: string, features?: string[]): FontkitGlyphRun;
}

interface FontkitModule {
    /**
     * 只能传 {@code Uint8Array}（或别的 TypedArray）。fontkit 内部把它交给 restructure 的
     * DecodeStream，那里直接 {@code new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)}
     * ——裸 ArrayBuffer 没有 {@code .buffer} 属性，会抛 TypeError。签名故意不写
     * {@code ArrayBuffer}，免得又有人把 {@code res.arrayBuffer()} 的结果直接递进来。
     */
    create(buffer: Uint8Array): FontkitFont;
}

// ---------- fontkit 模块 / 字体 cache ----------

let fontkitModulePromise: Promise<FontkitModule> | null = null;

/** fontId → fontkit Font 实例（已 fetch + parse）。 */
const fontCache = new Map<string, Promise<FontkitFont | null>>();

/** polygon cache key = "fontId|fontSize|text|letterSpacing|lineHeight"。 */
const polygonCache = new Map<string, Polygon | null>();

/**
 * MultiPolygon 形态缓存（保留 holes + 多 polygon），用于 Live Paint 占用区
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

/**
 * fetch + parse 单个字体；缓存按 fontId。失败返 null。
 *
 * <p><b>失败不留在缓存里</b>：一次网络抖动、或后端字体还没注册完的那个窗口期返 404，
 * 不该把这个字体钉死到 worker 重建为止（下次 Live Paint 只能拿 bbox 兜底）。
 * 拿到 null 就把条目摘掉，下次点击重试。</p>
 */
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
            // 必须包成 Uint8Array：fontkit 走 restructure 的 DecodeStream，那里拿
            // buffer.buffer / byteOffset 建 DataView，裸 ArrayBuffer 会抛
            // "First argument to DataView constructor must be an ArrayBuffer"，
            // 被下面的 catch 吞成 null —— 结果是所有文字的 Live Paint 都静默退化成 bbox。
            const font = fk.create(new Uint8Array(buf));
            return font ?? null;
        } catch (e) {
            if (typeof console !== 'undefined' && import.meta.env && import.meta.env.DEV) {
                console.warn(`[TextGlyphExtractor] loadFont ${fontId} failed:`, e);
            }
            return null;
        }
    })();
    fontCache.set(fontId, p);
    void p.then((font) => {
        // 只在这条 promise 仍是当前缓存时摘，别误删后来重新发起的那次。
        if (font === null && fontCache.get(fontId) === p) fontCache.delete(fontId);
    });
    return p;
}

/**
 * 主入口：TextElement → polygon (单 ring)，以 element-local (el.x, el.y) 为原点偏移。
 *
 * 返回 null = 退化（未加载 / 空文本 / vertical / 字体不可用）→ 调用方 fallback bbox。
 *
 * **重要**：这是"取面积最大外环 + 丢 holes"的简化版本，仅适合点击命中检测等不需要
 * 精确 hole / 多 glyph 拓扑的场景。Live Paint 占用区计算请用
 * {@link textElementToMultiPolygon}——它保留 holes（如 "O" 内孔）
 * 和所有独立 glyph polygon。
 */
export async function textElementToPolygon(textEl: TextElement): Promise<Polygon | null> {
    // 早退：vertical 模式 v1 不支持（layout 逻辑复杂）
    if (textEl.vertical) return null;
    // 空文本（或纯空白）→ 走 bbox 即可
    if (!textEl.text || textEl.text.trim().length === 0) return null;
    // fontSize ≤ 0 防御
    if (!Number.isFinite(textEl.fontSize) || textEl.fontSize <= 0) return null;

    const key = buildLayoutCacheKey(textEl);
    let cached: Polygon | null | undefined = polygonCache.get(key);
    if (cached === undefined) {
        // 走 multi 路径再降级为"面积最大外环"——避免与 textElementToMultiPolygon 重复
        // 跑昂贵的 fontkit layout + union。
        const { multi, cacheable } = await getOrComputeMultiLayout(textEl);
        // 字体这次没拿到时不写缓存：那是"暂时取不到字体"，不是"这段文字没形状"。
        if (!cacheable) return null;
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
 * TextElement → 完整 MultiPolygon（保留 holes + 多 polygon），
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

    const { multi } = await getOrComputeMultiLayout(textEl);
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

/** {@link getOrComputeMultiLayout} 的返回：结果 + 这个结果值不值得进缓存。 */
interface LayoutLookup {
    /** 算出来的 MultiPolygon；null = 这段文字没有可用形状（或字体没取到）。 */
    multi: PCMultiPolygon | null;
    /**
     * false = 这次是因为字体没取到才 null，别缓存。
     * 缓存了会把退化状态钉死在这条 cache key 上：字体后来恢复了，这段文字也永远走 bbox 兜底。
     */
    cacheable: boolean;
}

/**
 * 内部：取或算 MultiPolygon layout cache（以 (0,0) 为原点）。失败 / 空 → null。
 * 与 polygonCache 共用同 key（fontId|size|text|letterSpacing|lineHeight）。
 */
async function getOrComputeMultiLayout(textEl: TextElement): Promise<LayoutLookup> {
    const key = buildLayoutCacheKey(textEl);
    if (multiPolygonCache.has(key)) {
        return { multi: multiPolygonCache.get(key) ?? null, cacheable: true };
    }
    const { multi, fontMissing } = await computeLayoutMultiPolygon(textEl);
    if (fontMissing) return { multi: null, cacheable: false };
    if (multiPolygonCache.size >= POLYGON_CACHE_MAX) {
        const firstKey = multiPolygonCache.keys().next().value;
        if (firstKey !== undefined) multiPolygonCache.delete(firstKey);
    }
    multiPolygonCache.set(key, multi);
    return { multi, cacheable: true };
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
 * 真正干活：layoutText 同源摆位 → fontkit glyph paths → polygon-clipping union → 完整 MultiPolygon。
 * 输出以 (0,0) 为原点（element-local 坐标，相当于 textEl.x = textEl.y = 0）；caller 加 element 偏移。
 *
 * 原 computeLayoutPolygon 在 union 后取面积最大外环，丢失了 "O" / "你"
 * 等含内孔字符的 hole 以及多字符场景的非最大 polygon。改为返完整 MultiPolygon。
 *
 * **advance 同源**：早先实现走 `font.layout(line)` 拿 GlyphRun，用 fontkit
 * `pos.xAdvance × scale`（hmtx 表真实 advance，对 fontSize=32 Inter "HELLO" ≈ 18 px/char）
 * 摆位 glyph；但 PreviewRenderer 视觉摆位走 `TextLayout.layoutText` + `charAdvance`
 * （ASCII canonical = 16 px/char，CJK = fontSize，metrics 表存在时 round(base × size / baseSize)）
 * —— **双源 advance 不一致**。5 字符累积偏差 ~10 px > "O" 内孔半径 ~6 px，用户点击视觉
 * O 中央时坐标落在 polygon hole 外，Live Paint 无法识别洞。
 *
 * 修复：layoutText() 同源摆位 + 只用 fontkit `glyphForCodePoint(cp).path` 拿 SVG d；
 * align / softWrap / letterSpacing / lineHeight / vertical fallback 全自动正确（layoutText
 * 已处理）。bold/italic 由调用方知道 textEl.bold/italic，shear 在 polygon 顶点上做仿射
 * （与 PreviewRenderer `ctx.transform(1, 0, -0.2, 1, ...)` 数学等价）。
 *
 * bold (effects.stroke / textEl.bold) 让视觉 outline 比 path 大几 px，v1 接受不完美——
 * polygon 是 glyph path 本身的 outline，bold 几 px 误差小于 hole 半径 6 px，可接受。
 */
async function computeLayoutMultiPolygon(
    textEl: TextElement,
): Promise<{ multi: PCMultiPolygon | null; fontMissing: boolean }> {
    const font = await loadFont(textEl.fontId);
    // 字体没取到要单独告诉调用方：它跟"这段文字算出来就是空的"不一样，不能缓存。
    if (!font) return { multi: null, fontMissing: true };
    const unitsPerEm = font.unitsPerEm || 1000;
    const fontSize = textEl.fontSize;
    /** font design units → pixel 缩放 */
    const scale = fontSize / unitsPerEm;

    // 用 layoutText 同源摆位，input 把 element 原点平移到
    // (0, 0)，这样 PositionedGlyph.x / baselineY 直接是 element-local 坐标。其他字段
    // （fontId / fontSize / align / letterSpacing / lineHeight / w / h / text）原样透传，
    // 让 layoutText 的 softWrap + align + lineHeight + letterSpacing 行为与 PreviewRenderer 一致。
    //
    // vertical 模式在 textElementToPolygon / textElementToMultiPolygon 入口已早退，
    // 这里再做一次防御保险。
    if (textEl.vertical) return { multi: null, fontMissing: false };
    const layoutInput: TextElement = { ...textEl, x: 0, y: 0 };
    const positioned = layoutText(layoutInput);
    if (positioned.length === 0) return { multi: null, fontMissing: false };

    const italic = textEl.italic === true;

    const subPolygons: PCPolygon[] = [];

    for (const g of positioned) {
        // 取 BMP / Astral 通用 codepoint。layoutText 已按 char 拆，emoji surrogate pair
        // 在 layoutText 内按单 char 处理（与 PreviewRenderer 一致），这里同样按 charCodeAt(0)
        // 即可——若是 high surrogate 拿不到 glyph，fontkit 返 .notdef glyph，path 多半为空，
        // glyphToPolygons 跳过。
        const cp = g.ch.codePointAt(0);
        if (cp === undefined) continue;

        let glyph: FontkitGlyph;
        try {
            glyph = font.glyphForCodePoint(cp);
        } catch (e) {
            if (typeof console !== 'undefined' && import.meta.env && import.meta.env.DEV) {
                console.warn('[TextGlyphExtractor] glyphForCodePoint failed:', e);
            }
            continue;
        }
        if (!glyph || !glyph.path) continue;

        // glyph 摆位：originX = g.x（layoutText 横坐标，左上 = 该 glyph 绘制起点），
        // baselineY = g.baselineY（layoutText 已加 ascent）。parseSvgPathToPolygons 内
        // 把 fontkit Y-up 翻成屏幕 Y-down 并做 scale。
        const glyphPolygons = glyphToPolygons(glyph, scale, g.x, g.baselineY);

        // 一个 glyph 的多条轮廓要按"套没套着"分组。
        //
        // polygon-clipping 按 GeoJSON 语义理解一个 polygon：ring[0] 是外环、其余全是内孔，
        // 而内孔必须落在外环里面。"O" 这种确实是套着的，但 "三" / "i" / "%" / 大量汉字的
        // 几条轮廓是并排的实心笔画——全塞进同一个 polygon 的话，除第一条以外全被当成
        // 非法内孔丢掉（实测：三条并排横杠 union 完只剩一条）。结果是后面几笔的位置被
        // 判成空白，油漆桶直接盖在字上。
        //
        // 所以：套着的（外环 + 内孔）留在同一个 polygon 里，并排的各自独立成 polygon。
        // 不同 glyph 之间本来就走独立 polygon，多字符 union 后保持不连通。
        const contours: Polygon[] = [];
        for (const poly of glyphPolygons) {
            if (poly.length < 3) continue;
            // italic 应用 horizontal shear。PreviewRenderer
            // 走 ctx.translate(t.x, t.y) → transform(1, 0, -0.2, 1, ...) → translate(-t.x, -t.y)，
            // 复合矩阵作用为 worldX' = worldX - 0.2*(worldY - t.y)；化到 element-local
            // 坐标（worldX = localX + t.x, worldY = localY + t.y）得到
            // localX' = localX - 0.2 * localY，与 t.x/t.y 无关。
            contours.push(italic ? applyItalicShear(poly) : poly);
        }
        for (const group of groupContoursByNesting(contours)) {
            subPolygons.push(group.map(r => closeRing(toPCRing(r))) as PCPolygon);
        }
    }

    if (subPolygons.length === 0) return { multi: null, fontMissing: false };

    let merged: ReturnType<typeof polygonClipping.union>;
    try {
        merged = polygonClipping.union(subPolygons[0], ...subPolygons.slice(1));
    } catch (e) {
        if (typeof console !== 'undefined' && import.meta.env && import.meta.env.DEV) {
            console.warn('[TextGlyphExtractor] union failed:', e);
        }
        return { multi: null, fontMissing: false };
    }
    if (!merged || merged.length === 0) return { multi: null, fontMissing: false };

    // 返完整 MultiPolygon = Polygon[] = Ring[][]
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
    return { multi: out.length === 0 ? null : out, fontMissing: false };
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

/**
 * 构建 layout cache key，覆盖所有影响 layoutText 输出 +
 * italic 形状的字段。w/h 影响 softWrap + align；align 影响行内偏移；bold 不影响 polygon
 * 形状（path 本身不变，bold 仅 stroke 包装视觉效果）所以不入 key——可显著增加 cache
 * 命中率。
 */
function buildLayoutCacheKey(t: TextElement): string {
    return [
        t.fontId,
        t.fontSize,
        t.text,
        t.letterSpacing ?? 0,
        t.lineHeight ?? 0,
        t.align ?? 'left',
        t.w ?? 0,
        t.h ?? 0,
        t.italic === true ? '1' : '0',
    ].join('|');
}

/**
 * italic horizontal shear。在 element-local 坐标系下做。
 * 与 PreviewRenderer `ctx.transform(1, 0, -0.2, 1, ...)` 数学等价（参见
 * computeLayoutMultiPolygon 注释）。
 */
function applyItalicShear(poly: Polygon): Polygon {
    const out: Polygon = new Array(poly.length);
    for (let i = 0; i < poly.length; i++) {
        const [x, y] = poly[i];
        out[i] = [x + ITALIC_SHEAR * y, y];
    }
    return out;
}

/**
 * 点在多边形内判定（射线法）。字形的各条轮廓互不相交，所以拿某条轮廓的任意一个顶点
 * 去测另一条，结果就代表整条轮廓的包含关系。
 */
function pointInPolygon(px: number, py: number, poly: Polygon): boolean {
    let inside = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
        const [xi, yi] = poly[i];
        const [xj, yj] = poly[j];
        if ((yi > py) !== (yj > py)
            && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) {
            inside = !inside;
        }
    }
    return inside;
}

/**
 * 把一个 glyph 的若干条轮廓按嵌套层数分组：偶数层是外环（各自开一个 polygon），
 * 奇数层是它最近那层外环的内孔。
 *
 * <p>"O" → 1 组 [外环, 内孔]；"三" → 3 组各 1 条；"8" 里孔中还有实心的话也能正确成组。
 * 轮廓数量以个位数计，O(n²) 足够。</p>
 */
export function groupContoursByNesting(contours: Polygon[]): Polygon[][] {
    if (contours.length <= 1) return contours.map(c => [c]);
    // 面积从大到小处理，保证外层先于内层
    const order = contours.map((_, i) => i)
        .sort((a, b) => polygonArea(contours[b]) - polygonArea(contours[a]));
    const parent = new Array<number>(contours.length).fill(-1);
    const depth = new Array<number>(contours.length).fill(0);
    for (let oi = 0; oi < order.length; oi++) {
        const i = order[oi];
        const [px, py] = contours[i][0];
        let best = -1;
        let bestArea = Infinity;
        for (let oj = 0; oj < oi; oj++) {
            const j = order[oj];
            if (!pointInPolygon(px, py, contours[j])) continue;
            const a = polygonArea(contours[j]);
            if (a < bestArea) { bestArea = a; best = j; }   // 取最贴身的那一层
        }
        parent[i] = best;
        depth[i] = best < 0 ? 0 : depth[best] + 1;
    }
    const polyIndexOf = new Array<number>(contours.length).fill(-1);
    const out: Polygon[][] = [];
    for (const i of order) {
        const p = parent[i];
        if (depth[i] % 2 === 1 && p >= 0 && polyIndexOf[p] >= 0) {
            out[polyIndexOf[p]].push(contours[i]);   // 内孔挂到外环那一组
        } else {
            polyIndexOf[i] = out.length;
            out.push([contours[i]]);
        }
    }
    return out;
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
