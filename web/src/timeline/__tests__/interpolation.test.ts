/**
 * 0.6 P3（B3）：关键帧插值器前端镜像测试。
 *
 * 镜像后端 KeyframeInterpolator 的语义（rendering.md §9.1–§9.5）：mapTime 三模式 /
 * sampleNumeric 边界 + Str resolve / sampleColor / sampleFill / sampleText per 类型 /
 * interpolate 端到端。数值舍入用 `Math.round`（与 Java record int 字段 / Java
 * `(int) Math.round` 对齐）；opacity 钳 [0,1]；无可插值轨返 base 同一引用。
 *
 * 这是「前端镜像逻辑自洽」测试，不读共享向量（向量在 easing / colorLerp 两端各跑）。
 */
import { describe, expect, it } from 'vitest';
import {
    mapTime,
    sampleNumeric,
    sampleColor,
    sampleFill,
    sampleText,
    interpolate,
} from '../interpolation';
import type {
    Element,
    Fill,
    Keyframe,
    LinearGradient,
    ProjectState,
    SolidFill,
    Timeline,
} from '@/types/protocol';

function kf(property: string, timeMs: number, value: Keyframe['value'], easing: Keyframe['easing'] = { type: 'linear' }): Keyframe {
    return { id: `kf-${property}-${timeMs}`, property, timeMs, value, easing };
}

// ---------- mapTime（§9 loopMode） ----------

describe('mapTime', () => {
    it('returns 0 for non-positive duration', () => {
        expect(mapTime(123, 0, 'loop')).toBe(0);
        expect(mapTime(123, -1, 'loop')).toBe(0);
    });

    it('ONCE clamps to [0, durationMs]', () => {
        expect(mapTime(-50, 1000, 'once')).toBe(0);
        expect(mapTime(400, 1000, 'once')).toBe(400);
        expect(mapTime(5000, 1000, 'once')).toBe(1000);
    });

    it('LOOP wraps with floorMod', () => {
        expect(mapTime(0, 1000, 'loop')).toBe(0);
        expect(mapTime(1000, 1000, 'loop')).toBe(0);
        expect(mapTime(1500, 1000, 'loop')).toBe(500);
        expect(mapTime(-200, 1000, 'loop')).toBe(800);   // 负位置回绕
    });

    it('PING_PONG is a triangle wave of period 2×duration', () => {
        const d = 1000;
        expect(mapTime(0, d, 'pingPong')).toBe(0);
        expect(mapTime(1000, d, 'pingPong')).toBe(1000);
        expect(mapTime(1500, d, 'pingPong')).toBe(500);   // 回程
        expect(mapTime(2000, d, 'pingPong')).toBe(0);
        expect(mapTime(2500, d, 'pingPong')).toBe(500);   // 第二周期去程
    });

    it('defaults to loop for undefined mode', () => {
        expect(mapTime(1500, 1000, undefined)).toBe(500);
    });
});

// ---------- sampleNumeric（§9.1 / §9.2 数值 / §9.5 变量） ----------

