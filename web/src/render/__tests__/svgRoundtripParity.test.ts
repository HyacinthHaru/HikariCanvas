// @vitest-environment happy-dom
/**
 * svgRoundtripParity.test.ts
 *
 * 回归测试：锁定「SVG 导入产物在前端能正确渲染、
 * 且 fillRule/归一化 d 的处理与后端一致」。
 *
 * 双端一致性说明（B0 已对齐，本测试锁定回归）：
 *   前端  PreviewRenderer.drawPath  → ctx.fill(path, 'evenodd')
 *   后端  PathRenderer.java         → path.setWindingRule(WIND_EVEN_ODD)
 *   两端都把 fillRule:'evenodd' 映射到 even-odd 缠绕规则，逐函数一致。
 *
 * happy-dom canvas 限制：
 *   happy-dom 的 <canvas> 2D context 不支持真实像素绘制，
 *   getImageData 拿不到真实像素，因此本测试不做像素快照对比，
 *   改做「逻辑级 parity」：检查 svgToElements 产物的 d 命令集合，
 *   以及通过 mock ctx 断言 drawPath 对 fillRule 的调用参数。
 */

import { describe, it, expect, vi, beforeAll } from 'vitest';
import type { PathElement } from '@/types/protocol';

// ── happy-dom 不提供 Path2D，stub 全局 ──────────────────────────────────────
beforeAll(() => {
    vi.stubGlobal('Path2D', class {
        constructor(_d?: string) {}
        moveTo(_x: number, _y: number) {}
        lineTo(_x: number, _y: number) {}
        quadraticCurveTo(_cpx: number, _cpy: number, _x: number, _y: number) {}
        bezierCurveTo(
            _cp1x: number, _cp1y: number,
            _cp2x: number, _cp2y: number,
            _x: number, _y: number,
        ) {}
        closePath() {}
        addPath(_p: unknown) {}
    });
});

// ── 动态导入（确保 Path2D stub 先就位）──────────────────────────────────────
async function getDrawPath() {
    const mod = await import('../PreviewRenderer');
    return mod.drawPath;
}

// ── 测试用 SVG ───────────────────────────────────────────────────────────────
/**
 * DONUT_SVG：
 *   path[0] — 外方 40×40 + 内方 20×20 镂空，fill-rule="evenodd"（带洞图形）
 *             原始 d 含 H/V 命令，期望 svgToElements 归一化为 M/L/Q/C/Z 子集
 *   path[1] — A 弧线（stroke only），同样期望归一化后无 A/H/V/S/T 残留
 */
const DONUT_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
  <path d="M0 0 H40 V40 H0 Z M10 10 H30 V30 H10 Z" fill="#ff0000" fill-rule="evenodd"/>
  <path d="M10 0 A10 10 0 0 1 0 10" stroke="#00ff00" stroke-width="2"/>
