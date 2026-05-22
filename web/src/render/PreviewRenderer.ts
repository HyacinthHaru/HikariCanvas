import type { ProjectState, Element, Layer, RectElement, TextElement, IconElement, ImageElement, PathElement, CircleElement, ShapeElement, BrushStrokeElement, Glow } from '@/types/protocol';
import { isLegacyIconSource } from '@/types/protocol';
import { layoutText, ASCENT_RATIO, type PositionedGlyph } from './TextLayout';
import { applyBlendModeOver } from './BlendModes';
import { parsePathD } from './PathParser';
import { arrowSize, arrowShape, dotRadius, drawArrow, drawDot } from './MarkerRenderer';
import { fillToCanvasStyle } from './fill';
import { applyBayerDither } from './BayerDither';
import { getPaletteLut, type PaletteLut } from './PaletteLut';
import { ensureLoaded, isLoaded } from './FontLoader';
import { ensureLoaded as ensureIconLoaded, getCached as getCachedIcon, onIconLoaded } from './IconLoader';
import { interpolate, type PlaceholderSegment } from '@/variable/interpolator';
import type { useVariableStore } from '@/stores/variables';

/**
 * 前端 Canvas 2D 预览渲染器。镜像 Java {@code CanvasCompositor}。
 * 契约 docs/rendering.md §4 / §5（效果渲染顺序：glow → shadow → stroke → fill）。
 *
 * M8-E 升级：分层渲染。同后端 fast/slow path 双轨；fast path 直接画主 ctx，
 * slow path 走 offscreen canvas + ImageData {@code applyBlendModeOver} 合成。
 */
export function renderProjectState(
    ctx: CanvasRenderingContext2D,
    state: ProjectState | null,
    hideIds?: Set<string>,
): void {
    const widthPx = (state?.canvas.widthMaps ?? 1) * 128;
    const heightPx = (state?.canvas.heightMaps ?? 1) * 128;

    // M17 F5：canvas.background 升级为 Fill 联合类型。镜像后端 FillPaintBuilder.fillToPaint：
    // solid → hex string；linear/radial → CanvasGradient（端点 / 中心由 bbox=整画布推）。
    const bgStyle = fillToCanvasStyle(ctx, state?.canvas.background, 0, 0, widthPx, heightPx);
    ctx.fillStyle = bgStyle ?? '#FFFFFF';
    ctx.fillRect(0, 0, widthPx, heightPx);

    if (!state) return;

    ctx.imageSmoothingEnabled = false;
    // @ts-expect-error non-standard but supported in Chromium
    ctx.textRendering = 'geometricPrecision';

    for (const layer of state.layers) {
        if (!layer.visible || layer.opacity <= 0) continue;
        if (layer.elements.length === 0) continue;
        const visibleElements = hideIds
            ? layer.elements.filter((e) => e.visible && !hideIds.has(e.id))
            : layer.elements.filter((e) => e.visible);
        if (visibleElements.length === 0) continue;

        if (canFastPath(layer)) {
            for (const e of visibleElements) drawElement(ctx, e, widthPx, heightPx);
        } else {
            // slow path：offscreen ARGB canvas 画 layer 内 element，按 layerOpacity/blendMode 合到主 ctx
            renderLayerSlowPath(ctx, visibleElements, layer, widthPx, heightPx);
        }
    }
}

/** 同后端 canFastPath 判定（含 element.renderMode 防御 check，与 M11 dither 集成对齐）。 */
function canFastPath(layer: Layer): boolean {
    if (layer.opacity < 1) return false;
    if (layer.blendMode !== 'normal') return false;
    for (const e of layer.elements) {
        const op = e.opacity;
        if (op !== undefined && op !== null && op < 1) return false;
        if (e.renderMode && e.renderMode !== 'clean') return false;
        // element-level blendMode 字段保留但 M8-E 不实装合成（M11 dither 一起）
    }
    return true;
}

function renderLayerSlowPath(
    dstCtx: CanvasRenderingContext2D,
    elements: Element[],
    layer: Layer,
    widthPx: number,
    heightPx: number,
): void {
    const off = document.createElement('canvas');
    off.width = widthPx;
    off.height = heightPx;
    const og = off.getContext('2d');
    if (!og) return;
    og.imageSmoothingEnabled = false;
    // @ts-expect-error non-standard
    og.textRendering = 'geometricPrecision';
    // 透明背景；不 fillRect
    for (const e of elements) drawElement(og, e, widthPx, heightPx);

    // ImageData 合成：dst ImageData + src ImageData → applyBlendModeOver mutate dst → putImageData 回 dst
    const dstImg = dstCtx.getImageData(0, 0, widthPx, heightPx);
    const srcImg = og.getImageData(0, 0, widthPx, heightPx);
    applyBlendModeOver(dstImg, srcImg, layer.opacity, layer.blendMode);
    dstCtx.putImageData(dstImg, 0, 0);
}

function drawElement(ctx: CanvasRenderingContext2D, e: Element, widthPx: number, heightPx: number): void {
    if (e.renderMode === 'dither') {
        drawDitheredElement(ctx, e, widthPx, heightPx);
        return;
    }
    ctx.save();
    if (e.rotation !== 0) {
        const cx = e.x + e.w / 2;
        const cy = e.y + e.h / 2;
        ctx.translate(cx, cy);
        ctx.rotate((e.rotation * Math.PI) / 180);
        ctx.translate(-cx, -cy);
    }
    // M8-E：element-level opacity 通过 globalAlpha 实装（同后端 SrcOver.derive）
    const op = e.opacity;
    if (op !== undefined && op !== null && op < 1) {
        ctx.globalAlpha = ctx.globalAlpha * op;
    }
    drawElementBody(ctx, e);
    ctx.restore();
}

