// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeAll } from 'vitest';
import type { PathElement } from '@/types/protocol';

// happy-dom 不提供 Path2D，stub 全局，让 parsePathD 能正常构造
beforeAll(() => {
    vi.stubGlobal('Path2D', class {
        constructor(_d?: string) {}
        moveTo(_x: number, _y: number) {}
        lineTo(_x: number, _y: number) {}
        quadraticCurveTo(_cpx: number, _cpy: number, _x: number, _y: number) {}
        bezierCurveTo(_cp1x: number, _cp1y: number, _cp2x: number, _cp2y: number, _x: number, _y: number) {}
        closePath() {}
        addPath(_p: unknown) {}
    });
});

// 在 stub 之后动态导入，确保 PathParser 里的 new Path2D() 走 stub
async function getDrawPath() {
    const mod = await import('../PreviewRenderer');
    return mod.drawPath;
}

function fakeCtx() {
    return {
        save: vi.fn(),
        restore: vi.fn(),
        translate: vi.fn(),
        fill: vi.fn(),
        stroke: vi.fn(),
        clip: vi.fn(),
        beginPath: vi.fn(),
        set fillStyle(_v: unknown) {},
        set strokeStyle(_v: unknown) {},
        set globalAlpha(_v: unknown) { },
        get globalAlpha() { return 1; },
        set lineWidth(_v: unknown) {},
        set lineCap(_v: unknown) {},
        set lineJoin(_v: unknown) {},
    } as unknown as CanvasRenderingContext2D;
}

function pathEl(fillRule?: 'nonzero' | 'evenodd'): PathElement {
    return {
        id: 'e', type: 'path', x: 0, y: 0, w: 10, h: 10, rotation: 0, locked: false,
        visible: true, d: 'M0 0 L10 0 L10 10 Z',
        fill: { type: 'solid', color: '#ff0000' }, fillRule,
    } as PathElement;
}

describe('drawPath fillRule', () => {
    it('passes evenodd to ctx.fill', async () => {
        const drawPath = await getDrawPath();
        const ctx = fakeCtx();
        drawPath(ctx, pathEl('evenodd'));
        expect((ctx.fill as ReturnType<typeof vi.fn>).mock.calls.some((c) => c[1] === 'evenodd')).toBe(true);
    });

    it('defaults to nonzero when fillRule absent', async () => {
        const drawPath = await getDrawPath();
        const ctx = fakeCtx();
        drawPath(ctx, pathEl(undefined));
        const calls = (ctx.fill as ReturnType<typeof vi.fn>).mock.calls;
        expect(calls.every((c) => c[1] === undefined || c[1] === 'nonzero')).toBe(true);
    });
});
