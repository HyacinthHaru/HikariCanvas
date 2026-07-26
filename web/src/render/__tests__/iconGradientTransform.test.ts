/**
 * SVG 图标的渐变填充不能被图标自身的缩放平移再映射一遍。
 *
 * <p>渐变端点是在 fill() 那一刻按当前变换矩阵映射的，不是设 fillStyle 时定死的。图标绘制会先
 * translate + scale 到 viewBox 空间，所以渐变必须用**变换后坐标系**里的 element bbox。之前用的是
 * 全局坐标，端点被这层变换又映射了一次 → 编辑器里渐变整个错位；后端 IconRenderer 用
 * createTransformedShape 不动 Graphics2D 变换，Paint 坐标本就是画布坐标，画出来是对的。
 * 也就是说这条是编辑器画错、游戏内画对，双端对不上。</p>
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { drawElement } from '../PreviewRenderer';
import { ensureLoaded as ensureIconLoaded } from '../IconLoader';
import type { IconElement, LinearGradient } from '@/types/protocol';

// ---------- 假 ctx：只实现图标路径用到的那几个方法，并记录变换矩阵 ----------

interface Ctm { tx: number; ty: number; sx: number; sy: number }

class FakeGradient {
    constructor(readonly coords: [number, number, number, number]) {}
    addColorStop(): void {}
}

class FakeCtx {
    ctm: Ctm = { tx: 0, ty: 0, sx: 1, sy: 1 };
    private stack: Ctm[] = [];
    globalAlpha = 1;
    fillStyle: unknown = '';
    /** 每次 fill() 记一条：当时的 fillStyle + 当时的变换矩阵。 */
    readonly fills: { style: unknown; ctm: Ctm }[] = [];

    save(): void { this.stack.push({ ...this.ctm }); }
    restore(): void { const s = this.stack.pop(); if (s) this.ctm = s; }
    translate(x: number, y: number): void {
        this.ctm.tx += this.ctm.sx * x;
        this.ctm.ty += this.ctm.sy * y;
    }
    scale(x: number, y: number): void { this.ctm.sx *= x; this.ctm.sy *= y; }
    rotate(): void {}
    createLinearGradient(x0: number, y0: number, x1: number, y1: number): FakeGradient {
        return new FakeGradient([x0, y0, x1, y1]);
    }
    fill(): void { this.fills.push({ style: this.fillStyle, ctm: { ...this.ctm } }); }
    // 占位分支（图标加载失败时的占位框）用得到，这里不该被走到
    strokeRect(): void { throw new Error('不该走占位分支'); }
    fillText(): void {}
    beginPath(): void {}
    closePath(): void {}
    moveTo(): void {}
    lineTo(): void {}
    stroke(): void {}
    setLineDash(): void {}
}

function toCanvas(ctm: Ctm, x: number, y: number): [number, number] {
    return [ctm.tx + ctm.sx * x, ctm.ty + ctm.sy * y];
}

/**
 * 后端 / Rect / Circle 那条通用规则：渐变端点由 bbox 四角在方向向量上的投影极值决定
 * （镜像 fill.ts 的 buildLinearGradient，这里直接给画布坐标系的 bbox）。
 */
function expectedEndpointsInCanvasSpace(
    angleDeg: number, bx: number, by: number, bw: number, bh: number,
): [number, number, number, number] {
    const rad = (angleDeg * Math.PI) / 180;
    const dx = Math.cos(rad);
    const dy = Math.sin(rad);
    const cx = bx + bw / 2;
    const cy = by + bh / 2;
    let minP = Infinity, maxP = -Infinity;
    for (const [px, py] of [[bx, by], [bx + bw, by], [bx, by + bh], [bx + bw, by + bh]]) {
        const p = (px - cx) * dx + (py - cy) * dy;
        if (p < minP) minP = p;
        if (p > maxP) maxP = p;
    }
    return [cx + dx * minP, cy + dy * minP, cx + dx * maxP, cy + dy * maxP];
}

const GRADIENT: LinearGradient = {
    type: 'linear',
    angle: 0,
    stops: [
        { position: 0, color: '#FF0000' },
        { position: 1, color: '#0000FF' },
    ],
};

