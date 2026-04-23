import type { ProjectState, Element, RectElement, TextElement, Glow } from '@/types/protocol';
import { layoutText, CanvasMeasurer, type PositionedGlyph } from './TextLayout';

/**
 * 前端 Canvas 2D 预览渲染器。镜像 Java {@code CanvasCompositor}。
 * 契约 docs/rendering.md §4 / §5（效果渲染顺序：glow → shadow → stroke → fill）。
 */
export function renderProjectState(ctx: CanvasRenderingContext2D, state: ProjectState | null): void {
    const widthPx = (state?.canvas.widthMaps ?? 1) * 128;
    const heightPx = (state?.canvas.heightMaps ?? 1) * 128;

    ctx.fillStyle = state?.canvas.background ?? '#FFFFFF';
    ctx.fillRect(0, 0, widthPx, heightPx);

    if (!state) return;

    ctx.imageSmoothingEnabled = false;
    // @ts-expect-error non-standard but supported in Chromium
    ctx.textRendering = 'geometricPrecision';

    for (const e of state.elements) {
        if (!e.visible) continue;
        drawElement(ctx, e);
    }
}

function drawElement(ctx: CanvasRenderingContext2D, e: Element): void {
    ctx.save();
    if (e.rotation !== 0) {
        const cx = e.x + e.w / 2;
        const cy = e.y + e.h / 2;
        ctx.translate(cx, cy);
        ctx.rotate((e.rotation * Math.PI) / 180);
        ctx.translate(-cx, -cy);
    }
    if (e.type === 'rect') drawRect(ctx, e);
    else if (e.type === 'text') drawText(ctx, e);
    ctx.restore();
}

function drawRect(ctx: CanvasRenderingContext2D, r: RectElement): void {
    if (r.fill) {
        ctx.fillStyle = r.fill;
        ctx.fillRect(r.x, r.y, r.w, r.h);
    }
    if (r.stroke && r.stroke.width > 0) {
        const sw = Math.min(r.stroke.width, Math.max(1, Math.min(r.w, r.h) / 2));
        ctx.fillStyle = r.stroke.color;
        ctx.fillRect(r.x, r.y, r.w, sw);
        ctx.fillRect(r.x, r.y + r.h - sw, r.w, sw);
        ctx.fillRect(r.x, r.y, sw, r.h);
        ctx.fillRect(r.x + r.w - sw, r.y, sw, r.h);
    }
}

// ---------- 字体元数据（与后端 FontRegistry.Metadata 镜像，判断 pixelated + nativeSize） ----------

interface FontMeta { pixelated: boolean; nativeSize: number; }
const FONT_META: Record<string, FontMeta> = {
    ark_pixel: { pixelated: true, nativeSize: 12 },
    source_han_sans: { pixelated: false, nativeSize: 0 },
};

function shouldUseNearestNeighbor(family: string, targetSize: number): boolean {
    const meta = FONT_META[family];
    if (!meta?.pixelated || meta.nativeSize <= 0) return false;
    const scale = Math.floor(targetSize / meta.nativeSize);
    return scale >= 2 && scale * meta.nativeSize === targetSize;
}

// ---------- Text：4 层 glow → shadow → stroke → fill ----------

function drawText(ctx: CanvasRenderingContext2D, t: TextElement): void {
    if (!t.text) return;
    const family = fontFamily(t.fontId);
    const fontSpec = `${t.fontSize}px "${family}"`;
    ctx.font = fontSpec;
    ctx.textBaseline = 'alphabetic';
    ctx.textAlign = 'left';

    const measurer = new CanvasMeasurer(ctx, fontSpec);
    const glyphs = layoutText(t, measurer);
    if (glyphs.length === 0) return;

    const fx = t.effects;
    const useNN = shouldUseNearestNeighbor(family, t.fontSize);
    const nativeSize = useNN ? FONT_META[family].nativeSize : 0;
    const nativeSpec = useNN ? `${nativeSize}px "${family}"` : '';

    // 1. glow（底层）
    if (fx?.glow && fx.glow.radius > 0) {
        renderGlow(ctx, glyphs, fontSpec, t.fontSize, fx.glow);
    }
    // 2. shadow：drawString offset（与后端 CanvasCompositor 一致，不用 ctx.shadow*）
    if (fx?.shadow) {
        for (const g of glyphs) {
            if (useNN) drawPixelatedGlyph(ctx, g, nativeSpec, t.fontSize, nativeSize,
                    fx.shadow.color, fx.shadow.dx, fx.shadow.dy);
            else {
                ctx.fillStyle = fx.shadow.color;
                drawGlyphFill(ctx, g, t.fontSize, fx.shadow.dx, fx.shadow.dy);
            }
        }
    }
    // 3. stroke：ctx.strokeText（pixelated 路径不支持，仍走原路；对像素字体用 stroke 较少）
    if (fx?.stroke && fx.stroke.width > 0) {
        ctx.strokeStyle = fx.stroke.color;
        ctx.lineWidth = fx.stroke.width;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        for (const g of glyphs) drawGlyphStroke(ctx, g, t.fontSize);
    }
    // 4. fill（顶层）
    for (const g of glyphs) {
        if (useNN) drawPixelatedGlyph(ctx, g, nativeSpec, t.fontSize, nativeSize, t.color, 0, 0);
        else {
            ctx.fillStyle = t.color;
            drawGlyphFill(ctx, g, t.fontSize, 0, 0);
        }
    }
}