describe('sampleNumeric', () => {
    it('returns null for empty track', () => {
        expect(sampleNumeric([], 0)).toBeNull();
    });

    it('takes first keyframe before the first time (no extrapolation)', () => {
        const kfs = [kf('x', 100, 10), kf('x', 200, 20)];
        expect(sampleNumeric(kfs, 0)).toBe(10);
        expect(sampleNumeric(kfs, 100)).toBe(10);
    });

    it('takes last keyframe after the last time (no extrapolation)', () => {
        const kfs = [kf('x', 100, 10), kf('x', 200, 20)];
        expect(sampleNumeric(kfs, 200)).toBe(20);
        expect(sampleNumeric(kfs, 999)).toBe(20);
    });

    it('linearly interpolates inside the span (linear easing)', () => {
        const kfs = [kf('x', 0, 0), kf('x', 100, 100)];
        expect(sampleNumeric(kfs, 50)).toBe(50);
        expect(sampleNumeric(kfs, 25)).toBe(25);
    });

    it('applies left-keyframe easing', () => {
        const kfs = [kf('x', 0, 0, { type: 'easeInOut' }), kf('x', 100, 100)];
        // easeInOut(0.5) = 0.5 → 50；不是端帧 easing。
        expect(sampleNumeric(kfs, 50)!).toBeCloseTo(50, 6);
        // easeInOut(0.3) ≈ 0.187396 → ~18.74
        expect(sampleNumeric(kfs, 30)!).toBeCloseTo(18.739589, 4);
    });

    it('resolves Str numeric strings without resolver', () => {
        const kfs = [kf('x', 0, '10'), kf('x', 100, '20')];
        expect(sampleNumeric(kfs, 50)).toBe(15);
    });

    it('falls variable refs to 0 without resolver (§9.5 chain end)', () => {
        const kfs = [kf('x', 0, '${var:user/a}'), kf('x', 100, 100)];
        // a → 0、b = 100、t=0.5 → 50
        expect(sampleNumeric(kfs, 50)).toBe(50);
    });

    it('uses the injected resolver for Str values', () => {
        const kfs = [kf('x', 0, '${var:user/a}'), kf('x', 100, '${var:user/b}')];
        const resolver = (raw: string) => (raw.includes('/a') ? 40 : 80);
        expect(sampleNumeric(kfs, 50, resolver)).toBe(60);
    });

    it('skips the whole track (null) on a wrong-typed value', () => {
        const kfs = [kf('x', 0, 0), kf('x', 100, { type: 'solid', color: '#fff' } as Fill)];
        expect(sampleNumeric(kfs, 50)).toBeNull();
    });

    it('takes the later keyframe on interior coincident times (§9.1 重合帧取后)', () => {
        // 重合帧组放区间内部（非首帧边界）：t=100 命中最大 timeMs ≤ t 的那帧 → 99，无除零。
        // 注意首帧边界（t ≤ first.timeMs）走 §9.1 取首帧分支，不属「取后」语义。
        const kfs = [kf('x', 0, 5), kf('x', 100, 10), kf('x', 100, 99), kf('x', 200, 20)];
        expect(sampleNumeric(kfs, 100)).toBe(99);
    });

    it('takes the first keyframe at the first-frame boundary even with coincident frames', () => {
        const kfs = [kf('x', 100, 10), kf('x', 100, 99), kf('x', 200, 20)];
        // t=100 == first.timeMs → §9.1 首帧分支取首帧 10（不外插），与后端 spanAt 一致。
        expect(sampleNumeric(kfs, 100)).toBe(10);
    });

    it('rejects host-lenient numeric forms (0x1p4 / 5d / 12abc) → 0 (§9.5)', () => {
        // 与后端 StrictNumber 同文法：parseFloat 会接受这些，严格文法整串匹配 → 拒
        expect(sampleNumeric([kf('x', 0, '0x1p4'), kf('x', 100, 100)], 0)).toBe(0);
        expect(sampleNumeric([kf('x', 0, '5d'), kf('x', 100, 100)], 0)).toBe(0);
        expect(sampleNumeric([kf('x', 0, '12abc'), kf('x', 100, 100)], 0)).toBe(0);
    });

    it('trim strips only ascii ws, not NBSP / full-width space (§9.5 双端对齐)', () => {
        // JS trim() 会剥 NBSP 致 " 5"→5，与 Java trim() 分叉；改 [\x00-\x20] strip 后两端均 0
        expect(sampleNumeric([kf('x', 0, ' 5'), kf('x', 100, 100)], 0)).toBe(0);
        expect(sampleNumeric([kf('x', 0, '　5'), kf('x', 100, 100)], 0)).toBe(0);
        expect(sampleNumeric([kf('x', 0, '\t5\n'), kf('x', 100, 100)], 0)).toBe(5);
    });
});

// ---------- sampleColor（§9.4） ----------

describe('sampleColor', () => {
    it('interpolates hex strings in linear space', () => {
        const kfs = [kf('color', 0, '#000000'), kf('color', 100, '#FFFFFF')];
        expect(sampleColor(kfs, 50)).toBe('#BCBCBC');
    });

    it('takes endpoints without interpolation outside the span', () => {
        const kfs = [kf('color', 100, '#FF0000'), kf('color', 200, '#0000FF')];
        expect(sampleColor(kfs, 0)).toBe('#FF0000');
        expect(sampleColor(kfs, 999)).toBe('#0000FF');
    });

    it('skips the whole track when any value is a variable ref', () => {
        const kfs = [kf('color', 0, '${var:user/c}'), kf('color', 100, '#FFFFFF')];
        expect(sampleColor(kfs, 50)).toBeNull();
    });

    it('skips the whole track on a wrong-typed value', () => {
        const kfs = [kf('color', 0, 123 as unknown as string), kf('color', 100, '#FFFFFF')];
        expect(sampleColor(kfs, 50)).toBeNull();
    });
});

