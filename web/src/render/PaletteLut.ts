// MC 地图调色板查找表 —— Java PaletteLut 的 TypeScript 镜像。
// 契约见 docs/rendering.md §6 + plugin/src/main/java/moe/hikari/canvas/render/PaletteLut.java
// 算法完全相同：5-bit 量化 → 32³ byte LUT；CIE76 Lab 距离（sRGB D65 → XYZ → Lab）。
//
// 用法：const lut = await getPaletteLut(); const idx = lut.matchColor(r, g, b);

export interface PaletteEntry {
    index: number;
    rgb: [number, number, number];
    alpha: number;
}

const ALPHA_THRESHOLD = 128;
const LUT_BITS = 5;
const LUT_DIM = 1 << LUT_BITS;            // 32
const LUT_SIZE = LUT_DIM * LUT_DIM * LUT_DIM; // 32768

// D65 白点 + CIE 常量（与 Java 一致）
const D65_X = 0.95047;
const D65_Y = 1.00000;
const D65_Z = 1.08883;
const LAB_EPSILON = 216 / 24389;
const LAB_KAPPA = 24389 / 27;

export class PaletteLut {
    /** 原始 palette entries（含透明索引）。 */
    readonly entries: readonly PaletteEntry[];

    private readonly opaqueIndices: Uint8Array;
    private readonly opaqueLabL: Float64Array;
    private readonly opaqueLabA: Float64Array;
    private readonly opaqueLabB: Float64Array;
    private readonly lut: Uint8Array;

    /** 透明索引（MC 保留，一般是 0）。matchColor 对 alpha < 128 返回此值。 */
    static readonly TRANSPARENT_INDEX = 0;

    /** 从 /api/palette fetch → 构建 LUT。 */
    static async loadFromEndpoint(url = '/api/palette'): Promise<PaletteLut> {
        const r = await fetch(url);
        if (!r.ok) throw new Error(`GET ${url} failed: ${r.status}`);
        const entries = (await r.json()) as PaletteEntry[];
        return new PaletteLut(entries);
    }

    constructor(entries: PaletteEntry[]) {
        this.entries = entries;
        const opaque = entries.filter((e) => e.alpha >= ALPHA_THRESHOLD);
        const n = opaque.length;
        this.opaqueIndices = Uint8Array.from(opaque.map((e) => e.index));
        this.opaqueLabL = new Float64Array(n);
        this.opaqueLabA = new Float64Array(n);
        this.opaqueLabB = new Float64Array(n);
        for (let i = 0; i < n; i++) {
            const [L, A, B] = rgbToLab(opaque[i].rgb[0], opaque[i].rgb[1], opaque[i].rgb[2]);
            this.opaqueLabL[i] = L;
            this.opaqueLabA[i] = A;
            this.opaqueLabB[i] = B;
        }
        this.lut = new Uint8Array(LUT_SIZE);
        this.build();
    }

    /** 返回最接近 (r,g,b) 的 palette index；alpha < 128 直接返 TRANSPARENT_INDEX。 */
    matchColor(r: number, g: number, b: number, a = 255): number {
        if (a < ALPHA_THRESHOLD) return PaletteLut.TRANSPARENT_INDEX;
        const rq = clamp8(r) >> (8 - LUT_BITS);
        const gq = clamp8(g) >> (8 - LUT_BITS);
        const bq = clamp8(b) >> (8 - LUT_BITS);
        return this.lut[(rq << (2 * LUT_BITS)) | (gq << LUT_BITS) | bq];
    }

    /** 反向：palette index → [r, g, b, a]；越界返 null。 */
    getColor(index: number): [number, number, number, number] | null {
        const e = this.entries[index];
        return e ? [e.rgb[0], e.rgb[1], e.rgb[2], e.alpha] : null;
    }

    /**
     * 把 ImageData 逐像素量化到 palette 颜色（RGB 覆盖回 imageData，alpha 保留）。
     * M5-D snapshot 测试 + "Simulate MC palette" 预览切换 用。
     */
    quantizeImageData(imgData: ImageData): void {
        const d = imgData.data;
        for (let i = 0; i < d.length; i += 4) {
            const a = d[i + 3];
            if (a < ALPHA_THRESHOLD) continue;
            const idx = this.matchColor(d[i], d[i + 1], d[i + 2]);
            const e = this.entries[idx];
            if (e) {
                d[i] = e.rgb[0];
                d[i + 1] = e.rgb[1];
                d[i + 2] = e.rgb[2];
            }
        }
    }

    // ---------- 内部 ----------

    private build(): void {
        const step = 1 << (8 - LUT_BITS);
        const mid = step / 2; // 用 cell 中心点代表整格颜色
        const n = this.opaqueIndices.length;
        for (let rq = 0; rq < LUT_DIM; rq++) {
            const r = rq * step + mid;
            for (let gq = 0; gq < LUT_DIM; gq++) {
                const g = gq * step + mid;
                for (let bq = 0; bq < LUT_DIM; bq++) {
                    const b = bq * step + mid;
                    const [L, A, B] = rgbToLab(r, g, b);
                    let bestDist = Infinity;
                    let bestIdx = 0;
                    for (let i = 0; i < n; i++) {
                        const dL = L - this.opaqueLabL[i];
                        const dA = A - this.opaqueLabA[i];
                        const dB = B - this.opaqueLabB[i];
                        const d = dL * dL + dA * dA + dB * dB;
                        if (d < bestDist) { bestDist = d; bestIdx = this.opaqueIndices[i]; }
                    }
                    this.lut[(rq << (2 * LUT_BITS)) | (gq << LUT_BITS) | bq] = bestIdx;
                }
            }
        }
    }
}

// ---------- 色彩空间 ----------

function rgbToLab(r: number, g: number, b: number): [number, number, number] {
    const rn = srgbToLinear(r / 255);
    const gn = srgbToLinear(g / 255);
    const bn = srgbToLinear(b / 255);
    const x = 0.4124564 * rn + 0.3575761 * gn + 0.1804375 * bn;
    const y = 0.2126729 * rn + 0.7151522 * gn + 0.0721750 * bn;
    const z = 0.0193339 * rn + 0.1191920 * gn + 0.9503041 * bn;
    const fx = labF(x / D65_X);
    const fy = labF(y / D65_Y);
    const fz = labF(z / D65_Z);
    return [116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)];
}

function srgbToLinear(c: number): number {
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

function labF(t: number): number {
    return t > LAB_EPSILON ? Math.cbrt(t) : (LAB_KAPPA * t + 16) / 116;
}

function clamp8(v: number): number {
    return v < 0 ? 0 : v > 255 ? 255 : v;
}

// ---------- 懒加载 singleton ----------

let lutPromise: Promise<PaletteLut> | null = null;

/**
 * 首次调 fetch + 构建；之后返回缓存 promise。
 * 构建耗时 ~1-3s（单线程遍历 8M 格 × 248 palette）；由于只跑一次，放 main thread 可接受。
 * M7 polish 可迁到 Web Worker 避免首帧阻塞。
 */
export function getPaletteLut(): Promise<PaletteLut> {
    if (!lutPromise) lutPromise = PaletteLut.loadFromEndpoint();
    return lutPromise;
}
