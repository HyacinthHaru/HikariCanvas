// @vitest-environment happy-dom
/**
 * 渐变 stop 的颜色兜底。
 *
 * 背景：{@code CanvasGradient.addColorStop} 遇到读不懂的颜色串会<b>抛异常</b>，而画布重绘
 * 挂在 rAF 回调上、没人接这个异常——一个坏颜色就让整块画布每帧崩一次，页面从此不再更新。
 * 工程导入构造的 canvas.background 能绕过协议入口校验带进坏颜色，所以这里必须自己兜底：
 * 读不懂的颜色一律当白色，与后端 FillPaintBuilder.parseColor 完全一致。
 */
import { describe, it, expect, vi } from 'vitest';
import { fillToCanvasStyle } from '../fill';
import type { LinearGradient, RadialGradient, SolidFill } from '@/types/protocol';

/** 模拟浏览器行为：非法颜色串 addColorStop 抛 SyntaxError。 */
function makeGradient() {
    const stops: Array<{ p: number; color: string }> = [];
    return {
        stops,
        addColorStop(p: number, color: string) {
            if (!/^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$/.test(color)) {
                throw new DOMException(`invalid color: ${color}`, 'SyntaxError');
            }
            stops.push({ p, color });
        },
    };
}

function fakeCtx(grad: ReturnType<typeof makeGradient>) {
    return {
        createLinearGradient: vi.fn(() => grad),
        createRadialGradient: vi.fn(() => grad),
    } as unknown as CanvasRenderingContext2D;
}

describe('fillToCanvasStyle 颜色兜底', () => {
    it('线性渐变里的非法颜色不再让重绘抛异常，落白色', () => {
        const grad = makeGradient();
        const fill: LinearGradient = {
            type: 'linear',
            angle: 0,
            stops: [
                { position: 0, color: 'rgb(255,0,0)' },   // 非 #RRGGBB 形态
                { position: 1, color: '#00FF00' },
            ],
        };
        expect(() => fillToCanvasStyle(fakeCtx(grad), fill, 0, 0, 100, 100)).not.toThrow();
        expect(grad.stops).toEqual([
            { p: 0, color: '#FFFFFF' },
            { p: 1, color: '#00FF00' },
        ]);
    });

    it('径向渐变同样兜底', () => {
        const grad = makeGradient();
        const fill: RadialGradient = {
            type: 'radial',
            cx: 0.5, cy: 0.5, r: 1,
            stops: [
                { position: 0, color: '#112233' },
                { position: 1, color: 'not-a-color' },
            ],
        };
        expect(() => fillToCanvasStyle(fakeCtx(grad), fill, 0, 0, 100, 100)).not.toThrow();
        expect(grad.stops[1].color).toBe('#FFFFFF');
    });

    it('合法 8 位带透明度的 hex 原样保留（不误伤）', () => {
        const grad = makeGradient();
        const fill: LinearGradient = {
            type: 'linear',
            angle: 90,
            stops: [
                { position: 0, color: '#00000000' },
                { position: 1, color: '#ffffffff' },
            ],
        };
        fillToCanvasStyle(fakeCtx(grad), fill, 0, 0, 64, 64);
        expect(grad.stops.map(s => s.color)).toEqual(['#00000000', '#ffffffff']);
    });

    it('纯色填充的非法颜色也落白色（与后端 parseColor 一致）', () => {
        const grad = makeGradient();
        const fill = { type: 'solid', color: 'chartreuse' } as SolidFill;
        expect(fillToCanvasStyle(fakeCtx(grad), fill, 0, 0, 10, 10)).toBe('#FFFFFF');
    });

    it('只有一个 stop 的渐变退化成纯色时也兜底', () => {
        const grad = makeGradient();
        const fill: LinearGradient = {
            type: 'linear',
            angle: 0,
            stops: [{ position: 0, color: 'oklch(50% 0.1 200)' }],
        };
        expect(fillToCanvasStyle(fakeCtx(grad), fill, 0, 0, 10, 10)).toBe('#FFFFFF');
    });
});