/**
 * M5-C5 像素字体最近邻缩放。用 nativeSize 字号的 subcanvas 画 mask →
 * {@code imageSmoothingEnabled=false} drawImage 缩放到 target 尺寸。
 * 位置锚点与 {@link drawGlyphFill} 一致（TextLayout 按 target size 排字）。
 */
function drawPixelatedGlyph(ctx: CanvasRenderingContext2D, g: PositionedGlyph,
                            nativeSpec: string, targetSize: number, nativeSize: number,
                            color: string, dx: number, dy: number): void {
    // 先用主 ctx 切到 native font 测 nativeChW（但不污染后续；下面会复原）
    const savedFont = ctx.font;
    ctx.font = nativeSpec;
    const nativeChW = Math.max(1, Math.round(ctx.measureText(g.ch).width));
    // 目标尺寸（TextLayout 用 target-size 排字；这里按 target chW 贴合）
    ctx.font = `${targetSize}px "${extractFamily(nativeSpec)}"`;
    const targetChW = Math.max(1, Math.round(ctx.measureText(g.ch).width));
    ctx.font = savedFont;

    const targetAscent = Math.round(targetSize * 0.8);
    const nativeAsc = Math.round(nativeSize * 0.8);

    // subcanvas：用 nativeSize 字体画 mask（关 AA）
    const sub = document.createElement('canvas');
    sub.width = nativeChW;
    sub.height = nativeSize;
    const sg = sub.getContext('2d');
    if (!sg) return;
    sg.imageSmoothingEnabled = false;
    // @ts-expect-error non-standard
    sg.textRendering = 'geometricPrecision';
    sg.font = nativeSpec;
    sg.textBaseline = 'alphabetic';
    sg.fillStyle = color;
    sg.fillText(g.ch, 0, nativeAsc);

    const prevSmoothing = ctx.imageSmoothingEnabled;
    ctx.imageSmoothingEnabled = false;
    if (g.rotated) {
        ctx.save();
        ctx.translate(g.x + dx, g.baselineY + dy);
        ctx.rotate(Math.PI / 2);
        ctx.drawImage(sub, -targetChW / 2, targetAscent - targetSize / 2, targetChW, targetSize);
        ctx.restore();
    } else {
        ctx.drawImage(sub, g.x + dx, g.baselineY + dy - targetAscent, targetChW, targetSize);
    }
    ctx.imageSmoothingEnabled = prevSmoothing;
}

function extractFamily(fontSpec: string): string {
    const m = /"([^"]+)"/.exec(fontSpec);
    return m ? m[1] : 'ark_pixel';
}

function drawGlyphFill(ctx: CanvasRenderingContext2D, g: PositionedGlyph,
                      fontSize: number, dx: number, dy: number): void {
    if (!g.rotated) {
        ctx.fillText(g.ch, g.x + dx, g.baselineY + dy);
        return;
    }
    ctx.save();
    ctx.translate(g.x + dx, g.baselineY + dy);
    ctx.rotate(Math.PI / 2);
    const chW = ctx.measureText(g.ch).width;
    const ascent = Math.round(fontSize * 0.8);
    ctx.fillText(g.ch, -chW / 2, ascent - fontSize / 2);
    ctx.restore();
}

function drawGlyphStroke(ctx: CanvasRenderingContext2D, g: PositionedGlyph, fontSize: number): void {
    if (!g.rotated) {
        ctx.strokeText(g.ch, g.x, g.baselineY);
        return;
    }
    ctx.save();
    ctx.translate(g.x, g.baselineY);
    ctx.rotate(Math.PI / 2);
    const chW = ctx.measureText(g.ch).width;
    const ascent = Math.round(fontSize * 0.8);
    ctx.strokeText(g.ch, -chW / 2, ascent - fontSize / 2);
    ctx.restore();
}

// ---------- Glow（自实现盒模糊，镜像 Java GlowRenderer） ----------

