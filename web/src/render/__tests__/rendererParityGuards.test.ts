// @vitest-environment happy-dom
/**
 * 渲染层双端一致性守卫（都直接跑 PreviewRenderer 真实代码，不复刻公式）。
 *
 * 覆盖三条曾经分叉的路径：
 * 1. 矩形描边宽度钳制 —— 后端 Java int 除法向零截断，前端曾用浮点除，极小矩形差半像素
 * 2. dither 主路径的 opacity 守卫 —— 前端曾漏 isFinite/clamp，负 opacity 在前端全亮、后端隐形
 * 3. 字体兜底 —— 后端 getOrDefault 退到 ark_pixel，前端曾没有字体栈、直接落浏览器默认矢量字体
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Element, RectElement } from '@/types/protocol';

// PaletteLut / BayerDither 都换成假的：本文件只关心 drawDitheredElement 走到主路径之后
// 对 globalAlpha 的处理，不关心抖动像素本身。
vi.mock('../PaletteLut', () => ({
    getPaletteLut: () => Promise.resolve({ fake: true }),
}));
vi.mock('../BayerDither', () => ({
    applyBayerDither: vi.fn(),
}));

interface RecordingCtx {
    ctx: CanvasRenderingContext2D;
    fillRects: number[][];
    alphaWrites: number[];
}

/** 主画布替身：记录 fillRect 参数与每次 globalAlpha 赋值。 */
function recordingCtx(): RecordingCtx {
    const fillRects: number[][] = [];
    const alphaWrites: number[] = [];
    let alpha = 1;
    const ctx = {
        save: vi.fn(),
        restore: vi.fn(),
        translate: vi.fn(),
        rotate: vi.fn(),
        fillRect: (x: number, y: number, w: number, h: number) => { fillRects.push([x, y, w, h]); },
        drawImage: vi.fn(),
        set fillStyle(_v: unknown) {},
        set strokeStyle(_v: unknown) {},
        set globalAlpha(v: number) { alpha = v; alphaWrites.push(v); },
        get globalAlpha() { return alpha; },
    } as unknown as CanvasRenderingContext2D;
    return { ctx, fillRects, alphaWrites };
}

/** off-screen canvas 替身：happy-dom 的 canvas.getContext 返 null，会让 dither 主路径提前 return。 */
function stubOffscreenCanvas(): void {
    const realCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
        if (tag !== 'canvas') return realCreate(tag);
        const og = {
            imageSmoothingEnabled: false,
            translate: vi.fn(),
            rotate: vi.fn(),
            fillRect: vi.fn(),
            drawImage: vi.fn(),
            getImageData: () => ({ data: new Uint8ClampedArray(4), width: 1, height: 1 }),
            putImageData: vi.fn(),
            set fillStyle(_v: unknown) {},
            set strokeStyle(_v: unknown) {},
            set globalAlpha(_v: unknown) {},
            get globalAlpha() { return 1; },
        };
        return { width: 0, height: 0, getContext: () => og } as unknown as HTMLCanvasElement;
    }) as typeof document.createElement);
}

async function importRenderer() {
    return import('../PreviewRenderer');
}

function rect(overrides: Partial<RectElement>): RectElement {
    return {
        id: 'r1', type: 'rect', x: 0, y: 0, w: 9, h: 9, rotation: 0,
        locked: false, visible: true,
        ...overrides,
    } as RectElement;
}

