import { describe, it, expect } from 'vitest';
import {
    computePreviewTransform, wallToPreview, previewToWall, clientToWall,
} from './previewCoords';

describe('computePreviewTransform', () => {
    it('fit-scale 取较小比例 + 居中偏移', () => {
        // 墙 512×256 进 256×256 → scale=min(256/512,256/256)=0.5；offsetX=(256-256)/2=0, offsetY=(256-128)/2=64
        const t = computePreviewTransform(512, 256, 256, 256);
        expect(t.scale).toBeCloseTo(0.5, 6);
        expect(t.offsetX).toBeCloseTo(0, 6);
        expect(t.offsetY).toBeCloseTo(64, 6);
    });
    it('任一维 ≤ 0 → 恒等兜底', () => {
        expect(computePreviewTransform(0, 256, 256, 256)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
        expect(computePreviewTransform(512, 256, 256, 0)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
    });
});

describe('wallToPreview / previewToWall 互逆', () => {
    const t = computePreviewTransform(512, 256, 300, 300);
    it('round-trip 还原', () => {
        const p = wallToPreview(t, 137, 89);
        const w = previewToWall(t, p.x, p.y);
        expect(w.x).toBeCloseTo(137, 6);
        expect(w.y).toBeCloseTo(89, 6);
    });
});

describe('clientToWall (P4 M1 正解)', () => {
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
