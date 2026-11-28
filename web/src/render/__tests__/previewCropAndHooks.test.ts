// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { Element, IconElement, ImageElement, RectElement, TextElement } from '@/types/protocol';

const paletteResolvers: Array<(v: { fake: boolean }) => void> = [];

vi.mock('../PaletteLut', () => ({
    getPaletteLut: () => new Promise<{ fake: boolean }>((resolve) => {
        paletteResolvers.push(resolve);
    }),
}));

vi.mock('../BayerDither', () => ({
    applyBayerDither: vi.fn(),
}));

interface Matrix2D {
    a: number;
    b: number;
    c: number;
    d: number;
    e: number;
    f: number;
}

function mulMatrix(m1: Matrix2D, m2: Matrix2D): Matrix2D {
    return {
        a: m1.a * m2.a + m1.c * m2.b,
        b: m1.b * m2.a + m1.d * m2.b,
        c: m1.a * m2.c + m1.c * m2.d,
        d: m1.b * m2.c + m1.d * m2.d,
        e: m1.a * m2.e + m1.c * m2.f + m1.e,
        f: m1.b * m2.e + m1.d * m2.f + m1.f,
    };
}

/** 带真实 2D 仿射变换栈与 canvas 尺寸的主画布替身。 */
function makeTransformAwareCtx(cw: number, ch: number) {
    let current: Matrix2D = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 };
    const stack: Matrix2D[] = [];
    const drawImageCalls: Array<{ src: unknown; args: number[] }> = [];
    const fillTextCalls: Array<{ text: string; x: number; y: number }> = [];
    const fillRectCalls: number[][] = [];
    const clipCalls: number[] = [];

    const ctx = {
        canvas: { width: cw, height: ch },
        save: () => { stack.push({ ...current }); },
        restore: () => {
            const top = stack.pop();
            if (top) current = top;
        },
        translate: (tx: number, ty: number) => {
            current = mulMatrix(current, { a: 1, b: 0, c: 0, d: 1, e: tx, f: ty });
        },
        rotate: (rad: number) => {
            const cos = Math.cos(rad);
            const sin = Math.sin(rad);
            current = mulMatrix(current, { a: cos, b: sin, c: -sin, d: cos, e: 0, f: 0 });
        },
        scale: (sx: number, sy: number) => {
            current = mulMatrix(current, { a: sx, b: 0, c: 0, d: sy, e: 0, f: 0 });
        },
        transform: (a: number, b: number, c: number, d: number, e: number, f: number) => {
            current = mulMatrix(current, { a, b, c, d, e, f });
        },
        getTransform: () => ({ ...current } as DOMMatrix),
        fillRect: (x: number, y: number, w: number, h: number) => { fillRectCalls.push([x, y, w, h]); },
        strokeRect: vi.fn(),
        setLineDash: vi.fn(),
        drawImage: (src: unknown, ...args: number[]) => { drawImageCalls.push({ src, args }); },
        fillText: (text: string, x: number, y: number) => { fillTextCalls.push({ text, x, y }); },
        strokeText: vi.fn(),
        measureText: (s: string) => ({ width: s.length * 8 }),
        clip: () => { clipCalls.push(1); },
        beginPath: vi.fn(),
        globalAlpha: 1,
        fillStyle: '#000000',
        strokeStyle: '#000000',
        font: '16px sans-serif',
        textAlign: 'left',
        textBaseline: 'alphabetic',
        imageSmoothingEnabled: false,
        textRendering: 'geometricPrecision',
    } as unknown as CanvasRenderingContext2D;

    return { ctx, drawImageCalls, fillTextCalls, fillRectCalls, clipCalls, setMatrix: (m: Matrix2D) => { current = m; } };
}

interface OffscreenRecord {
    width: number;
    height: number;
    translates: Array<[number, number]>;
}