function drawElementBody(ctx: CanvasRenderingContext2D, e: Element): void {
    if (e.type === 'rect') drawRect(ctx, e);
    else if (e.type === 'text') drawText(ctx, e);
    else if (e.type === 'icon') drawIcon(ctx, e);
    else if (e.type === 'path') drawPath(ctx, e);
    else if (e.type === 'circle') drawCircle(ctx, e);
    else if (e.type === 'shape') drawShape(ctx, e);
    else if (e.type === 'brush') drawBrush(ctx, e);
    else if (e.type === 'image') drawImage(ctx, e);
}

/**
 * M12-C 笔触绘制：与 Java {@code CanvasCompositor.drawBrush} 公式逐行镜像。
 * Catmull-Rom → cubic Bezier：B1 = P1 + (P2 - P0) / 6, B2 = P2 - (P3 - P1) / 6；
 * 首尾 phantom：P[-1] = P[0], P[n] = P[n-1]。段宽度 = size × avgPressure（pressureSize）；
 * 段 alpha 与外层 globalAlpha 复合。
 */
function drawBrush(ctx: CanvasRenderingContext2D, b: BrushStrokeElement): void {
    if (!b.points || b.points.length < 2) return;
    const paint = fillToCanvasStyle(ctx, b.fill, b.x, b.y, b.w, b.h);
    if (!paint) return;
    ctx.strokeStyle = paint;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    const outerAlpha = ctx.globalAlpha;
    const n = b.points.length;
    for (let i = 0; i < n - 1; i++) {
        const p1 = b.points[i];
        const p2 = b.points[i + 1];
        const p0 = i > 0 ? b.points[i - 1] : p1;
        const p3 = i < n - 2 ? b.points[i + 2] : p2;

        const x1 = b.x + p1.x, y1 = b.y + p1.y;
        const x2 = b.x + p2.x, y2 = b.y + p2.y;
        const x0 = b.x + p0.x, y0 = b.y + p0.y;
        const x3 = b.x + p3.x, y3 = b.y + p3.y;

        const cx1 = x1 + (x2 - x0) / 6;
        const cy1 = y1 + (y2 - y0) / 6;
        const cx2 = x2 - (x3 - x1) / 6;
        const cy2 = y2 - (y3 - y1) / 6;

        const avgPressure = (p1.pressure + p2.pressure) / 2;
        const segWidth = b.pressureSize
            ? Math.max(1, b.size * Math.max(0.05, avgPressure))
            : Math.max(1, b.size);
        let segAlpha = outerAlpha;
        if (b.pressureOpacity) {
            segAlpha *= Math.max(0.05, avgPressure);
        }
        ctx.globalAlpha = Math.min(1, segAlpha);
        ctx.lineWidth = segWidth;

        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.bezierCurveTo(cx1, cy1, cx2, cy2, x2, y2);
        ctx.stroke();
    }
    ctx.globalAlpha = outerAlpha;
}

// ---------- M11-C：dither pass（per-element off-canvas → BayerDither → drawImage） ----------

let cachedPalette: PaletteLut | null = null;
let paletteReadyHook: (() => void) | null = null;

/** CanvasView 注册：PaletteLut 加载完成 → 请求重绘（dither element 首帧本来 fallback clean）。 */
export function onPaletteReady(hook: () => void) { paletteReadyHook = hook; }

function getCachedPalette(): PaletteLut | null {
    if (cachedPalette) return cachedPalette;
    // 首次：发起 lazy load，本帧返回 null，加载完后 hook 触发重绘
    getPaletteLut().then((p) => {
        cachedPalette = p;
        paletteReadyHook?.();
    }).catch(() => { /* palette 加载失败：dither element 永久 fallback 走 clean */ });
    return null;
}

/**
 * dither element 路径：与后端 CanvasCompositor.drawDitheredElement 镜像。
 * 单 element 一张 canvas 尺寸的 off-canvas，画 element body（含 rotation）→ getImageData
 * → applyBayerDither → putImageData → drawImage 回主 ctx（opacity 通过主 ctx.globalAlpha 起效）。
 *
 * <p>palette 未就绪时降级走 clean 路径（drawElementBody 直接画主 ctx + rotation/opacity），
 * palette 加载完后 hook 触发重绘自动切到 dither 形态。</p>
 */
function drawDitheredElement(
    ctx: CanvasRenderingContext2D,
    e: Element,
    widthPx: number,
    heightPx: number,
): void {
    const palette = getCachedPalette();
    if (!palette) {
        // fallback：clean 路径
        ctx.save();
        if (e.rotation !== 0) {
            const cx = e.x + e.w / 2;
            const cy = e.y + e.h / 2;
            ctx.translate(cx, cy);
            ctx.rotate((e.rotation * Math.PI) / 180);
            ctx.translate(-cx, -cy);
        }
        const op = e.opacity;
        if (op !== undefined && op !== null && op < 1) {
            ctx.globalAlpha = ctx.globalAlpha * op;
        }
        drawElementBody(ctx, e);
        ctx.restore();
        return;
    }
    const off = document.createElement('canvas');
    off.width = widthPx;
    off.height = heightPx;
    const og = off.getContext('2d');
    if (!og) return;
    og.imageSmoothingEnabled = false;
    if (e.rotation !== 0) {
        const cx = e.x + e.w / 2;
        const cy = e.y + e.h / 2;
        og.translate(cx, cy);
        og.rotate((e.rotation * Math.PI) / 180);
        og.translate(-cx, -cy);
    }
    drawElementBody(og, e);

    const img = og.getImageData(0, 0, widthPx, heightPx);
    applyBayerDither(img, palette);
    og.putImageData(img, 0, 0);

    // element.opacity 通过主 ctx.globalAlpha 起作用（drawImage 走 SrcOver）
    const op = e.opacity;
    const prevAlpha = ctx.globalAlpha;
    if (op !== undefined && op !== null && op < 1) {
        ctx.globalAlpha = prevAlpha * op;
    }
    ctx.drawImage(off, 0, 0);
    ctx.globalAlpha = prevAlpha;
}

