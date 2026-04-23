import type { ProjectState, Element, RectElement, TextElement } from '@/types/protocol';

/**
 * 前端 Canvas 2D 预览渲染器。
 *
 * <p><b>M5-B1 临时版：</b> 只做 rect（fill + stroke）与 text（单行 fillText）的视觉
 * baseline；正式的前后端像素级一致实现留 M5-C（届时对齐 Java {@code CanvasCompositor}：
 * 调色板量化、完整 TextLayout 镜像、effects、像素字体最近邻缩放、woff2 字体加载等）。</p>
 *
 * <p><b>坐标系：</b> Canvas 尺寸 = widthMaps×128 × heightMaps×128，1 像素 = 1 MC 地图像素。
 * 缩放由外层 CSS transform 处理（zoom），此处始终按原始像素绘制。</p>
 *
 * <p><b>不作为：</b> 调色板量化（M5-C 和 palette.json 同步）。现在是 CSS 颜色直通，
 * 浏览器显示可能比游戏内精准一点（MC 256 色会 snap 到最近色）。</p>
 */
export function renderProjectState(ctx: CanvasRenderingContext2D, state: ProjectState | null): void {
    const widthPx = (state?.canvas.widthMaps ?? 1) * 128;
    const heightPx = (state?.canvas.heightMaps ?? 1) * 128;

    // 背景
    ctx.fillStyle = state?.canvas.background ?? '#FFFFFF';
    ctx.fillRect(0, 0, widthPx, heightPx);

    if (!state) return;

    // 关抗锯齿（rendering.md §4.3）；字体渲染用 geometricPrecision
    ctx.imageSmoothingEnabled = false;
    // textRendering 是较新的 Canvas 属性，Chromium 支持；TS 签名可能没；ts-expect-error 包住
    // @ts-expect-error non-standard but supported in Chromium
    ctx.textRendering = 'geometricPrecision';

    for (const e of state.elements) {
        if (!e.visible) continue;
        drawElement(ctx, e);
    }
}

function drawElement(ctx: CanvasRenderingContext2D, e: Element): void {
    ctx.save();
    // rotation 绕 bbox 中心转（与 Java CanvasCompositor 一致）
    if (e.rotation !== 0) {
        const cx = e.x + e.w / 2;
        const cy = e.y + e.h / 2;
        ctx.translate(cx, cy);
        ctx.rotate((e.rotation * Math.PI) / 180);
        ctx.translate(-cx, -cy);
    }
    if (e.type === 'rect') {
        drawRect(ctx, e);
    } else if (e.type === 'text') {
        drawText(ctx, e);
    }
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
        // 四 fillRect 画整数像素边框（与后端 CanvasCompositor.drawRect 同策略）
        ctx.fillRect(r.x, r.y, r.w, sw);
        ctx.fillRect(r.x, r.y + r.h - sw, r.w, sw);
        ctx.fillRect(r.x, r.y, sw, r.h);
        ctx.fillRect(r.x + r.w - sw, r.y, sw, r.h);
    }
}

function drawText(ctx: CanvasRenderingContext2D, t: TextElement): void {
    if (!t.text) return;
    // M5-C1：fontId 直接作 CSS font-family（style.css 已 @font-face 声明
    // 'ark_pixel' / 'source_han_sans'），与后端 FontRegistry 用同一 TTF/OTF 原件
    const family = fontFamily(t.fontId);
    ctx.font = `${t.fontSize}px "${family}"`;
    ctx.fillStyle = t.color;
    ctx.textBaseline = 'top';
    ctx.textAlign = t.align === 'center' ? 'center' : t.align === 'right' ? 'right' : 'left';

    // align 按元素 w 做偏移
    let x = t.x;
    if (t.align === 'center') x = t.x + t.w / 2;
    else if (t.align === 'right') x = t.x + t.w;

    // M5-B1 简化：单行 fillText；M5-C3 会换成 TextLayout 镜像（hard/soft wrap + 禁则 +
    // letterSpacing + 基线 0.8 + 竖排）
    ctx.fillText(t.text, x, t.y);
}

function fontFamily(fontId: string): string {
    // 未知 fontId → fallback 到默认（与后端 FontRegistry.DEFAULT_FONT_ID 一致）
    const KNOWN = new Set(['ark_pixel', 'source_han_sans']);
    return KNOWN.has(fontId) ? fontId : 'ark_pixel';
}