function stubOffscreenCanvases(): OffscreenRecord[] {
    const records: OffscreenRecord[] = [];
    const realCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
        if (tag !== 'canvas') return realCreate(tag);
        const rec: OffscreenRecord = { width: 0, height: 0, translates: [] };
        records.push(rec);
        const og = {
            imageSmoothingEnabled: false,
            textRendering: 'geometricPrecision',
            font: '',
            fillStyle: '#ffffff',
            textBaseline: 'alphabetic',
            filter: 'none',
            globalCompositeOperation: 'source-over',
            save: vi.fn(),
            restore: vi.fn(),
            translate: (tx: number, ty: number) => { rec.translates.push([tx, ty]); },
            rotate: vi.fn(),
            fillText: vi.fn(),
            measureText: () => ({ width: 8 }),
            drawImage: vi.fn(),
            fillRect: vi.fn(),
            clearRect: vi.fn(),
            fill: vi.fn(),
            getImageData: (_x: number, _y: number, w: number, h: number) => ({
                data: new Uint8ClampedArray(Math.min(w * h * 4, 65536)),
                width: w,
                height: h,
            }),
            putImageData: vi.fn(),
        };
        const fakeCanvas = {
            get width() { return rec.width; },
            set width(v: number) { rec.width = v; },
            get height() { return rec.height; },
            set height(v: number) { rec.height = v; },
            getContext: () => og,
        };
        return fakeCanvas as unknown as HTMLCanvasElement;
    }) as typeof document.createElement);
    return records;
}

class ControllableImage {
    static instances: ControllableImage[] = [];
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    private _src = '';

    constructor() {
        ControllableImage.instances.push(this);
    }

    get src(): string { return this._src; }
    set src(v: string) { this._src = v; }
}

class MockPath2D {
    moveTo(): void {}
    lineTo(): void {}
    quadraticCurveTo(): void {}
    bezierCurveTo(): void {}
    closePath(): void {}
    rect(): void {}
    addPath(): void {}
}