// ---------- M9-C：PathElement / CircleElement / ShapeElement 真实绘制 ----------

/** 与后端 CanvasCompositor.drawPath 镜像。d 内坐标相对 element.(x, y)。 */
function drawPath(ctx: CanvasRenderingContext2D, p: PathElement): void {
    if (!p.d) return;
    const parsed = parsePathD(p.d);

    ctx.save();
    ctx.translate(p.x, p.y);

    // d 内已 translate(p.x, p.y)，渐变 bbox 用本地坐标 0..p.w/p.h
    const pFill = fillToCanvasStyle(ctx, p.fill, 0, 0, p.w, p.h);
    if (pFill) {
        ctx.fillStyle = pFill;
        ctx.fill(parsed.path);
    }

    let strokeColor: string | null = null;
    let strokeWidth = 0;
    if (p.stroke && p.stroke.width > 0) {
        strokeWidth = p.stroke.width;
        strokeColor = p.stroke.color;
        ctx.strokeStyle = strokeColor;
        ctx.lineWidth = strokeWidth;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';

        // 2026-05-15 修箭头 Bug：arrow apex 处宽 0，粗 stroke 会从 arrow 锥尖戳出。
        // 在描边前 clip 一个反相形状（大矩形外圈 + arrow 三角形作"洞"，evenodd 填充规则），
        // 让 stroke 不画进 arrow 内部，arrow 自己 fill 覆盖。
        const subtractClip = buildArrowSubtractClip(parsed, p, strokeWidth);
        if (subtractClip) {
            ctx.save();
            ctx.clip(subtractClip, 'evenodd');
            ctx.stroke(parsed.path);
            ctx.restore();
        } else {
            ctx.stroke(parsed.path);
        }
    }

    if (parsed.hasSegments && strokeColor) {
        // 0.4.7：element-aware cap — 让 marker 大小不超过 element 对角线某比例，
        // 避免 stroke 极粗时 marker 吞没短箭头（详见 MarkerRenderer.arrowSize 注释）
        const diag = Math.hypot(p.w, p.h);
        if (p.markerEnd) {
            drawPathMarker(ctx, p.markerEnd,
                parsed.endX, parsed.endY,
                parsed.endTangentX, parsed.endTangentY,
                strokeWidth, diag, strokeColor);
        }
        if (p.markerStart) {
            // markerStart 朝起点外 = startTangent 反向
            drawPathMarker(ctx, p.markerStart,
                parsed.startX, parsed.startY,
                -parsed.startTangentX, -parsed.startTangentY,
                strokeWidth, diag, strokeColor);
        }
    }

    ctx.restore();
}

function drawPathMarker(
    ctx: CanvasRenderingContext2D,
    type: 'arrow' | 'dot',
    x: number, y: number,
    dirX: number, dirY: number,
    strokeWidth: number,
    elementDiagonal: number,
    color: string,
): void {
    if (type === 'arrow') {
        drawArrow(ctx, x, y, dirX, dirY, arrowSize(strokeWidth, elementDiagonal), color);
    } else if (type === 'dot') {
        drawDot(ctx, x, y, dotRadius(strokeWidth, elementDiagonal), color);
    }
}

/**
 * 构造「整 element bbox 减去 arrow 三角形」的反相 clip 形状。返回 null = 无 arrow，
 * 调用方走原描边路径。坐标系：调用时 ctx 已 translate(p.x, p.y)，所有几何用本地坐标。
 */
function buildArrowSubtractClip(
    parsed: ReturnType<typeof parsePathD>,
    p: PathElement,
    strokeWidth: number,
): Path2D | null {
    const hasEndArrow = p.markerEnd === 'arrow';
    const hasStartArrow = p.markerStart === 'arrow';
    if (!hasEndArrow && !hasStartArrow) return null;
    if (!parsed.hasSegments) return null;

    // 0.4.7：clip 减除 size 必须与实际绘制 size 一致，否则 stroke 末端从扣减区"漏出"
    const diag = Math.hypot(p.w, p.h);
    const size = arrowSize(strokeWidth, diag);
    const clip = new Path2D();
    // 外圈：大矩形（含 strokeWidth padding 防 AA gap）。坐标原点已 translate 到 p.(x,y)，
    // 所以本地坐标 0..p.w/p.h；padding 取 strokeWidth + size 足够外延。
    const pad = strokeWidth + size + 16;
    clip.rect(-pad, -pad, p.w + 2 * pad, p.h + 2 * pad);

    if (hasEndArrow) {
        const tri = arrowShape(parsed.endX, parsed.endY,
            parsed.endTangentX, parsed.endTangentY, size);
        if (tri) clip.addPath(tri);
    }
    if (hasStartArrow) {
        const tri = arrowShape(parsed.startX, parsed.startY,
            -parsed.startTangentX, -parsed.startTangentY, size);
        if (tri) clip.addPath(tri);
    }
    return clip;
}