function renderGlow(ctx: CanvasRenderingContext2D, glyphs: PositionedGlyph[],
                    fontSpec: string, fontSize: number, glow: Glow): void {
    const radius = glow.radius;
    // 1) 外接矩形（考虑 rotated）
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    const ascent = Math.round(fontSize * 0.8);
    const descent = fontSize - ascent;
    for (const g of glyphs) {
        if (g.rotated) {
            // rotated 占方格 fontSize × fontSize，中心 = (g.x, g.baselineY)
            minX = Math.min(minX, g.x - fontSize / 2);
            maxX = Math.max(maxX, g.x + fontSize / 2);
            minY = Math.min(minY, g.baselineY - fontSize / 2);
            maxY = Math.max(maxY, g.baselineY + fontSize / 2);
        } else {
            const chW = ctx.measureText(g.ch).width;
            minX = Math.min(minX, g.x);
            maxX = Math.max(maxX, g.x + chW);
            minY = Math.min(minY, g.baselineY - ascent);
            maxY = Math.max(maxY, g.baselineY + descent);
        }
    }
    const pad = radius + 1;
    const bboxX = Math.floor(minX - pad);
    const bboxY = Math.floor(minY - pad);
    const bboxW = Math.ceil(maxX - minX) + pad * 2;
    const bboxH = Math.ceil(maxY - minY) + pad * 2;
    if (bboxW <= 0 || bboxH <= 0) return;

    // 2) local canvas：画字形 mask（白色）
    const local = document.createElement('canvas');
    local.width = bboxW;
    local.height = bboxH;
    const lg = local.getContext('2d');
    if (!lg) return;
    lg.imageSmoothingEnabled = false;
    // @ts-expect-error non-standard
    lg.textRendering = 'geometricPrecision';
    lg.font = fontSpec;
    lg.fillStyle = '#ffffff';
    lg.textBaseline = 'alphabetic';
    for (const g of glyphs) {
        if (g.rotated) {
            lg.save();
            lg.translate(g.x - bboxX, g.baselineY - bboxY);
            lg.rotate(Math.PI / 2);
            const chW = lg.measureText(g.ch).width;
            lg.fillText(g.ch, -chW / 2, ascent - fontSize / 2);
            lg.restore();
        } else {
            lg.fillText(g.ch, g.x - bboxX, g.baselineY - bboxY);
        }
    }

    // 3) 提取 alpha + 水平/垂直盒模糊
    const imgData = lg.getImageData(0, 0, bboxW, bboxH);
    const n = bboxW * bboxH;
    const alpha = new Uint8Array(n);
    for (let i = 0; i < n; i++) alpha[i] = imgData.data[i * 4 + 3];
    const tmp = new Uint8Array(n);
    boxBlurHorizontal(alpha, tmp, bboxW, bboxH, radius);
    boxBlurVertical(tmp, alpha, bboxW, bboxH, radius);

    // 4) 着色
    const rgb = parseRgb(glow.color);
    for (let i = 0; i < n; i++) {
        imgData.data[i * 4] = rgb[0];
        imgData.data[i * 4 + 1] = rgb[1];
        imgData.data[i * 4 + 2] = rgb[2];
        imgData.data[i * 4 + 3] = alpha[i];
    }
    lg.putImageData(imgData, 0, 0);

    // 5) 合到主画布
    ctx.drawImage(local, bboxX, bboxY);
}

function boxBlurHorizontal(src: Uint8Array, dst: Uint8Array, w: number, h: number, radius: number): void {
    const diameter = radius * 2 + 1;
    for (let y = 0; y < h; y++) {
        const rowBase = y * w;
        let sum = 0;
        for (let dx = -radius; dx <= radius; dx++) {
            sum += src[rowBase + clamp(dx, 0, w - 1)];
        }
        for (let x = 0; x < w; x++) {
            dst[rowBase + x] = Math.floor(sum / diameter);
            sum += src[rowBase + clamp(x + radius + 1, 0, w - 1)]
                 - src[rowBase + clamp(x - radius, 0, w - 1)];
        }
    }
}

function boxBlurVertical(src: Uint8Array, dst: Uint8Array, w: number, h: number, radius: number): void {
    const diameter = radius * 2 + 1;
    for (let x = 0; x < w; x++) {
        let sum = 0;
        for (let dy = -radius; dy <= radius; dy++) {
            sum += src[clamp(dy, 0, h - 1) * w + x];
        }
        for (let y = 0; y < h; y++) {
            dst[y * w + x] = Math.floor(sum / diameter);
            sum += src[clamp(y + radius + 1, 0, h - 1) * w + x]
                 - src[clamp(y - radius, 0, h - 1) * w + x];
        }
    }
}

function clamp(v: number, lo: number, hi: number): number {
    return v < lo ? lo : (v > hi ? hi : v);
}

function parseRgb(hex: string): [number, number, number] {
    const m = /^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$/.exec(hex);
    if (!m) return [255, 255, 255];
    const rgb = parseInt(m[1], 16);
    return [(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff];
}

function fontFamily(fontId: string): string {
    const KNOWN = new Set(['ark_pixel', 'source_han_sans']);
    return KNOWN.has(fontId) ? fontId : 'ark_pixel';
}