// ---------- sampleFill（§9.4） ----------

describe('sampleFill', () => {
    it('lerps solid fills', () => {
        const kfs = [
            kf('fill', 0, { type: 'solid', color: '#000000' } as Fill),
            kf('fill', 100, { type: 'solid', color: '#FFFFFF' } as Fill),
        ];
        const out = sampleFill(kfs, 50) as SolidFill;
        expect(out.color).toBe('#BCBCBC');
    });

    it('normalizes hex string values to solid fill', () => {
        const kfs = [kf('fill', 0, '#000000'), kf('fill', 100, '#FFFFFF')];
        const out = sampleFill(kfs, 50) as SolidFill;
        expect(out.type).toBe('solid');
        expect(out.color).toBe('#BCBCBC');
    });

    it('steps on cross-type fill (solid vs linear)', () => {
        const a: Fill = { type: 'solid', color: '#000000' };
        const b: LinearGradient = {
            type: 'linear', angle: 0,
            stops: [{ position: 0, color: '#fff' }, { position: 1, color: '#fff' }],
        };
        const kfs = [kf('fill', 0, a), kf('fill', 100, b)];
        expect(sampleFill(kfs, 50)).toBe(a);   // step → 左端帧引用
    });

    it('skips the track on a variable-ref value', () => {
        const kfs = [kf('fill', 0, '${var:user/f}'), kf('fill', 100, '#fff')];
        expect(sampleFill(kfs, 50)).toBeNull();
    });
});

// ---------- sampleText（§9.2 离散） ----------

describe('sampleText', () => {
    it('steps to the nearest keyframe with timeMs <= t', () => {
        const kfs = [kf('text', 0, 'a'), kf('text', 100, 'b'), kf('text', 200, 'c')];
        expect(sampleText(kfs, 0)).toBe('a');
        expect(sampleText(kfs, 99)).toBe('a');
        expect(sampleText(kfs, 100)).toBe('b');
        expect(sampleText(kfs, 150)).toBe('b');
        expect(sampleText(kfs, 999)).toBe('c');
    });

    it('takes the first keyframe before the first time', () => {
        const kfs = [kf('text', 100, 'a'), kf('text', 200, 'b')];
        expect(sampleText(kfs, 0)).toBe('a');
    });

    it('keeps variable templates verbatim (resolved later by rasterize)', () => {
        const kfs = [kf('text', 0, '${var:user/t}')];
        expect(sampleText(kfs, 50)).toBe('${var:user/t}');
    });

    it('skips the track on a non-string value', () => {
        const kfs = [kf('text', 0, 42 as unknown as string)];
        expect(sampleText(kfs, 0)).toBeNull();
    });
});

// ---------- interpolate 端到端 ----------

function rect(over: Partial<Element> = {}): Element {
    return {
        id: 'e-rect', type: 'rect', x: 0, y: 0, w: 10, h: 10, rotation: 0,
        locked: false, visible: true, opacity: 1, fill: { type: 'solid', color: '#000000' },
        ...over,
    } as Element;
}
function text(over: Partial<Element> = {}): Element {
    return {
        id: 'e-text', type: 'text', x: 0, y: 0, w: 10, h: 10, rotation: 0,
        locked: false, visible: true, text: 'hi', fontId: 'inter', fontSize: 12,
        color: '#000000', align: 'left', letterSpacing: 0, lineHeight: 1, vertical: false,
        ...over,
    } as Element;
}
function image(over: Partial<Element> = {}): Element {
    return {
        id: 'e-img', type: 'image', x: 0, y: 0, w: 10, h: 10, rotation: 0,
        locked: false, visible: true, source: 'abc123',
        ...over,
    } as Element;
}

function projectOf(...elements: Element[]): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#FFFFFF' },
        layers: [{
            id: 'l-1', name: 'L', visible: true, locked: false, opacity: 1,
            blendMode: 'normal', elements,
        }],
        activeLayerId: 'l-1',
        history: { undoDepth: 0, redoDepth: 0 },
    };
}

function timelineOf(tracks: Record<string, Keyframe[]>): Timeline {
    return {
        id: 'tl-1', name: 'T', durationMs: 1000, fps: 20, loopMode: 'loop',
        trigger: { type: 'manual', params: {} }, tracks,
    };
}