/** 与后端 CanvasCompositor.drawCircle 镜像：bbox 推 cx/cy/rx/ry → ctx.ellipse。 */
function drawCircle(ctx: CanvasRenderingContext2D, c: CircleElement): void {
    const cx = c.x + c.w / 2;
    const cy = c.y + c.h / 2;
    const rx = c.w / 2;
    const ry = c.h / 2;

    const cFill = fillToCanvasStyle(ctx, c.fill, c.x, c.y, c.w, c.h);
    if (cFill) {
        ctx.fillStyle = cFill;
        ctx.beginPath();
        ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
        ctx.fill();
    }
    if (c.stroke && c.stroke.width > 0) {
        ctx.strokeStyle = c.stroke.color;
        ctx.lineWidth = c.stroke.width;
        ctx.lineCap = 'butt';
        ctx.lineJoin = 'miter';
        ctx.beginPath();
        ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
        ctx.stroke();
    }
}

/** 与后端 CanvasCompositor.drawShape / buildShapePath 镜像。 */
function drawShape(ctx: CanvasRenderingContext2D, s: ShapeElement): void {
    const cx = s.x + s.w / 2;
    const cy = s.y + s.h / 2;
    const outerR = Math.min(s.w, s.h) / 2;
    const star = s.kind === 'star';
    const innerR = star ? outerR * (s.innerRatio ?? 0.5) : 0;
    const sides = s.sides;
    const totalVerts = star ? sides * 2 : sides;

    ctx.beginPath();
    for (let i = 0; i < totalVerts; i++) {
        const angle = -Math.PI / 2 + (Math.PI * 2 * i / totalVerts);
        const r = (star && (i & 1) === 1) ? innerR : outerR;
        const x = cx + Math.cos(angle) * r;
        const y = cy + Math.sin(angle) * r;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
    }
    ctx.closePath();

    const sFill = fillToCanvasStyle(ctx, s.fill, s.x, s.y, s.w, s.h);
    if (sFill) {
        ctx.fillStyle = sFill;
        ctx.fill();
    }
    if (s.stroke && s.stroke.width > 0) {
        ctx.strokeStyle = s.stroke.color;
        ctx.lineWidth = s.stroke.width;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        ctx.stroke();
    }
}

// ---------- Icon 图标 ----------
//
// 加载策略：源走 /api/template-asset/icons/<source>.png。Image 是异步的，但 renderProjectState
// 是同步函数 —— 因此首次绘制时图未就绪，画占位 ?；图加载完毕调用 onIconReady() 回调，外层
// CanvasView 的 requestDraw 会再触发一次完整重绘。

interface IconCacheEntry { img: HTMLImageElement; ready: boolean; failed: boolean; }
const iconCache = new Map<string, IconCacheEntry>();
let iconReadyHook: (() => void) | null = null;

/** CanvasView 注册：图标异步加载完成后请求重绘 */
export function onIconReady(hook: () => void) { iconReadyHook = hook; }

function getIconImage(source: string): IconCacheEntry {
    let entry = iconCache.get(source);
    if (entry) return entry;
    const img = new Image();
    entry = { img, ready: false, failed: false };
    iconCache.set(source, entry);
    img.onload = () => { entry!.ready = true; iconReadyHook?.(); };
    img.onerror = () => { entry!.failed = true; iconReadyHook?.(); };
    img.src = `/api/template-asset/icons/${encodeURIComponent(source)}.png`;
    return entry;
}

function drawIcon(ctx: CanvasRenderingContext2D, ic: IconElement): void {
    if (isLegacyIconSource(ic.source)) {
        drawIconLegacyPng(ctx, ic);
        return;
    }
    drawIconSvgPath(ctx, ic);
}

/** M7 legacy PNG 路径（行为完全不变）：source 不含 `/` 时走 /api/template-asset/icons/<source>.png。 */
function drawIconLegacyPng(ctx: CanvasRenderingContext2D, ic: IconElement): void {
    const entry = getIconImage(ic.source);
    if (entry.failed || !entry.ready) {
        drawIconPlaceholder(ctx, ic);
        return;
    }
    if (!ic.tint) {
        ctx.drawImage(entry.img, ic.x, ic.y, ic.w, ic.h);
        return;
    }
    // tint：先画到 offscreen canvas，再 source-in 染色，最后贴到主 canvas
    const off = document.createElement('canvas');
    off.width = ic.w;
    off.height = ic.h;
    const octx = off.getContext('2d');
    if (!octx) { ctx.drawImage(entry.img, ic.x, ic.y, ic.w, ic.h); return; }
    octx.imageSmoothingEnabled = false;
    octx.drawImage(entry.img, 0, 0, ic.w, ic.h);
    octx.globalCompositeOperation = 'source-in';
    octx.fillStyle = ic.tint;
    octx.fillRect(0, 0, ic.w, ic.h);
    ctx.drawImage(off, ic.x, ic.y);
}

/**
 * M26.2 SVG 矢量路径：source 含 `/`（如 `fa-solid/heart`）→ 从 IconLoader 拉 path d + viewBox，
 * 用 Path2D + 变换 + Fill 绘制。镜像后端 {@code IconRenderer.renderSvgPath}：
 *
 * <ol>
 *   <li>cache 未命中触发 ensureLoaded（异步），本帧画占位 ?；加载完 onIconLoaded → requestDraw</li>
 *   <li>cache 中 null = 失败/不存在 → 画占位 ?</li>
 *   <li>等比缩放居中：scale = min(elW/vbW, elH/vbH)；offset 含 vbX/vbY 平移</li>
 *   <li>fill：FillCompat → ctx.fillStyle (string | CanvasGradient)；undefined → 黑色</li>
 * </ol>
 */