</svg>`;

// ── mock CanvasRenderingContext2D ────────────────────────────────────────────
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
        set globalAlpha(_v: unknown) {},
        get globalAlpha() { return 1; },
        set lineWidth(_v: unknown) {},
        set lineCap(_v: unknown) {},
        set lineJoin(_v: unknown) {},
    } as unknown as CanvasRenderingContext2D;
}

// ── 直接构造 PathElement（不经 svgToElements，用于独立验证 drawPath）────────
function pathEl(fillRule?: 'nonzero' | 'evenodd'): PathElement {
    return {
        id: 'e', type: 'path', x: 0, y: 0, w: 40, h: 40, rotation: 0,
        locked: false, visible: true,
        d: 'M0 0 L40 0 L40 40 L0 40 Z M10 10 L30 10 L30 30 L10 30 Z',
        fill: { type: 'solid', color: '#ff0000' },
        fillRule,
    } as PathElement;
}

// ────────────────────────────────────────────────────────────────────────────
describe('SVG import → render parity', () => {

    // ── 1. 归一化：d 只含 M/L/Q/C/Z ─────────────────────────────────────────
    it('normalized d contains only M/L/Q/C/Z (no H/V/S/T/A)', async () => {
        const { svgToElements } = await import('@/lib/svg/svgToElements');
        const drafts = svgToElements(DONUT_SVG);
        const pathDrafts = drafts.filter(d => d.type === 'path');

        // 至少应有两个 path draft（donut + arc stroke）
        expect(pathDrafts.length).toBeGreaterThanOrEqual(2);

        // 后端 PathDValidator 只接受 M/L/Q/C/Z，归一化必须彻底
        const ALLOWED = new Set(['M', 'L', 'Q', 'C', 'Z']);
        for (const draft of pathDrafts) {
            const dStr = String(draft.props.d);
            const cmds = dStr.match(/[A-Za-z]/g) ?? [];
            for (const c of cmds) {
                expect(ALLOWED, `unexpected command "${c}" in: ${dStr}`)
                    .toContain(c.toUpperCase());
            }
        }
    });

    // ── 2. 带洞图形 fillRule 正确承载 ────────────────────────────────────────
    it('donut path carries fillRule evenodd', async () => {
        const { svgToElements } = await import('@/lib/svg/svgToElements');
        const drafts = svgToElements(DONUT_SVG);
        const donut = drafts.find(d => d.type === 'path' && d.props.fillRule === 'evenodd');
        expect(donut).toBeTruthy();
        expect(donut!.props.fillRule).toBe('evenodd');
    });

    // ── 3. 双端 fillRule 一致性：前端 drawPath 调 ctx.fill(path, 'evenodd') ──
    //
    // 后端 PathRenderer.java 对应：
    //   path.setWindingRule(PathIterator.WIND_EVEN_ODD)   // fillRule === 'evenodd'
    //   path.setWindingRule(PathIterator.WIND_NON_ZERO)   // 其余
    //
    // 前端 PreviewRenderer.drawPath 对应：
    //   ctx.fill(parsed.path, p.fillRule === 'evenodd' ? 'evenodd' : 'nonzero')
    //
    // 两端都把 fillRule:'evenodd' 映射到 even-odd 缠绕规则，逐函数一致（B0 已对齐）。
    it('drawPath passes evenodd to ctx.fill — mirrors backend WIND_EVEN_ODD', async () => {
        const drawPath = await getDrawPath();
        const ctx = fakeCtx();
        drawPath(ctx, pathEl('evenodd'));
        const fillCalls = (ctx.fill as ReturnType<typeof vi.fn>).mock.calls;
        expect(fillCalls.some(c => c[1] === 'evenodd')).toBe(true);
    });

    it('drawPath uses nonzero when fillRule is absent — mirrors backend WIND_NON_ZERO', async () => {
        const drawPath = await getDrawPath();
        const ctx = fakeCtx();
        drawPath(ctx, pathEl(undefined));
        const fillCalls = (ctx.fill as ReturnType<typeof vi.fn>).mock.calls;
        // undefined → nonzero 分支，不应出现 'evenodd'
        expect(fillCalls.every(c => c[1] === undefined || c[1] === 'nonzero')).toBe(true);
    });

    // ── 4. svgToElements 产物 fillRule 与 drawPath mock 端对端打通 ───────────
    it('end-to-end: svgToElements evenodd draft → drawPath calls ctx.fill with evenodd', async () => {
        const { svgToElements } = await import('@/lib/svg/svgToElements');
        const drawPath = await getDrawPath();

        const drafts = svgToElements(DONUT_SVG);
        const donutDraft = drafts.find(d => d.type === 'path' && d.props.fillRule === 'evenodd');
        expect(donutDraft).toBeTruthy();

        // 把 draft props 转成 PathElement 调 drawPath
        const el: PathElement = {
            id: 'imported',
            type: 'path',
            x: Number(donutDraft!.props.x ?? 0),
            y: Number(donutDraft!.props.y ?? 0),
            w: Number(donutDraft!.props.w ?? 40),
            h: Number(donutDraft!.props.h ?? 40),
            rotation: 0,
            locked: false,
            visible: true,
            d: String(donutDraft!.props.d),
            fill: donutDraft!.props.fill as PathElement['fill'],
            fillRule: donutDraft!.props.fillRule as 'evenodd',
        };

        const ctx = fakeCtx();
        drawPath(ctx, el);
        const fillCalls = (ctx.fill as ReturnType<typeof vi.fn>).mock.calls;
        expect(fillCalls.some(c => c[1] === 'evenodd')).toBe(true);
    });
});