function iconEl(source: string, over: Partial<IconElement> = {}): IconElement {
    return {
        type: 'icon',
        id: 'i-1',
        x: 100, y: 50, w: 64, h: 64,
        rotation: 0, locked: false, visible: true,
        source,
        fill: GRADIENT,
        ...over,
    } as IconElement;
}

async function primeIcon(id: string, viewBox: string): Promise<void> {
    const fetchMock = vi.fn(async () => ({
        ok: true,
        json: async () => ({ viewBox, paths: [{ d: 'M0 0 L10 10 Z' }] }),
    }));
    vi.stubGlobal('fetch', fetchMock);
    try {
        await ensureIconLoaded(id);
    } finally {
        vi.unstubAllGlobals();
    }
}

describe('drawIconSvgPath：渐变端点落在 element bbox 上', () => {
    beforeEach(() => {
        // 直接挂 globalThis（不用 stubGlobal）—— primeIcon 里的 unstubAllGlobals 会把它一起撤掉
        (globalThis as unknown as { Path2D: unknown }).Path2D =
            class { constructor(_d: string) {} };
    });

    it('方形 viewBox：端点经变换后正好横跨 element bbox', async () => {
        const id = 'fa-solid/heart';
        await primeIcon(id, '0 0 512 512');

        const ctx = new FakeCtx();
        const el = iconEl(id);
        drawElement(ctx as unknown as CanvasRenderingContext2D, el, 256, 128);

        expect(ctx.fills.length).toBeGreaterThan(0);
        const { style, ctm } = ctx.fills[0];
        expect(style).toBeInstanceOf(FakeGradient);
        const [x0, y0, x1, y1] = (style as FakeGradient).coords;
        const [cx0, cy0] = toCanvas(ctm, x0, y0);
        const [cx1, cy1] = toCanvas(ctm, x1, y1);

        const [ex0, ey0, ex1, ey1] =
            expectedEndpointsInCanvasSpace(GRADIENT.angle, el.x, el.y, el.w, el.h);
        expect(cx0).toBeCloseTo(ex0, 6);
        expect(cy0).toBeCloseTo(ey0, 6);
        expect(cx1).toBeCloseTo(ex1, 6);
        expect(cy1).toBeCloseTo(ey1, 6);
    });

    it('非方形 viewBox（有居中偏移）同样对齐', async () => {
        const id = 'fa-solid/wide';
        await primeIcon(id, '0 0 640 512');

        const ctx = new FakeCtx();
        const el = iconEl(id, { fill: { ...GRADIENT, angle: 90 } });
        drawElement(ctx as unknown as CanvasRenderingContext2D, el, 256, 128);

        const { style, ctm } = ctx.fills[0];
        const [x0, y0, x1, y1] = (style as FakeGradient).coords;
        const [cx0, cy0] = toCanvas(ctm, x0, y0);
        const [cx1, cy1] = toCanvas(ctm, x1, y1);

        const [ex0, ey0, ex1, ey1] =
            expectedEndpointsInCanvasSpace(90, el.x, el.y, el.w, el.h);
        expect(cx0).toBeCloseTo(ex0, 6);
        expect(cy0).toBeCloseTo(ey0, 6);
        expect(cx1).toBeCloseTo(ex1, 6);
        expect(cy1).toBeCloseTo(ey1, 6);
    });

    it('viewBox 带非零原点（minX/minY）也对齐', async () => {
        const id = 'fa-solid/offset';
        await primeIcon(id, '32 16 512 512');

        const ctx = new FakeCtx();
        const el = iconEl(id);
        drawElement(ctx as unknown as CanvasRenderingContext2D, el, 256, 128);

        const { style, ctm } = ctx.fills[0];
        const [x0, y0, x1, y1] = (style as FakeGradient).coords;
        const [cx0, cy0] = toCanvas(ctm, x0, y0);
        const [cx1, cy1] = toCanvas(ctm, x1, y1);

        const [ex0, ey0, ex1, ey1] =
            expectedEndpointsInCanvasSpace(GRADIENT.angle, el.x, el.y, el.w, el.h);
        expect(cx0).toBeCloseTo(ex0, 6);
        expect(cy0).toBeCloseTo(ey0, 6);
        expect(cx1).toBeCloseTo(ex1, 6);
        expect(cy1).toBeCloseTo(ey1, 6);
    });
});