function drawIconSvgPath(ctx: CanvasRenderingContext2D, ic: IconElement): void {
    if (ic.w <= 0 || ic.h <= 0) return;
    const cached = getCachedIcon(ic.source);
    if (cached === undefined) {
        // 首次：发起 lazy load，本帧占位；加载完后 hook 触发重绘
        void ensureIconLoaded(ic.source);
        drawIconPlaceholder(ctx, ic);
        return;
    }
    if (cached === null) {
        drawIconPlaceholder(ctx, ic);
        return;
    }
    const vb = parseViewBox(cached.viewBox);
    if (!vb) { drawIconPlaceholder(ctx, ic); return; }
    const [vbX, vbY, vbW, vbH] = vb;
    if (vbW <= 0 || vbH <= 0) { drawIconPlaceholder(ctx, ic); return; }

    const scale = Math.min(ic.w / vbW, ic.h / vbH);
    const offX = (ic.w - vbW * scale) / 2 - vbX * scale;
    const offY = (ic.h - vbH * scale) / 2 - vbY * scale;

    // fill 渐变 bbox = element bbox（与 Rect/Circle 一致）。fillStyle 在变换前算好（用 element 坐标系），
    // 否则 CanvasGradient 端点会被 translate/scale 错位。Canvas API: fillStyle 一旦 set，
    // 后续 fill() 在当前变换矩阵下绘制——gradient 几何按 set 时坐标系算。
    const style = fillToCanvasStyle(ctx, ic.fill, ic.x, ic.y, ic.w, ic.h) ?? '#000000';

    ctx.save();
    try {
        ctx.translate(ic.x + offX, ic.y + offY);
        ctx.scale(scale, scale);
        ctx.fillStyle = style;
        for (const p of cached.paths) {
            const path2d = new Path2D(p.d);
            ctx.fill(path2d);
        }
    } finally {
        ctx.restore();
    }
}

function parseViewBox(viewBox: string): [number, number, number, number] | null {
    const parts = viewBox.trim().split(/[\s,]+/);
    if (parts.length !== 4) return null;
    const nums: number[] = [];
    for (const p of parts) {
        const n = Number(p);
        if (!Number.isFinite(n)) return null;
        nums.push(n);
    }
    return [nums[0], nums[1], nums[2], nums[3]];
}

function drawIconPlaceholder(ctx: CanvasRenderingContext2D, ic: IconElement): void {
    ctx.save();
    ctx.strokeStyle = '#AAAAAA';
    ctx.setLineDash([3, 2]);
    ctx.strokeRect(ic.x + 0.5, ic.y + 0.5, ic.w - 1, ic.h - 1);
    ctx.setLineDash([]);
    ctx.fillStyle = '#AAAAAA';
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('?', ic.x + ic.w / 2, ic.y + ic.h / 2);
    ctx.restore();
}

// ---------- M13 ImageElement ----------
//
// 加载策略：源走 /api/upload/<hash>。同 IconElement 异步模型：首次绘制时图未就绪→占位 ?，
// 加载完毕调用同一 iconReadyHook → CanvasView.requestDraw 再触一次重绘。
// mask 用 Canvas 2D ctx.clip(Path2D) 实装；inverted 用 even-odd fill rule 反相。

interface ImageCacheEntry { img: HTMLImageElement; ready: boolean; failed: boolean; }
const imageCache = new Map<string, ImageCacheEntry>();

// M16 P1.1：/api/upload/{hash} 后端鉴权要 sessionId query param；上层注入访问器避免
// PreviewRenderer 直接耦合 Pinia store。
let uploadAuthProvider: (() => string | null) | null = null;
export function setUploadAuthProvider(fn: () => string | null) { uploadAuthProvider = fn; }

function buildUploadUrl(source: string): string {
    const sid = uploadAuthProvider?.() ?? null;
    const base = `/api/upload/${encodeURIComponent(source)}`;
    return sid ? `${base}?sessionId=${encodeURIComponent(sid)}` : base;
}

function getUploadImage(source: string): ImageCacheEntry {
    let entry = imageCache.get(source);
    if (entry) return entry;
    const img = new Image();
    entry = { img, ready: false, failed: false };
    imageCache.set(source, entry);
    img.onload = () => { entry!.ready = true; iconReadyHook?.(); };
    img.onerror = () => { entry!.failed = true; iconReadyHook?.(); };
    img.src = buildUploadUrl(source);
    return entry;
}

/** 上传完成后立即缓存 + 标 ready，省去一次 fetch 往返。可选优化入口。 */
export function preloadImage(source: string, dataUrl: string): void {
    if (imageCache.has(source)) return;
    const img = new Image();
    const entry: ImageCacheEntry = { img, ready: false, failed: false };
    imageCache.set(source, entry);
    img.onload = () => { entry.ready = true; iconReadyHook?.(); };
    img.onerror = () => { entry.failed = true; iconReadyHook?.(); };
    img.src = dataUrl;
}

function drawImage(ctx: CanvasRenderingContext2D, im: ImageElement): void {
    const entry = getUploadImage(im.source);
    if (entry.failed || !entry.ready) {
        // 占位：虚线方框 + ?，同 drawIcon 风格
        ctx.save();
        ctx.strokeStyle = '#AAAAAA';
        ctx.setLineDash([3, 2]);
        ctx.strokeRect(im.x + 0.5, im.y + 0.5, im.w - 1, im.h - 1);
        ctx.setLineDash([]);
        ctx.fillStyle = '#AAAAAA';
        ctx.font = '12px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('?', im.x + im.w / 2, im.y + im.h / 2);
        ctx.restore();
        return;
    }

    ctx.save();
    try {
        ctx.translate(im.x, im.y);
        if (im.mask) {
            const parsed = parsePathD(im.mask.d);
            if (im.mask.inverted) {
                // 反相 = bbox 减 mask；用 Path2D + addPath + evenodd 自然达成
                const inverted = new Path2D();
                inverted.rect(0, 0, im.w, im.h);
                inverted.addPath(parsed.path);
                ctx.clip(inverted, 'evenodd');
            } else {
                ctx.clip(parsed.path);
            }
        }
        ctx.drawImage(entry.img, 0, 0, im.w, im.h);
    } finally {
        ctx.restore();
    }
}