beforeEach(() => {
    vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// 1. 描边宽度钳制：向零截断，对齐后端 RectRenderer 的 int 除法
// ---------------------------------------------------------------------------

describe('drawRect 描边宽度钳制与后端 int 除法一致', () => {
    /** 后端 RectRenderer：sw = min(stroke.width, max(1, min(w,h) / 2))，Java int 除法向零截断。 */
    function backendStrokeWidth(w: number, h: number, strokeWidth: number): number {
        return Math.min(strokeWidth, Math.max(1, Math.trunc(Math.min(w, h) / 2)));
    }

    // 只有 min(w,h) 为奇数「且」描边粗过半个盒子时才会走到钳制分支
    const cases: Array<[number, number, number]> = [
        [9, 9, 8],
        [9, 15, 20],
        [7, 7, 4],
        [3, 5, 3],
        [1, 1, 2],
        [20, 20, 4],   // 不触发钳制的常规值，确保没改坏
    ];

    for (const [w, h, sw] of cases) {
        it(`w=${w} h=${h} strokeWidth=${sw}`, async () => {
            const { drawElement } = await importRenderer();
            const rec = recordingCtx();
            drawElement(rec.ctx, rect({ w, h, stroke: { color: '#000000', width: sw } }) as Element, 100, 100);
            // 4 条边框各一个 fillRect：上边框高度 = 实际用的描边宽度
            expect(rec.fillRects.length).toBe(4);
            const applied = rec.fillRects[0][3];
            expect(applied).toBe(backendStrokeWidth(w, h, sw));
            expect(Number.isInteger(applied)).toBe(true);
        });
    }
});

// ---------------------------------------------------------------------------
// 2. dither 主路径 opacity 守卫
// ---------------------------------------------------------------------------

describe('drawDitheredElement 主路径的 opacity 守卫（对齐后端 finiteOr + clamp）', () => {
    async function alphaForOpacity(opacity: number): Promise<number[]> {
        const mod = await importRenderer();
        // 先触发一次 palette 懒加载并等它 resolve，之后 drawElement 才会走 dither 主路径
        mod.resetImageCaches?.();
        stubOffscreenCanvas();
        const warm = recordingCtx();
        mod.drawElement(warm.ctx, rect({ renderMode: 'dither' }) as Element, 100, 100);
        await Promise.resolve();
        await Promise.resolve();

        const rec = recordingCtx();
        mod.drawElement(
            rec.ctx,
            rect({ renderMode: 'dither', fill: { type: 'solid', color: '#ff0000' }, opacity }) as Element,
            100, 100,
        );
        return rec.alphaWrites;
    }

    it('负 opacity 被 clamp 到 0（修前是 -0.5，HTML 规范直接忽略 → 元素全亮）', async () => {
        const writes = await alphaForOpacity(-0.5);
        expect(writes.length).toBeGreaterThan(0);
        expect(writes.every((v) => v >= 0 && v <= 1)).toBe(true);
        expect(writes[0]).toBe(0);
    });

    it('NaN opacity 兜底为 1（不改变可见度）', async () => {
        const writes = await alphaForOpacity(NaN);
        expect(writes.every((v) => Number.isFinite(v))).toBe(true);
        expect(writes[0]).toBe(1);
    });

    it('正常 opacity=0.25 原样生效', async () => {
        const writes = await alphaForOpacity(0.25);
        expect(writes[0]).toBe(0.25);
    });
});

// ---------------------------------------------------------------------------
// 3. 字体兜底栈
// ---------------------------------------------------------------------------

describe('fontStackSpec：缺字体时退到后端同一枚兜底字体', () => {
    it('普通字体拼出两级字体栈', async () => {
        const { fontStackSpec, FALLBACK_FONT_ID } = await importRenderer();
        expect(fontStackSpec('source_han_sans', 16))
            .toBe(`16px "source_han_sans", "${FALLBACK_FONT_ID}"`);
    });

    it('本身就是兜底字体时不重复自己', async () => {
        const { fontStackSpec, FALLBACK_FONT_ID } = await importRenderer();
        expect(fontStackSpec(FALLBACK_FONT_ID, 12)).toBe(`12px "${FALLBACK_FONT_ID}"`);
    });

    it('本服没有的字体（跨服 pack / 服主删掉的用户字体）也带兜底项', async () => {
        const { fontStackSpec, FALLBACK_FONT_ID } = await importRenderer();
        const spec = fontStackSpec('some_missing_user_font', 24);
        expect(spec).toContain('"some_missing_user_font"');
        expect(spec).toContain(`"${FALLBACK_FONT_ID}"`);
    });

    it('兜底字体 id 与后端 FontRegistry.DEFAULT_FONT_ID 一致', async () => {
        const { FALLBACK_FONT_ID, FONT_META } = await importRenderer();
        expect(FALLBACK_FONT_ID).toBe('ark_pixel');
        // 兜底字体必须在 FONT_META 里有元数据，否则 NN 判定拿不到 nativeSize
        expect(FONT_META[FALLBACK_FONT_ID]?.pixelated).toBe(true);
    });
});
