// @vitest-environment happy-dom
/**
 * 导入出口预检。
 *
 * 服务端对 element.add 有一串硬约束（d 串 ≤4096 字符、宽高 ≥1、线宽整数 ≤128、
 * 渐变停止点 2..8 且位置有序）。前端不预检的话，超限的元素在服务端一律被判非法丢掉，
 * 而导入对话框还报"成功导入 N 个"——数字和画布上的东西对不上，用户完全不知道少了什么。
 *
 * 能收敛的就地收敛，实在不行才丢并计数。
 */
import { describe, it, expect } from 'vitest';
import { svgToElements, svgToElementsDetailed, BACKEND_LIMITS } from '../svgToElements';
import type { Stroke, LinearGradient, RadialGradient } from '@/types/protocol';

const SVG_NS = 'xmlns="http://www.w3.org/2000/svg"';

describe('宽高必须 ≥1', () => {
    it('水平线的 bbox 高度是 0 → 补成 1 而不是被服务端拒掉', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><line x1="0" y1="5" x2="40" y2="5" stroke="#000000"/></svg>`);
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.h).toBe(1);
        expect(drafts[0].props.w).toBe(40);
    });

    it('x/y/w/h 都是整数（服务端是 int 字段，小数会被截断）', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><rect x="1.6" y="2.4" width="3.7" height="4.2" fill="#000000"/></svg>`);
        const p = drafts[0].props;
        for (const k of ['x', 'y', 'w', 'h'] as const) {
            expect(Number.isInteger(p[k])).toBe(true);
        }
    });

    it('坐标超出服务端范围的形状被挡下并计数', () => {
        const { drafts, dropped } = svgToElementsDetailed(
            `<svg ${SVG_NS}><rect x="99999" y="0" width="10" height="10" fill="#000000"/></svg>`);
        expect(drafts).toHaveLength(0);
        expect(dropped.OUT_OF_RANGE).toBe(1);
    });
});

describe('线宽收敛', () => {
    it('0.5px 的线取整成 0 会让整条元素被拒 → 至少给 1px', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><path d="M0 0 L10 10" fill="none" stroke="#000000" stroke-width="0.5"/></svg>`);
        expect(drafts).toHaveLength(1);
        expect((drafts[0].props.stroke as Stroke).width).toBe(1);
    });

    it('超粗的线钳到上限', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><path d="M0 0 L10 10" fill="none" stroke="#000000" stroke-width="9999"/></svg>`);
        expect((drafts[0].props.stroke as Stroke).width).toBe(BACKEND_LIMITS.maxStrokeWidth);
    });

    it('有填充时 0 宽描边直接去掉（留着也画不出来）', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><rect x="0" y="0" width="10" height="10" fill="#ff0000" stroke="#000000" stroke-width="0"/></svg>`);
        expect(drafts[0].props.stroke).toBeUndefined();
    });
});

describe('线宽跟着 transform 缩放', () => {
    it('父 g 上的 scale(4) 让 1px 线变成 4px', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><g transform="scale(4)">
                <path d="M0 0 L10 10" fill="none" stroke="#000000" stroke-width="1"/>
            </g></svg>`);
        expect((drafts[0].props.stroke as Stroke).width).toBe(4);
    });

    it('没有 transform 时线宽不变（回归守卫）', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><path d="M0 0 L10 10" fill="none" stroke="#000000" stroke-width="3"/></svg>`);
        expect((drafts[0].props.stroke as Stroke).width).toBe(3);
    });
});

describe('d 串长度', () => {
    /** 造一条超长 path：每段坐标都带 6 位小数，原样输出必然超 4096 字符。 */
    function longPath(segments: number): string {
        let d = 'M0.123456 0.123456';
        for (let i = 1; i <= segments; i++) {
            d += ` L${(i + 0.123456).toFixed(6)} ${(i + 0.654321).toFixed(6)}`;
        }
        return d;
    }

    it('超长 d 串先降精度塞进上限，而不是整条丢掉', () => {
        const { drafts, dropped } = svgToElementsDetailed(
            `<svg ${SVG_NS}><path d="${longPath(200)}" fill="none" stroke="#000000"/></svg>`);
        expect(dropped.PATH_TOO_LONG).toBeUndefined();
        expect(drafts).toHaveLength(1);
        expect((drafts[0].props.d as string).length).toBeLessThanOrEqual(BACKEND_LIMITS.maxPathDLen);
    });

    it('降到整数精度还塞不下就挡下来并计数（不虚报成功）', () => {
        const { drafts, dropped } = svgToElementsDetailed(
            `<svg ${SVG_NS}><path d="${longPath(2000)}" fill="none" stroke="#000000"/></svg>`);
        expect(drafts).toHaveLength(0);
        expect(dropped.PATH_TOO_LONG).toBe(1);
    });

    it('普通图标不受影响，精度照旧保留（回归守卫）', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><path d="M0.5 0.25 L10.125 10.5" fill="none" stroke="#000000"/></svg>`);
        expect(drafts[0].props.d).toContain('.');
    });
});

describe('渐变收敛到服务端接受的范围', () => {
    it('r="120%" 超出 (0,2] 上限 → 钳回 2', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}>
                <defs><radialGradient id="g" r="120%">
                    <stop offset="0%" stop-color="#ff0000"/><stop offset="100%" stop-color="#0000ff"/>
                </radialGradient></defs>
                <rect x="0" y="0" width="10" height="10" fill="url(#g)"/>
            </svg>`);
        const fill = drafts[0].props.fill as RadialGradient;
        expect(fill.type).toBe('radial');
        expect(fill.r).toBeLessThanOrEqual(BACKEND_LIMITS.maxRadialR);
    });

    it('读不懂的 offset 不再产出 NaN 位置', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}>
                <defs><linearGradient id="g">
                    <stop offset="abc%" stop-color="#ff0000"/><stop offset="100%" stop-color="#0000ff"/>
                </linearGradient></defs>
                <rect x="0" y="0" width="10" height="10" fill="url(#g)"/>
            </svg>`);
        const fill = drafts[0].props.fill as LinearGradient;
        for (const s of fill.stops) {
            expect(Number.isFinite(s.position)).toBe(true);
            expect(s.position).toBeGreaterThanOrEqual(0);
            expect(s.position).toBeLessThanOrEqual(1);
        }
    });
});

describe('transform 写错不该毁掉整个形状', () => {
    it('translate(abc) 里的非数字不再让坐标变成 NaN', () => {
        const drafts = svgToElements(
            `<svg ${SVG_NS}><g transform="translate(abc)">
                <rect x="1" y="2" width="10" height="10" fill="#000000"/>
            </g></svg>`);
        expect(drafts).toHaveLength(1);
        expect(drafts[0].props.x).toBe(1);
        expect(drafts[0].props.d).not.toContain('NaN');
    });
});