beforeEach(() => {
    paletteResolvers.length = 0;
    ControllableImage.instances.length = 0;
    vi.resetModules();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    if (typeof globalThis.Path2D === 'undefined') {
        vi.stubGlobal('Path2D', MockPath2D);
    }
    vi.stubGlobal('FontFace', class {
        status = 'loaded';
        load(): Promise<unknown> { return Promise.resolve(this); }
    });
    Object.defineProperty(document, 'fonts', {
        value: { add: () => {} },
        configurable: true,
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

describe('drawRect / drawImage / drawIcon 尺寸守卫', () => {
    it('drawRect 在 w <= 0 或 h <= 0 时直接跳过', async () => {
        const { drawElement } = await import('../PreviewRenderer');
        const { ctx, fillRectCalls } = makeTransformAwareCtx(128, 128);

        for (const [w, h] of [[0, 10], [-5, 10], [10, 0], [10, -3]]) {
            const el: RectElement = {
                id: 'r', type: 'rect', x: 10, y: 10, w, h, rotation: 0,
                locked: false, visible: true,
                fill: { type: 'solid', color: '#ff0000' },
                stroke: { color: '#000000', width: 2 },
            };
            drawElement(ctx, el, 128, 128);
        }
        expect(fillRectCalls).toHaveLength(0);
    });

    it('drawImage 在 w <= 0 或 h <= 0 时直接跳过且不发起图片加载', async () => {
        vi.stubGlobal('Image', ControllableImage);
        const { drawElement } = await import('../PreviewRenderer');
        const { ctx, drawImageCalls } = makeTransformAwareCtx(128, 128);

        for (const [w, h] of [[0, 20], [-1, 20], [20, 0], [20, -8]]) {
            const im: ImageElement = {
                id: 'im', type: 'image', x: 0, y: 0, w, h, rotation: 0,
                locked: false, visible: true, source: 'abcdef0123456789',
            };
            drawElement(ctx, im, 128, 128);
        }
        expect(drawImageCalls).toHaveLength(0);
        expect(ControllableImage.instances).toHaveLength(0);
    });

    it('drawIcon 在 w/h <= 0 或超过 10000 时直接跳过', async () => {
        const fetchSpy = vi.fn(async () => ({ ok: false }));
        vi.stubGlobal('fetch', fetchSpy);
        const { drawElement } = await import('../PreviewRenderer');
        const { ctx, fillTextCalls } = makeTransformAwareCtx(128, 128);

        for (const [w, h] of [[0, 32], [32, -1], [10_001, 32], [32, 10_001]]) {
            const ic: IconElement = {
                id: 'ic', type: 'icon', x: 0, y: 0, w, h, rotation: 0,
                locked: false, visible: true, source: 'fa-solid/star',
            };
            drawElement(ctx, ic, 128, 128);
        }
        expect(fillTextCalls).toHaveLength(0);
        expect(fetchSpy).not.toHaveBeenCalled();
    });
});

describe('visibleBoundsInUserSpace', () => {
    it('轻量级 mock ctx（无 canvas 或无 getTransform）安全返回 null', async () => {
        const { visibleBoundsInUserSpace } = await import('../PreviewRenderer');
        expect(visibleBoundsInUserSpace({} as CanvasRenderingContext2D)).toBeNull();
        expect(visibleBoundsInUserSpace({ canvas: { width: 100, height: 100 } } as CanvasRenderingContext2D)).toBeNull();
    });

    it('0 尺寸画布返回空框，奇异矩阵返回 null，平移与旋转正确反算用户空间 AABB', async () => {
        const { visibleBoundsInUserSpace } = await import('../PreviewRenderer');

        const zero = makeTransformAwareCtx(0, 128);
        expect(visibleBoundsInUserSpace(zero.ctx)).toEqual({ x: 0, y: 0, w: 0, h: 0 });

        const singular = makeTransformAwareCtx(128, 128);
        singular.setMatrix({ a: 0, b: 0, c: 0, d: 0, e: 0, f: 0 });
        expect(visibleBoundsInUserSpace(singular.ctx)).toBeNull();

        const translated = makeTransformAwareCtx(128, 64);
        translated.ctx.translate(-20, -10);
        expect(visibleBoundsInUserSpace(translated.ctx)).toEqual({ x: 20, y: 10, w: 128, h: 64 });

        const rotated = makeTransformAwareCtx(100, 100);
        rotated.ctx.translate(50, 50);
        rotated.ctx.rotate(Math.PI / 4);
        rotated.ctx.translate(-50, -50);
        // 100×100 绕中心 (50,50) 旋 45°：半对角线 = 50*√2 ≈ 70.71 → 范围 [-20.71, 120.71] → floor=-21, ceil=121, w=h=142
        expect(visibleBoundsInUserSpace(rotated.ctx)).toEqual({ x: -21, y: -21, w: 142, h: 142 });
    });
});

describe('renderGlow 裁剪与像素上限', () => {
    function makeGlowText(over: Partial<TextElement> = {}): TextElement {
        return {
            id: 't1',
            type: 'text',
            x: 0,
            y: 0,
            w: 400,
            h: 200,
            rotation: 0,
            locked: false,
            visible: true,
            text: 'HELLO WORLD',
            fontId: 'inter',
            fontSize: 32,
            color: '#ffffff',
            align: 'left',
            letterSpacing: 0,
            lineHeight: 1.2,
            vertical: false,
            effects: { glow: { color: '#ff00ff', radius: 4 } },
            ...over,
        };
    }

    it('旋转与半出界文本的发光离屏画布按可视区外扩 pad 裁剪，并平移 (-cropX, -cropY)', async () => {
        const offscreens = stubOffscreenCanvases();
        const { drawElement } = await import('../PreviewRenderer');
        const { ctx, drawImageCalls, fillTextCalls } = makeTransformAwareCtx(128, 128);

        // 旋转中心在 (64, 64) 画布中央，单行 60 字从 x=-236 铺到 x=724（总宽 ~960px），
        // 经 128×128 旋转 30° 的可视 AABB 裁剪后离屏宽度远小于 960px
        drawElement(ctx, makeGlowText({
            x: -236,
            y: 44,
            w: 600,
            h: 40,
            text: 'A'.repeat(60),
            rotation: 30,
        }), 128, 128);

        expect(offscreens).toHaveLength(1);
        expect(offscreens[0].width).toBeLessThan(250);
        expect(offscreens[0].translates.length).toBeGreaterThanOrEqual(1);
        expect(drawImageCalls).toHaveLength(1);
        expect(fillTextCalls.length).toBeGreaterThan(0);
    });

    it('完全位于可视区外的文本跳过发光离屏分配', async () => {
        const offscreens = stubOffscreenCanvases();
        const { drawElement } = await import('../PreviewRenderer');
        const { ctx } = makeTransformAwareCtx(128, 128);

        drawElement(ctx, makeGlowText({ x: 500, y: 500, text: 'OUT' }), 128, 128);
        expect(offscreens).toHaveLength(0);
    });

    it('超出 MAX_GLOW_PIXELS (20M) 时安全跳过发光，但主字形照常绘制', async () => {
        const offscreens = stubOffscreenCanvases();
        const { drawElement } = await import('../PreviewRenderer');
        const { fillTextCalls } = makeTransformAwareCtx(128, 128);
        // 用无 canvas 的 mock ctx 模拟无可视裁剪上界，让超大字形盒直撞 MAX_GLOW_PIXELS 闸门
        const bareCtx = {
            save: vi.fn(),
            restore: vi.fn(),
            translate: vi.fn(),
            rotate: vi.fn(),
            fillText: (text: string, x: number, y: number) => { fillTextCalls.push({ text, x, y }); },
            drawImage: vi.fn(),
            measureText: () => ({ width: 200 }),
            globalAlpha: 1,
        } as unknown as CanvasRenderingContext2D;

        const lines = Array.from({ length: 20 }, () => '中'.repeat(20)).join('\n');
        drawElement(bareCtx, makeGlowText({
            fontSize: 300,
            letterSpacing: 0,
            lineHeight: 1.5,
            w: 10000,
            h: 10000,
            text: lines,
        }), 128, 128);

        // 20*300 = 6000 宽 × ~9000 高 ≈ 54M > 20M：不分配离屏发光画布，但字形仍画
        expect(offscreens).toHaveLength(0);
        expect(fillTextCalls.length).toBeGreaterThan(0);
    });
});

describe('drawImageWithFeather 裁剪与硬边降级', () => {
    async function loadReadyImage(mod: typeof import('../PreviewRenderer'), source: string): Promise<void> {
        mod.preloadImage(source, 'data:image/png;base64,AAAA');
        const inst = ControllableImage.instances[ControllableImage.instances.length - 1];
        inst.onload?.();
    }

    it('旋转大图羽化时按可视区裁剪离屏画布，并在 (im.x + cropX, im.y + cropY) 贴回', async () => {
        vi.stubGlobal('Image', ControllableImage);
        const offscreens = stubOffscreenCanvases();
        const mod = await import('../PreviewRenderer');
        await loadReadyImage(mod, 'feathered_big');

        const { ctx, drawImageCalls } = makeTransformAwareCtx(128, 128);
        const im: ImageElement = {
            id: 'im1',
            type: 'image',
            x: -400,
            y: -400,
            w: 2000,
            h: 2000,
            rotation: 30,
            locked: false,
            visible: true,
            source: 'feathered_big',
            mask: { d: 'M0 0 L2000 0 L2000 2000 L0 2000 Z', inverted: false, featherPx: 4 },
        };
        mod.drawElement(ctx, im, 128, 128);

        // imgCanvas + maskCanvas + blurCanvas 各一张，尺寸均远小于 2000×2000
        expect(offscreens.length).toBe(3);
        for (const c of offscreens) {
            expect(c.width).toBeGreaterThan(0);
            expect(c.width).toBeLessThan(300);
            expect(c.height).toBeGreaterThan(0);
            expect(c.height).toBeLessThan(300);
        }
        expect(offscreens[0].translates[0][0]).toBeLessThan(0);
        expect(offscreens[1].translates[0][0]).toBeLessThan(0);
        expect(drawImageCalls).toHaveLength(1);
    });

    it('im.w > 10000 时不消失，而是降级走硬边 clip + drawImage', async () => {
        vi.stubGlobal('Image', ControllableImage);
        const offscreens = stubOffscreenCanvases();
        const mod = await import('../PreviewRenderer');
        await loadReadyImage(mod, 'oversized_dim');

        const { ctx, drawImageCalls, clipCalls } = makeTransformAwareCtx(128, 128);
        const im: ImageElement = {
            id: 'im2',
            type: 'image',
            x: 0,
            y: 0,
            w: 12_000,
            h: 100,
            rotation: 0,
            locked: false,
            visible: true,
            source: 'oversized_dim',
            mask: { d: 'M0 0 L100 0 L100 100 L0 100 Z', inverted: false, featherPx: 6 },
        };
        mod.drawElement(ctx, im, 128, 128);

        expect(offscreens).toHaveLength(0);
        expect(clipCalls).toHaveLength(1);
        expect(drawImageCalls).toHaveLength(1);
        expect(drawImageCalls[0].args).toEqual([0, 0, 12_000, 100]);
    });

    it('裁剪后面积仍超 MAX_FEATHER_PIXELS (16.7M) 时降级走硬边绘制', async () => {
        vi.stubGlobal('Image', ControllableImage);
        const offscreens = stubOffscreenCanvases();
        const mod = await import('../PreviewRenderer');
        await loadReadyImage(mod, 'huge_area');

        const drawImageCalls: unknown[] = [];
        const clipCalls: number[] = [];
        // 无 canvas 的替身 ctx → 跳过可视裁剪，5000×5000 = 25M > 16,777,216 触发硬边降级
        const bareCtx = {
            save: vi.fn(),
            restore: vi.fn(),
            translate: vi.fn(),
            rotate: vi.fn(),
            clip: () => { clipCalls.push(1); },
            drawImage: (...args: unknown[]) => { drawImageCalls.push(args); },
            globalAlpha: 1,
        } as unknown as CanvasRenderingContext2D;

        const im: ImageElement = {
            id: 'im3',
            type: 'image',
            x: 0,
            y: 0,
            w: 5000,
            h: 5000,
            rotation: 0,
            locked: false,
            visible: true,
            source: 'huge_area',
            mask: { d: 'M0 0 L5000 0 L5000 5000 L0 5000 Z', inverted: false, featherPx: 4 },
        };
        mod.drawElement(bareCtx, im, 128, 128);

        expect(offscreens).toHaveLength(0);
        expect(clipCalls).toHaveLength(1);
        expect(drawImageCalls).toHaveLength(1);
    });
});

describe('onPaletteReady / onImageReady 多监听器订阅与解绑', () => {
    it('onPaletteReady 支持多订阅者并可独立取消订阅', async () => {
        stubOffscreenCanvases();
        const mod = await import('../PreviewRenderer');
        let aCalls = 0;
        let bCalls = 0;
        const offA = mod.onPaletteReady(() => { aCalls++; });
        const offB = mod.onPaletteReady(() => { bCalls++; });
        offA();

        const { ctx } = makeTransformAwareCtx(64, 64);
        const ditherRect: Element = {
            id: 'd1', type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0,
            locked: false, visible: true, renderMode: 'dither',
            fill: { type: 'solid', color: '#112233' },
        };
        mod.drawElement(ctx, ditherRect, 64, 64);
        expect(paletteResolvers).toHaveLength(1);
        paletteResolvers[0]({ fake: true });
        await Promise.resolve();

        expect(aCalls).toBe(0);
        expect(bCalls).toBe(1);
        offB();
    });

    it('onImageReady 支持多订阅者并可独立取消订阅', async () => {
        vi.stubGlobal('Image', ControllableImage);
        const mod = await import('../PreviewRenderer');

        let first = 0;
        let second = 0;
        const unsubFirst = mod.onImageReady(() => { first++; });
        const unsubSecond = mod.onImageReady(() => { second++; });

        mod.preloadImage('img_one', 'data:image/png;base64,AA');
        ControllableImage.instances[0].onload?.();
        expect(first).toBe(1);
        expect(second).toBe(1);

        unsubFirst();
        mod.preloadImage('img_two', 'data:image/png;base64,BB');
        ControllableImage.instances[1].onload?.();
        expect(first).toBe(1);
        expect(second).toBe(2);
        unsubSecond();
    });
});
