/**
 * 0.7.1-P3-T3：预览框坐标系纯函数。
 *
 * <p>{@link computePreviewTransform} fit-scale + 居中；{@link wallToPreview} /
 * {@link previewToWall} 互逆（round-trip < 0.5px）。互逆是硬约束——P4「幽灵拖动设目标坐标」
 * 靠它把预览里指针位置反算成墙坐标，差一点就会偏。</p>
 */
import { describe, it, expect } from 'vitest';
import {
    computePreviewTransform,
    wallToPreview,
    previewToWall,
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
