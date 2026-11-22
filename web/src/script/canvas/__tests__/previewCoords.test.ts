/**
 * 预览框坐标系纯函数单测。
 *
 * <p>{@link computePreviewTransform} fit-scale + 居中；{@link wallToPreview} /
 * {@link previewToWall} 互逆（round-trip &lt; 0.5px）；{@link clientToWall} 按画布
 * 实际布局矩形比例映射指针坐标。</p>
 */
import { describe, it, expect } from 'vitest';
import {
    computePreviewTransform,
    wallToPreview,
    previewToWall,
    clientToWall,
    type PreviewTransform,
} from '../previewCoords';

describe('computePreviewTransform（fit + 居中）', () => {
    it('墙比预览框小：等比放大占满较紧的一维 + 居中', () => {
        // 墙 100×100，框 400×200 → scale = min(4, 2) = 2；缩放后 200×200
        // offsetX = (400 - 200)/2 = 100；offsetY = (200 - 200)/2 = 0
        const t = computePreviewTransform(100, 100, 400, 200);
        expect(t.scale).toBe(2);
        expect(t.offsetX).toBe(100);
        expect(t.offsetY).toBe(0);
    });

    it('墙比预览框大：等比缩小放进框 + 居中', () => {
        // 墙 512×256，框 256×256 → scale = min(0.5, 1) = 0.5；缩放后 256×128
        // offsetX = 0；offsetY = (256 - 128)/2 = 64
        const t = computePreviewTransform(512, 256, 256, 256);
        expect(t.scale).toBe(0.5);
        expect(t.offsetX).toBe(0);
        expect(t.offsetY).toBe(64);
    });

    it('正方墙 + 正方框：scale 1，无偏移', () => {
        const t = computePreviewTransform(128, 128, 128, 128);
        expect(t).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
    });

    it('退化输入（任一维 ≤ 0）→ 恒等变换兜底', () => {
        expect(computePreviewTransform(0, 100, 400, 200)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
        expect(computePreviewTransform(100, 0, 400, 200)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
        expect(computePreviewTransform(100, 100, 0, 200)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
        expect(computePreviewTransform(100, 100, 400, 0)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
        expect(computePreviewTransform(-5, 100, 400, 200)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
    });
});

describe('wallToPreview / previewToWall 互逆', () => {
    function roundTrip(t: PreviewTransform, x: number, y: number) {
        const p = wallToPreview(t, x, y);
        return previewToWall(t, p.x, p.y);
    }

    it('放大场景 round-trip 误差 < 0.5px', () => {
        const t = computePreviewTransform(100, 100, 400, 200);
        for (const [x, y] of [[0, 0], [50, 50], [99, 1], [37.5, 80.2]]) {
            const back = roundTrip(t, x, y);
            expect(Math.abs(back.x - x)).toBeLessThan(0.5);
            expect(Math.abs(back.y - y)).toBeLessThan(0.5);
        }
    });

    it('缩小场景 round-trip 误差 < 0.5px', () => {
        const t = computePreviewTransform(512, 256, 256, 256);
        for (const [x, y] of [[0, 0], [256, 128], [511, 255], [123.4, 200.7]]) {
            const back = roundTrip(t, x, y);
            expect(Math.abs(back.x - x)).toBeLessThan(0.5);
            expect(Math.abs(back.y - y)).toBeLessThan(0.5);
        }
    });

    it('wallToPreview 把墙原点映射到居中偏移点', () => {
        const t = computePreviewTransform(100, 100, 400, 200);
        expect(wallToPreview(t, 0, 0)).toEqual({ x: t.offsetX, y: t.offsetY });
    });

    it('previewToWall 把居中偏移点映射回墙原点', () => {
        const t = computePreviewTransform(512, 256, 256, 256);
        const back = previewToWall(t, t.offsetX, t.offsetY);
        expect(Math.abs(back.x)).toBeLessThan(0.001);
        expect(Math.abs(back.y)).toBeLessThan(0.001);
    });
});

describe('clientToWall', () => {
    // canvas CSS 宽是 round(wallW*scale)，本函数以 crect 真实尺寸比例映射，不经 transform.offset。
    const crect = { left: 100, top: 50, width: 256, height: 128 };
    const wallW = 512;
    const wallH = 256;

    it('canvas 左上角 client 点 → 墙原点 (0,0)', () => {
        const r = clientToWall(crect, wallW, wallH, 100, 50)!;
        expect(r.x).toBeCloseTo(0, 6);
        expect(r.y).toBeCloseTo(0, 6);
    });

    it('canvas 右下角 client 点 → 墙右下 (wallW, wallH)', () => {
        const r = clientToWall(crect, wallW, wallH, 100 + 256, 50 + 128)!;
        expect(r.x).toBeCloseTo(wallW, 6);
        expect(r.y).toBeCloseTo(wallH, 6);
    });

    it('中点 client → 墙中点', () => {
        const r = clientToWall(crect, wallW, wallH, 100 + 128, 50 + 64)!;
        expect(r.x).toBeCloseTo(wallW / 2, 6);
        expect(r.y).toBeCloseTo(wallH / 2, 6);
    });

    it('crect 任一维 ≤ 0 → null（canvas 未布局）', () => {
        expect(clientToWall({ left: 0, top: 0, width: 0, height: 128 }, wallW, wallH, 5, 5)).toBeNull();
        expect(clientToWall(crect, 0, wallH, 5, 5)).toBeNull();
    });
});