describe('interpolate (end-to-end)', () => {
    it('returns the same base reference when timeline has no tracks', () => {
        const base = projectOf(rect());
        const out = interpolate(base, timelineOf({}), 500);
        expect(out).toBe(base);
    });

    it('returns the same base reference when no element matches a track', () => {
        const base = projectOf(rect({ id: 'e-rect' }));
        const out = interpolate(base, timelineOf({ 'e-missing': [kf('x', 0, 5)] }), 500);
        expect(out).toBe(base);
    });

    it('rounds numeric x to integer (Math.round, aligned with Java int)', () => {
        const base = projectOf(rect());
        // x: 0 → 11，t=0.45 → 4.95 → round 5
        const out = interpolate(base, timelineOf({ 'e-rect': [kf('x', 0, 0), kf('x', 100, 11)] }), 45);
        expect(out.layers[0].elements[0].x).toBe(5);
    });

    it('clamps opacity into [0,1]', () => {
        const base = projectOf(rect({ opacity: 1 }));
        // opacity 0 → 2，t=1 → 2 → clamp 1
        const out = interpolate(base, timelineOf({ 'e-rect': [kf('opacity', 0, 0), kf('opacity', 1000, 2)] }), 1000);
        expect(out.layers[0].elements[0].opacity).toBe(1);
    });

    it('applies color on a text element', () => {
        const base = projectOf(text({ color: '#000000' }));
        const out = interpolate(base, timelineOf({ 'e-text': [kf('color', 0, '#000000'), kf('color', 100, '#FFFFFF')] }), 50);
        const el = out.layers[0].elements[0] as Element & { color: string };
        expect(el.color).toBe('#BCBCBC');
    });

    it('applies fill on a rect element', () => {
        const base = projectOf(rect());
        const out = interpolate(base, timelineOf({
            'e-rect': [
                kf('fill', 0, { type: 'solid', color: '#000000' } as Fill),
                kf('fill', 100, { type: 'solid', color: '#FFFFFF' } as Fill),
            ],
        }), 50);
        const el = out.layers[0].elements[0] as Element & { fill: SolidFill };
        expect(el.fill.color).toBe('#BCBCBC');
    });

    it('ignores fill track on a non-fillable image element (no fill written)', () => {
        const base = projectOf(image());
        const out = interpolate(base, timelineOf({
            'e-img': [
                kf('fill', 0, { type: 'solid', color: '#000000' } as Fill),
                kf('fill', 100, { type: 'solid', color: '#FFFFFF' } as Fill),
            ],
        }), 50);
        // fill 轨匹配到 image → 重建一份临时 state（与后端 withAnimated 一致），但
        // FILLABLE_TYPES 不含 image，故 fill 静默忽略：元素无 fill 字段、其余值不变。
        const el = out.layers[0].elements[0] as Element & { source: string };
        expect('fill' in el).toBe(false);
        expect(el.x).toBe(base.layers[0].elements[0].x);
        expect(el.source).toBe('abc123');
    });

    it('mixes multiple properties on one element track', () => {
        const base = projectOf(rect());
        const out = interpolate(base, timelineOf({
            'e-rect': [
                kf('x', 0, 0), kf('x', 100, 100),
                kf('opacity', 0, 1), kf('opacity', 100, 0),
            ],
        }), 50);
        const el = out.layers[0].elements[0];
        expect(el.x).toBe(50);
        expect(el.opacity).toBe(0.5);
    });

    it('does not mutate the base project (record-copy semantics)', () => {
        const base = projectOf(rect());
        interpolate(base, timelineOf({ 'e-rect': [kf('x', 0, 0), kf('x', 100, 100)] }), 50);
        expect(base.layers[0].elements[0].x).toBe(0);
    });

    it('strips timelines / activeTimelineId from the output state', () => {
        const base = { ...projectOf(rect()), timelines: [], activeTimelineId: 'tl-1' };
        const out = interpolate(base, timelineOf({ 'e-rect': [kf('x', 0, 0), kf('x', 100, 100)] }), 50);
        expect(out.timelines).toBeUndefined();
        expect(out.activeTimelineId).toBeUndefined();
    });

    it('clamps large numeric values to int range (no overflow wrap, §9.5)', () => {
        // 与后端 ix / StrictNumber.clampInt 对齐：x=3e9 round 后超 int，Java (int) 会回绕负值，
        // 故两端都钳到 2147483647
        const base = projectOf(rect());
        const out = interpolate(base, timelineOf({ 'e-rect': [kf('x', 0, 3_000_000_000), kf('x', 100, 3_000_000_000)] }), 50);
        expect(out.layers[0].elements[0].x).toBe(2147483647);
    });
});