function drawRect(ctx: CanvasRenderingContext2D, r: RectElement): void {
    const rFill = fillToCanvasStyle(ctx, r.fill, r.x, r.y, r.w, r.h);
    if (rFill) {
        ctx.fillStyle = rFill;
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

export interface FontMeta { displayName: string; pixelated: boolean; nativeSize: number; }
export const FONT_META: Record<string, FontMeta> = {
    ark_pixel: { displayName: 'Ark Pixel 12px（像素）', pixelated: true, nativeSize: 12 },
    source_han_sans: { displayName: '思源黑体 SC Regular', pixelated: false, nativeSize: 0 },
    // M21：6 个新内置字体（全 SIL OFL 1.1）
    source_han_serif: { displayName: '思源宋体 SC Regular', pixelated: false, nativeSize: 0 },
    jetbrains_mono: { displayName: 'JetBrains Mono Regular', pixelated: false, nativeSize: 0 },
    fira_code: { displayName: 'Fira Code Regular', pixelated: false, nativeSize: 0 },
    inter: { displayName: 'Inter Regular', pixelated: false, nativeSize: 0 },
    noto_serif: { displayName: 'Noto Serif Regular', pixelated: false, nativeSize: 0 },
    // M22：13 个艺术 / 装饰字体（全 SIL OFL 1.1）
    // 中文艺术 6
    smiley_sans: { displayName: '得意黑 Smiley Sans Oblique', pixelated: false, nativeSize: 0 },
    ma_shan_zheng: { displayName: '马善政毛笔楷书 Ma Shan Zheng Regular', pixelated: false, nativeSize: 0 },
    zcool_xiaowei: { displayName: '站酷小薇 ZCOOL XiaoWei Regular', pixelated: false, nativeSize: 0 },
    zcool_kuaile: { displayName: '站酷快乐体 ZCOOL KuaiLe Regular', pixelated: false, nativeSize: 0 },
    zcool_qingkehuangyou: { displayName: '站酷庆科黄油体 ZCOOL QingKe HuangYou Regular', pixelated: false, nativeSize: 0 },
    lxgw_wenkai: { displayName: '霞鹜文楷 LXGW WenKai Regular', pixelated: false, nativeSize: 0 },
    // 西文装饰 7（permanent_marker Apache License 跳过，shadows_into_light 替代）
    comic_neue: { displayName: 'Comic Neue Regular', pixelated: false, nativeSize: 0 },
    pacifico: { displayName: 'Pacifico Regular', pixelated: false, nativeSize: 0 },
    lobster: { displayName: 'Lobster Regular', pixelated: false, nativeSize: 0 },
    bangers: { displayName: 'Bangers Regular', pixelated: false, nativeSize: 0 },
    shadows_into_light: { displayName: 'Shadows Into Light Regular', pixelated: false, nativeSize: 0 },
    caveat: { displayName: 'Caveat Regular', pixelated: false, nativeSize: 0 },
    dancing_script: { displayName: 'Dancing Script Regular', pixelated: false, nativeSize: 0 },
    // M25：FHWA / Bahnschrift OFL 替代品
    overpass: { displayName: 'Overpass Regular (FHWA-like)', pixelated: false, nativeSize: 0 },
    bebas_neue: { displayName: 'Bebas Neue Regular (DIN-like)', pixelated: false, nativeSize: 0 },
};

/**
 * M5-D4 修 Bug 3/4：放宽为 <b>只要是像素字体就走 NN 路径</b>。
 *
 * <p>原实现要求 {@code targetSize} 是 {@code nativeSize}(12) 的 ≥2 整数倍，否则 fallback
 * 到 {@code ctx.fillText} —— 浏览器在非整数倍下会对 TTF 嵌入位图插值放大得到灰阶像素，
 * 看起来就像系统黑体而非点阵（Bug 3），且 ascent/descent 视觉上溢出 lineHeight 造成叠字
 * 视觉（Bug 4）。放宽后任何 {@code fontSize} 都用 {@code nativeSize=12} 画 mask → NEAREST
 * {@code drawImage} 到 target，保持纯黑白方块。Java 侧同步放宽。</p>
 */
function shouldUseNearestNeighbor(family: string): boolean {
    const meta = FONT_META[family];
    return !!(meta?.pixelated && meta.nativeSize > 0);
}

// ---------- Variable interpolation context（M28-enhance） ----------
//
// PreviewRenderer 是同步纯函数；运行时需要拿当前 wallId + Pinia variable store 解析 ${var:X}。
// 上层 CanvasView mount 时调 setVariableContext 注入访问器，避免 PreviewRenderer 直接 import
// useProjectStore / useVariableStore（保持渲染层与 Pinia 解耦，便于单测）。

interface VariableContext {
    wallId: string | null;
    store: ReturnType<typeof useVariableStore>;
}
let variableContextProvider: (() => VariableContext | null) | null = null;
export function setVariableContextProvider(fn: () => VariableContext | null) {
    variableContextProvider = fn;
}

// chip hint 配色：Catppuccin Mauve（半透明），与编辑器主题协调。
// 0.18 alpha 让背景轻微高亮但不刺眼；padding 给点上下气流防字形太顶。
const HINT_FILL = 'rgba(203, 166, 247, 0.20)';   // ctp-mauve @ 0.20
const HINT_BORDER = 'rgba(203, 166, 247, 0.50)';

/** 解析 TextElement.text 中的 ${var:X}。fallback：无 context 时原文本 + 空 segments。 */
function resolveTextForRender(t: TextElement): { rendered: string; segments: PlaceholderSegment[] } {
    const text = t.text;
    if (text == null || text.length === 0) return { rendered: '', segments: [] };
    if (text.indexOf('${var:') < 0) return { rendered: text, segments: [] };
    const ctx = variableContextProvider?.() ?? null;
    if (!ctx) return { rendered: text, segments: [] };
    const r = interpolate(text, ctx.wallId, ctx.store);
    let rendered = r.text;
    // 0.4.2 bugfix（Bug 1 兜底）：interpolator 已做二次扫描；若仍含 ${var:} 字面 = 数据损坏
    // （嵌套 / 错乱字符 / depth limit 内无法收敛），强制全替换为 "???" 防 wall 显字面 placeholder。
    if (rendered.indexOf('${var:') >= 0) {
        rendered = rendered.replace(/\$\{var:[^}]*\}/g, '???');
        if (import.meta.env.DEV) {
            // eslint-disable-next-line no-console
            console.warn('[PreviewRenderer] residual ${var:} after interpolate; replaced with ???');
        }
    }
    return { rendered, segments: r.segments };
}

// ---------- Text：4 层 glow → shadow → stroke → fill ----------

function drawText(ctx: CanvasRenderingContext2D, t: TextElement): void {
    if (!t.text) return;
    // 0.4.6 P3：italic = shear transform。包裹整个 drawText 内部绘制。
    // ctx.transform(1, 0, -0.2, 1, ...) 与 AWT AffineTransform.shear(-0.2, 0) 数学等价
    // → 双端像素一致。在 ctx.save / restore 内安全嵌套不影响外层 transform。
    const italic = t.italic === true;
    if (italic) {
        ctx.save();
        ctx.translate(t.x, t.y);
        ctx.transform(1, 0, -0.2, 1, 0, 0);
        ctx.translate(-t.x, -t.y);
    }
    try {
        drawTextInner(ctx, t);
    } finally {
        if (italic) ctx.restore();
    }
}

function drawTextInner(ctx: CanvasRenderingContext2D, t: TextElement): void {
    // M23：fontId 直接当 family（删除 KNOWN 白名单 fallback）。未加载的 fontId 触发
    //     ensureLoaded 异步加载；首帧 ctx.font 走系统 fallback，加载完 onFontLoaded
    //     回调通知 CanvasView.requestDraw 重画一次切到真字形。
    const family = t.fontId;
    if (!isLoaded(family)) {
        ensureLoaded(family);
    }
    const fontSpec = `${t.fontSize}px "${family}"`;
    ctx.font = fontSpec;
    ctx.textBaseline = 'alphabetic';
    ctx.textAlign = 'left';

    // M28-enhance：渲染前先用 interpolator 解析 ${var:X}。layout 走替换后字符串，
    // 避免编辑器画布上长占位符（如 "${var:schedule/eta_minutes}"）撑爆 layout
    // 致使文字叠在一起。游戏内同源走后端 Compositor 已 interpolate；前端 hint 仅编辑期视觉提示。
    const { rendered, segments } = resolveTextForRender(t);
    // 用替换后的字符串临时构造 layout 输入；其他字段透传
    const layoutInput: TextElement = rendered === t.text ? t : { ...t, text: rendered };
    if (!layoutInput.text) return;

    const glyphs = layoutText(layoutInput);
    if (glyphs.length === 0) return;

    const fx = t.effects;
    const useNN = shouldUseNearestNeighbor(family);
    const nativeSize = useNN ? FONT_META[family].nativeSize : 0;
    const nativeSpec = useNN ? `${nativeSize}px "${family}"` : '';

    // 0. placeholder hint chip 背景（仅编辑器；segments 非空 → 有变量占位符）
    //    游戏内后端 Compositor 不走本路径，无 hint，保持双端像素一致
    if (segments.length > 0) {
        drawPlaceholderHints(ctx, glyphs, segments, t.fontSize);
    }

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
    // 0.4.6 P3：bold = 额外 stroke pass（color = text color，width = max(1.5, fontSize * 0.08)）。
    // 与 effects.stroke 独立可叠加；像素字体（NN 路径）跳过保持锐利。
    if (t.bold === true && !useNN) {
        const boldWidth = Math.max(1.5, t.fontSize * 0.08);
        ctx.strokeStyle = t.color;
        ctx.lineWidth = boldWidth;
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
 * M28-enhance：placeholder hint chip 风格背景。每个 segment 对应原 text 中的 ${var:X}
 * 替换后的字符 range；通过 glyphs[].srcIndex 反查命中字符 → 按行分组 → 画半透明矩形。
 *
 * <p>视觉决策：Catppuccin Mauve 0.20 alpha 填充 + 0.50 alpha 边框（极薄），让用户能在编辑器
 * 上区分"哪几个字是变量值"；hover tooltip 留 v1.x（Canvas 不易做 hover；需 DOM overlay）。</p>
 */
function drawPlaceholderHints(
    ctx: CanvasRenderingContext2D,
    glyphs: PositionedGlyph[],
    segments: PlaceholderSegment[],
    fontSize: number,
): void {
    if (segments.length === 0 || glyphs.length === 0) return;
    ctx.save();
    ctx.fillStyle = HINT_FILL;
    ctx.strokeStyle = HINT_BORDER;
    ctx.lineWidth = 1;
    const ascent = Math.round(fontSize * ASCENT_RATIO);
    const padX = 1;
    const padTop = 2;
    const padBot = 2;
    for (const seg of segments) {
        // 找命中本 seg 的 glyph 子集（按 srcIndex range）
        const segGlyphs = glyphs.filter(g =>
            g.srcIndex !== undefined && g.srcIndex >= seg.start && g.srcIndex < seg.end);
        if (segGlyphs.length === 0) continue;
        // 按行分组：同 baselineY 的 glyph 一组（一个 placeholder 可能 softWrap 跨多行）
        const byLine = new Map<number, PositionedGlyph[]>();
        for (const g of segGlyphs) {
            const arr = byLine.get(g.baselineY);
            if (arr) arr.push(g);
            else byLine.set(g.baselineY, [g]);
        }
        for (const [baselineY, lineGlyphs] of byLine) {
            // sort by x to compute left/right bounds
            lineGlyphs.sort((a, b) => a.x - b.x);
            const first = lineGlyphs[0];
            const last = lineGlyphs[lineGlyphs.length - 1];
            // 末尾字符宽度估算：本来该用 charAdvance，但 glyphs 不直接带 advance；
            // 取 next glyph in line if exists, else 用 fontSize 兜底
            const idx = glyphs.indexOf(last);
            let lastAdv = fontSize;
            if (idx >= 0 && idx + 1 < glyphs.length && glyphs[idx + 1].baselineY === baselineY) {
                lastAdv = glyphs[idx + 1].x - last.x;
            } else {
                // ASCII 半角宽度 fallback；CJK 走 fontSize
                const cp = last.ch.charCodeAt(0);
                lastAdv = cp < 0x2E80 ? Math.round(fontSize * 0.5) : fontSize;
            }
            const x0 = first.x - padX;
            const x1 = last.x + lastAdv + padX;
            const y0 = baselineY - ascent - padTop;
            const y1 = baselineY + (fontSize - ascent) + padBot;
            ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
            ctx.strokeRect(x0 + 0.5, y0 + 0.5, x1 - x0 - 1, y1 - y0 - 1);
        }
    }
    ctx.restore();
}

/**
 * M5-D6 Bug 7 终版：扫 mask 实际字形边界 + 手工 per-pixel NN。
 *
 * <p>根因：ark_pixel TTF 的 {@code advance('W')=6} 但 Chromium 画 W 字形时会把可见像素
 * 画到 0..11（超出 advance 的 side-bearing）。前几版用 {@code nativeChW=6} 作为采样宽
 * 只取字形左半 → W 显示为 V。改为：<br>
 * 1. mask 画到 {@code nativeSize×nativeSize} 全宽；<br>
 * 2. 扫非透明像素找最右边界 {@code actualW}（字形真实宽度）；<br>
 * 3. dst 宽 = {@code round(actualW * targetSize / nativeSize)}；<br>
 * 4. 手工 NN 填 dst（Chromium/AWT 的 drawImage NN 在分数比下都丢源像素列，必须自写循环）；<br>
 * 5. drawImage 1:1 到主 ctx 支持 alpha blend + rotation。<br>
 * 字形永远完整；layout cursor 仍按 canonicalCharWidth 推，字形会略溢出或欠宽 cell，
 * 但绝不破损。Java 侧同策略。</p>
 */
function drawPixelatedGlyph(ctx: CanvasRenderingContext2D, g: PositionedGlyph,
                            nativeSpec: string, targetSize: number, nativeSize: number,
                            color: string, dx: number, dy: number): void {
    const targetAscent = Math.round(targetSize * ASCENT_RATIO);
    const nativeAsc = Math.round(nativeSize * ASCENT_RATIO);

    // 1) mask 全宽
    const sub = document.createElement('canvas');
    sub.width = nativeSize;
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

    // 2) 扫字形实际宽度
    const maskImg = sg.getImageData(0, 0, nativeSize, nativeSize);
    let maxCol = -1;
    for (let y = 0; y < nativeSize; y++) {
        for (let x = 0; x < nativeSize; x++) {
            if (maskImg.data[(y * nativeSize + x) * 4 + 3] > 0 && x > maxCol) maxCol = x;
        }
    }
    const actualW = Math.max(1, maxCol + 1);

    // 3) dst 尺寸：等比放大 actualW
    const dstW = Math.max(1, Math.round(actualW * targetSize / nativeSize));
    const dstH = targetSize;

    // 4) 手工 NN 填 dst（只采样 mask 左 actualW 列）
    const out = document.createElement('canvas');
    out.width = dstW;
    out.height = dstH;
    const og = out.getContext('2d');
    if (!og) return;
    const dstImg = og.createImageData(dstW, dstH);
    for (let ty = 0; ty < dstH; ty++) {
        const sy = Math.min(nativeSize - 1, Math.floor(ty * nativeSize / dstH));
        const srcRowBase = sy * nativeSize * 4;
        for (let tx = 0; tx < dstW; tx++) {
            const sx = Math.min(actualW - 1, Math.floor(tx * actualW / dstW));
            const si = srcRowBase + sx * 4;
            const di = (ty * dstW + tx) * 4;
            dstImg.data[di]     = maskImg.data[si];
            dstImg.data[di + 1] = maskImg.data[si + 1];
            dstImg.data[di + 2] = maskImg.data[si + 2];
            dstImg.data[di + 3] = maskImg.data[si + 3];
        }
    }
    og.putImageData(dstImg, 0, 0);

    // 5) 贴到主画布
    if (g.rotated) {
        ctx.save();
        ctx.translate(g.x + dx, g.baselineY + dy);
        ctx.rotate(Math.PI / 2);
        ctx.drawImage(out, -dstW / 2, targetAscent - targetSize / 2);
        ctx.restore();
    } else {
        ctx.drawImage(out, g.x + dx, g.baselineY + dy - targetAscent);
    }
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

