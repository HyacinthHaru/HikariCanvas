/**
 * 0.6 P2（B3）：timelineLogic 纯函数单测。
 *
 * 无 Vue / DOM / pinia（task 要求：纯函数测试，pinia 不进测试）。node 环境直接跑。
 *
 * 覆盖：
 * - groupKeyframesByElement：分组 + 排序 + 孤儿轨 type=null + 空输入
 * - formatKeyframeValue：number / string / Fill 对象 / 小数去尾
 * - defaultValueFor：opacity 缺省 → 1、其余缺省 → 0、显式值原样
 * - validateCreateForm：合法 + durationMs / fps / loopMode 各错误分支
 * - isValidKeyframeTime：边界
 * - shortElementId
 */
import { describe, expect, it } from 'vitest';
import {
    CREATE_FORM_DEFAULTS,
    EASING_TYPES,
    clampTimeMs,
    computeRulerTicks,
    defaultValueFor,
    defaultValueForExtended,
    formatKeyframeValue,
    formatTimeLabel,
    formatClock,
    frameMs,
    groupKeyframesByElement,
    isValidKeyframeTime,
    keyframeablePropertiesFor,
    msToPx,
    pxToMs,
    shortElementId,
    snapToFrame,
    splitTracksByProperty,
    aggregateTransformKeyframes,
    transformKeyframeKey,
    applyDragOverride,
    planTransformUpsert,
    groupsInMarquee,
    validateCreateForm,
    type CreateFormInput,
    type TransformSnapshot,
    type MarqueeRowView,
} from '../timelineLogic';
import type { Element, Fill, Keyframe, ProjectState, Timeline } from '@/types/protocol';

function mkKf(property: string, timeMs: number, value: number = 0): Keyframe {
    return {
        id: `kf-${property}-${timeMs}`,
        property,
        timeMs,
        value,
        easing: { type: 'linear' },
    };
}

function mkTimeline(tracks: Record<string, Keyframe[]>): Timeline {
    return {
        id: 'tl-deadbeef',
        name: 'demo',
        durationMs: 5000,
        fps: 20,
        loopMode: 'loop',
        trigger: { type: 'manual', params: {} },
        tracks,
    };
}

function mkRect(over: Partial<Element> = {}): Element {
    return {
        id: 'e-1a2b3c4d',
        type: 'rect',
        x: 10,
        y: 20,
        w: 30,
        h: 40,
        rotation: 0,
        locked: false,
        visible: true,
        ...over,
    } as Element;
}

describe('groupKeyframesByElement', () => {
    it('returns [] for null timeline', () => {
        expect(groupKeyframesByElement(null, () => null)).toEqual([]);
    });

    it('returns [] for timeline without tracks', () => {
        const tl = mkTimeline({});
        expect(groupKeyframesByElement(tl, () => 'rect')).toEqual([]);
    });

    it('groups by elementId ascending and sorts keyframes by (property, timeMs)', () => {
        const tl = mkTimeline({
            'e-bbb': [mkKf('x', 500), mkKf('opacity', 0), mkKf('x', 0)],
            'e-aaa': [mkKf('y', 100)],
        });
        const groups = groupKeyframesByElement(tl, (id) => (id === 'e-aaa' ? 'text' : 'circle'));
        // elementId 升序：e-aaa 在前
        expect(groups.map((g) => g.elementId)).toEqual(['e-aaa', 'e-bbb']);
        expect(groups[0].elementType).toBe('text');
        // e-bbb：opacity 先（字典序 o < x），x 内部按 timeMs 升序
        const bbb = groups[1];
        expect(bbb.keyframes.map((k) => `${k.property}@${k.timeMs}`)).toEqual([
            'opacity@0', 'x@0', 'x@500',
        ]);
    });

    it('marks orphan track type as null when element resolver returns null', () => {
        const tl = mkTimeline({ 'e-gone': [mkKf('x', 0)] });
        const groups = groupKeyframesByElement(tl, () => null);
        expect(groups[0].elementType).toBeNull();
    });

    it('does not mutate the source track array', () => {
        const original = [mkKf('x', 500), mkKf('x', 0)];
        const tl = mkTimeline({ 'e-a': original });
        groupKeyframesByElement(tl, () => 'rect');
        // 原数组顺序未被排序改动
        expect(original.map((k) => k.timeMs)).toEqual([500, 0]);
    });
});

describe('formatKeyframeValue', () => {
    it('formats integers as-is', () => {
        expect(formatKeyframeValue(42)).toBe('42');
        expect(formatKeyframeValue(0)).toBe('0');
    });

    it('trims trailing zeros on decimals', () => {
        expect(formatKeyframeValue(1.5)).toBe('1.5');
        expect(formatKeyframeValue(0.10000001)).toBe('0.1');
    });

    it('returns strings (incl. variable templates) as-is', () => {
        expect(formatKeyframeValue('hello')).toBe('hello');
        expect(formatKeyframeValue('${var:user/x}')).toBe('${var:user/x}');
    });

    it('returns object type for Fill values', () => {
        expect(formatKeyframeValue({ type: 'solid', color: '#fff' })).toBe('solid');
        expect(formatKeyframeValue({ type: 'linear', stops: [], angle: 0 })).toBe('linear');
    });

    it('falls back to "fill" for typeless objects', () => {
        expect(formatKeyframeValue({ foo: 1 })).toBe('fill');
    });
});

describe('defaultValueFor', () => {
    it('reads explicit numeric props', () => {
        const el = mkRect({ x: 10, y: 20, w: 30, h: 40, rotation: 15 });
        expect(defaultValueFor(el, 'x')).toBe(10);
        expect(defaultValueFor(el, 'rotation')).toBe(15);
        expect(defaultValueFor(el, 'h')).toBe(40);
    });

    it('defaults opacity to 1 when unset', () => {
        expect(defaultValueFor(mkRect(), 'opacity')).toBe(1);
    });

    it('reads explicit opacity', () => {
        expect(defaultValueFor(mkRect({ opacity: 0.3 }), 'opacity')).toBe(0.3);
    });

    it('defaults to 0 for null element (non-opacity)', () => {
        expect(defaultValueFor(null, 'x')).toBe(0);
        expect(defaultValueFor(null, 'rotation')).toBe(0);
    });

    it('defaults opacity to 1 for null element', () => {
        expect(defaultValueFor(null, 'opacity')).toBe(1);
    });
});

describe('validateCreateForm', () => {
    const base: CreateFormInput = { ...CREATE_FORM_DEFAULTS };

    it('accepts the defaults', () => {
        expect(validateCreateForm(base)).toEqual({ ok: true });
    });

    it('rejects non-positive durationMs', () => {
        const r = validateCreateForm({ ...base, durationMs: 0 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('durationMs');
    });

    it('rejects non-integer durationMs', () => {
        const r = validateCreateForm({ ...base, durationMs: 1.5 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('durationMs');
    });

    it('rejects absurdly long durationMs', () => {
        const r = validateCreateForm({ ...base, durationMs: 9_999_999 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.reason).toBe('errDurationTooLong');
    });

    it('rejects non-positive fps', () => {
        const r = validateCreateForm({ ...base, fps: 0 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('fps');
    });

    it('rejects fps above 240', () => {
        const r = validateCreateForm({ ...base, fps: 999 });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('fps');
    });

    it('rejects unknown loopMode', () => {
        // 强制非法值绕过类型检查模拟 UI 漂移
        const r = validateCreateForm({ ...base, loopMode: 'bogus' as never });
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.field).toBe('loopMode');
    });
});

describe('isValidKeyframeTime', () => {
    it('accepts boundaries 0 and durationMs', () => {
        expect(isValidKeyframeTime(0, 5000)).toBe(true);
        expect(isValidKeyframeTime(5000, 5000)).toBe(true);
        expect(isValidKeyframeTime(2500, 5000)).toBe(true);
    });

    it('rejects negative / overflow / non-integer', () => {
        expect(isValidKeyframeTime(-1, 5000)).toBe(false);
        expect(isValidKeyframeTime(5001, 5000)).toBe(false);
        expect(isValidKeyframeTime(1.5, 5000)).toBe(false);
    });
});

describe('shortElementId', () => {
    it('strips the e- prefix', () => {
        expect(shortElementId('e-1a2b3c4d')).toBe('1a2b3c4d');
    });
    it('returns id without prefix unchanged', () => {
        expect(shortElementId('abcdef')).toBe('abcdef');
    });
});

// ---------- P3 扩展：keyframeablePropertiesFor ----------

/** 给定 type 造一个最简元素（只需 type + base 字段够分类）。 */
function mkEl(type: string, over: Partial<Element> = {}): Element {
    return {
        id: `e-${type}`,
        type,
        x: 0, y: 0, w: 10, h: 10, rotation: 0,
        locked: false, visible: true,
        ...over,
    } as Element;
}

const NUMERIC_SIX = ['x', 'y', 'w', 'h', 'rotation', 'opacity'];

describe('keyframeablePropertiesFor', () => {
    it('returns the six numeric props for null element', () => {
        expect(keyframeablePropertiesFor(null)).toEqual(NUMERIC_SIX);
    });

    it('text adds color + text on top of numerics', () => {
        expect(keyframeablePropertiesFor(mkEl('text')))
            .toEqual([...NUMERIC_SIX, 'color', 'text']);
    });

    it('rect adds fill (有填充时)', () => {
        expect(keyframeablePropertiesFor(mkEl('rect', { fill: { type: 'solid', color: '#FFFFFF' } } as Partial<Element>)))
            .toEqual([...NUMERIC_SIX, 'fill']);
    });

    it.each(['rect', 'icon', 'path', 'circle', 'shape', 'brush'])(
        '%s is fillable (adds fill, no color/text)',
        (type) => {
            const props = keyframeablePropertiesFor(
                mkEl(type, { fill: { type: 'solid', color: '#FFFFFF' } } as Partial<Element>));
            expect(props).toEqual([...NUMERIC_SIX, 'fill']);
            expect(props).not.toContain('color');
            expect(props).not.toContain('text');
        },
    );

    it('P3 审查 #9：元素当前 fill 为 null/缺失时不放行 fill 轨（空心框不能加填充关键帧）', () => {
        expect(keyframeablePropertiesFor(mkEl('rect'))).toEqual(NUMERIC_SIX);
        expect(keyframeablePropertiesFor(mkEl('circle', { fill: null } as unknown as Partial<Element>)))
            .toEqual(NUMERIC_SIX);
    });

    it('image gets only the numeric six (no fill/color/text)', () => {
        expect(keyframeablePropertiesFor(mkEl('image', { source: 'abc' } as Partial<Element>)))
            .toEqual(NUMERIC_SIX);
    });

    it('covers all 8 element types deterministically', () => {
        // text(8) / rect 带填充(7) / image(6)
        expect(keyframeablePropertiesFor(mkEl('text')).length).toBe(8);
        expect(keyframeablePropertiesFor(
            mkEl('rect', { fill: { type: 'solid', color: '#FFFFFF' } } as Partial<Element>)).length).toBe(7);
        expect(keyframeablePropertiesFor(mkEl('image', { source: 'a' } as Partial<Element>)).length).toBe(6);
    });
});

// ---------- P3 扩展：defaultValueForExtended ----------

describe('defaultValueForExtended', () => {
    it('numeric props delegate to defaultValueFor (number)', () => {
        const el = mkEl('rect', { x: 12, opacity: 0.4 } as Partial<Element>);
        expect(defaultValueForExtended(el, 'x')).toBe(12);
        expect(defaultValueForExtended(el, 'opacity')).toBe(0.4);
        expect(defaultValueForExtended(mkEl('rect'), 'opacity')).toBe(1);
    });

    it('color reads element.color, defaulting to #000000', () => {
        expect(defaultValueForExtended(mkEl('text', { color: '#FF8800' } as Partial<Element>), 'color'))
            .toBe('#FF8800');
        expect(defaultValueForExtended(mkEl('text'), 'color')).toBe('#000000');
        // 非 text 元素无 color → 默认黑
        expect(defaultValueForExtended(mkEl('rect'), 'color')).toBe('#000000');
    });

    it('text reads element.text, defaulting to empty string', () => {
        expect(defaultValueForExtended(mkEl('text', { text: 'hi' } as Partial<Element>), 'text'))
            .toBe('hi');
        expect(defaultValueForExtended(mkEl('text'), 'text')).toBe('');
        // 空字符串元素 text 原样返回（不退默认）
        expect(defaultValueForExtended(mkEl('text', { text: '' } as Partial<Element>), 'text')).toBe('');
    });

    it('fill reads element.fill object, defaulting to white solid', () => {
        const el = mkEl('rect', { fill: { type: 'solid', color: '#123456' } } as Partial<Element>);
        expect(defaultValueForExtended(el, 'fill')).toEqual({ type: 'solid', color: '#123456' });
        expect(defaultValueForExtended(mkEl('rect'), 'fill')).toEqual({ type: 'solid', color: '#FFFFFF' });
    });

    it('fill wraps a legacy string fill into a SolidFill', () => {
        const el = mkEl('rect', { fill: '#abcdef' as unknown as Fill } as Partial<Element>);
        expect(defaultValueForExtended(el, 'fill')).toEqual({ type: 'solid', color: '#abcdef' });
    });

    it('fill preserves a linear gradient object', () => {
        const grad: Fill = {
            type: 'linear', angle: 45,
            stops: [{ position: 0, color: '#000' }, { position: 1, color: '#fff' }],
        };
        const el = mkEl('rect', { fill: grad } as Partial<Element>);
        expect(defaultValueForExtended(el, 'fill')).toBe(grad);
    });
});

// ---------- P3：EASING_TYPES 常量完整性 ----------

describe('EASING_TYPES', () => {
    it('lists the four MVP presets in order (no cubicBezier in the dropdown)', () => {
        expect(EASING_TYPES).toEqual(['linear', 'easeIn', 'easeOut', 'easeInOut']);
    });

    it('starts with linear (default)', () => {
        expect(EASING_TYPES[0]).toBe('linear');
    });
});

// ──────────────────────────────────────────────────────────
//  P4：AE dock 时间↔像素映射 + 二级属性子轨拆分
// ──────────────────────────────────────────────────────────

describe('splitTracksByProperty (P4 二级拆分)', () => {
    it('returns [] for null timeline', () => {
        expect(splitTracksByProperty(null, () => null)).toEqual([]);
    });

    it('groups by elementId asc → property asc → timeMs asc', () => {
        const timeline = {
            id: 'tl-1', name: 't', durationMs: 1000, fps: 20, loopMode: 'loop',
            trigger: { type: 'manual', params: {} },
            tracks: {
                'e-b': [mkKf('y', 500), mkKf('x', 300), mkKf('x', 100)],
                'e-a': [mkKf('opacity', 0)],
            },
        } as unknown as Timeline;
        const groups = splitTracksByProperty(timeline, (id) => (id === 'e-a' ? 'rect' : 'text'));
        expect(groups.map(g => g.elementId)).toEqual(['e-a', 'e-b']);   // elementId 升序
        expect(groups[0].elementType).toBe('rect');
        const eb = groups[1];
        expect(eb.properties.map(p => p.property)).toEqual(['x', 'y']);   // property 字典序
        expect(eb.properties[0].keyframes.map(k => k.timeMs)).toEqual([100, 300]);   // timeMs 升序
    });
});

describe('time <-> pixel mapping (P4)', () => {
    it('msToPx / pxToMs round-trip with scroll offset', () => {
        expect(msToPx(500, 0.1, 0)).toBe(50);
        expect(msToPx(500, 0.1, 100)).toBeCloseTo(40, 6);
        expect(pxToMs(50, 0.1, 0)).toBeCloseTo(500, 6);
        expect(pxToMs(40, 0.1, 100)).toBeCloseTo(500, 6);
    });

    it('pxToMs guards pxPerMs<=0 → returns scrollMs', () => {
        expect(pxToMs(123, 0, 50)).toBe(50);
    });

    it('clampTimeMs clamps + rounds to [0, duration]', () => {
        expect(clampTimeMs(-5, 1000)).toBe(0);
        expect(clampTimeMs(1500, 1000)).toBe(1000);
        expect(clampTimeMs(499.6, 1000)).toBe(500);
    });

    it('frameMs handles fps<=0 (fallback 20fps)', () => {
        expect(frameMs(20)).toBe(50);
        expect(frameMs(0)).toBe(50);
    });

    it('snapToFrame snaps to nearest frame + clamps', () => {
        expect(snapToFrame(123, 20, 1000)).toBe(100);     // round(123/50)=2 → 100
        expect(snapToFrame(130, 20, 1000)).toBe(150);     // round(130/50)=3 → 150
        expect(snapToFrame(99999, 20, 1000)).toBe(1000);  // clamp
    });
});

describe('formatTimeLabel (P4)', () => {
    it('uses ms below 1s', () => {
        expect(formatTimeLabel(0)).toBe('0ms');
        expect(formatTimeLabel(500)).toBe('500ms');
    });
    it('uses s at/above 1s (trim trailing 0)', () => {
        expect(formatTimeLabel(1000)).toBe('1s');
        expect(formatTimeLabel(1500)).toBe('1.5s');
        expect(formatTimeLabel(2250)).toBe('2.25s');
    });
});

describe('formatClock (P4.5 fix Bug1 定宽时钟)', () => {
    it('zero-pads seconds + millis to fixed m:ss.mmm', () => {
        expect(formatClock(0)).toBe('0:00.000');
        expect(formatClock(2333)).toBe('0:02.333');
        expect(formatClock(5000)).toBe('0:05.000');
        expect(formatClock(65432)).toBe('1:05.432');
    });
    it('clamps negative to 0', () => {
        expect(formatClock(-100)).toBe('0:00.000');
    });
    it('keeps constant width across decimal changes (anti-flicker)', () => {
        // 2.40s vs 2.33s：formatTimeLabel 去尾0 会变宽抖动；formatClock 恒 8 字符
        expect(formatClock(2400).length).toBe(formatClock(2333).length);
        expect(formatClock(2400)).toBe('0:02.400');
    });
});

describe('computeRulerTicks (P4)', () => {
    it('returns [] for degenerate input', () => {
        expect(computeRulerTicks(0, 0.1, 100)).toEqual([]);
        expect(computeRulerTicks(1000, 0, 100)).toEqual([]);
        expect(computeRulerTicks(1000, 0.1, 0)).toEqual([]);
    });
    it('produces nice-stepped ticks within duration, x = timeMs*pxPerMs', () => {
        const ticks = computeRulerTicks(1000, 0.5, 500, 0);
        expect(ticks.length).toBeGreaterThan(0);
        expect(ticks[0].timeMs).toBe(0);
        for (const tk of ticks) {
            expect(tk.timeMs).toBeGreaterThanOrEqual(0);
            expect(tk.timeMs).toBeLessThanOrEqual(1000);
            expect(tk.x).toBeCloseTo(tk.timeMs * 0.5, 6);
        }
    });
});

describe('aggregateTransformKeyframes (P4.5 整体关键帧)', () => {
    function tlWith(tracks: Record<string, Keyframe[]>): Timeline {
        return {
            id: 'tl-1', name: 't', durationMs: 1000, fps: 20, loopMode: 'loop',
            trigger: { type: 'manual', params: {} }, tracks,
        } as unknown as Timeline;
    }
    function kfE(id: string, property: string, timeMs: number, easing: Keyframe['easing'] = { type: 'linear' }): Keyframe {
        return { id, property, timeMs, value: 0, easing };
    }

    it('returns [] for missing element / null timeline', () => {
        expect(aggregateTransformKeyframes(tlWith({}), 'e-x')).toEqual([]);
        expect(aggregateTransformKeyframes(null, 'e-x')).toEqual([]);
    });

    it('groups transform keyframes by timeMs ascending', () => {
        const tl = tlWith({
            'e-1': [
                kfE('k1', 'x', 0), kfE('k2', 'y', 0), kfE('k3', 'opacity', 0),
                kfE('k4', 'x', 500), kfE('k5', 'y', 500),
            ],
        });
        const groups = aggregateTransformKeyframes(tl, 'e-1');
        expect(groups.map(g => g.timeMs)).toEqual([0, 500]);
        expect(groups[0].keyframeIds.slice().sort()).toEqual(['k1', 'k2', 'k3']);
        expect(groups[0].properties.slice().sort()).toEqual(['opacity', 'x', 'y']);
        expect(groups[1].keyframeIds.slice().sort()).toEqual(['k4', 'k5']);
    });

    it('excludes non-transform properties (color/text/fill)', () => {
        const tl = tlWith({ 'e-1': [kfE('k1', 'x', 0), kfE('kc', 'color', 0), kfE('kf', 'fill', 0)] });
        const groups = aggregateTransformKeyframes(tl, 'e-1');
        expect(groups.length).toBe(1);
        expect(groups[0].properties).toEqual(['x']);
    });

    it('takes group easing from first keyframe', () => {
        const tl = tlWith({ 'e-1': [kfE('k1', 'x', 0, { type: 'easeInOut' }), kfE('k2', 'y', 0)] });
        expect(aggregateTransformKeyframes(tl, 'e-1')[0].easing.type).toBe('easeInOut');
    });

    it('transformKeyframeKey is stable', () => {
        expect(transformKeyframeKey('e-1', 500)).toBe('e-1:500');
    });
});

describe('planTransformUpsert (P4.5b 自动加帧计划)', () => {
    it('all-add when no keyframes exist at timeMs, values from element current geometry', () => {
        const rect = mkRect();   // x10 y20 w30 h40 rot0, opacity 缺省→1
        const plan = planTransformUpsert(mkTimeline({}), rect, 0);
        expect(plan.timeMs).toBe(0);
        expect(plan.updates).toEqual([]);
        // 6 个 transform 属性全 add，值取元素当前几何（opacity 缺省 1）
        const byProp = Object.fromEntries(plan.adds.map(a => [a.property, a.value]));
        expect(byProp).toEqual({ x: 10, y: 20, w: 30, h: 40, rotation: 0, opacity: 1 });
    });

    it('all-update when every transform prop already has a frame at timeMs', () => {
        const rect = mkRect();
        const tl = mkTimeline({
            [rect.id]: [
                mkKf('x', 0), mkKf('y', 0), mkKf('w', 0),
                mkKf('h', 0), mkKf('rotation', 0), mkKf('opacity', 0),
            ],
        });
        const plan = planTransformUpsert(tl, rect, 0);
        expect(plan.adds).toEqual([]);
        expect(plan.updates.map(u => u.property).sort())
            .toEqual(['h', 'opacity', 'rotation', 'w', 'x', 'y']);
        // update 带正确 keyframeId（mkKf id = kf-<prop>-<timeMs>）+ 元素当前值
        const x = plan.updates.find(u => u.property === 'x')!;
        expect(x.keyframeId).toBe('kf-x-0');
        expect(x.value).toBe(10);
    });

    it('partial: existing props → update, missing props → add (只在同 timeMs 命中)', () => {
        const rect = mkRect();
        const tl = mkTimeline({ [rect.id]: [mkKf('x', 0), mkKf('y', 0), mkKf('x', 500)] });
        const plan = planTransformUpsert(tl, rect, 0);
        expect(plan.updates.map(u => u.property).sort()).toEqual(['x', 'y']);
        expect(plan.adds.map(a => a.property).sort()).toEqual(['h', 'opacity', 'rotation', 'w']);
        // 不同 timeMs 的 x@500 不参与 timeMs=0 的命中
        expect(plan.updates.find(u => u.property === 'x')!.keyframeId).toBe('kf-x-0');
    });
});

describe('applyDragOverride (P4.5b 拖动期跟手覆盖)', () => {
    function mkState(elements: Element[]): ProjectState {
        return {
            version: 3,
            canvas: { w: 128, h: 128, background: { type: 'solid', color: '#ffffff' } },
            layers: [{
                id: 'L', name: 'L', visible: true, locked: false, opacity: 1,
                blendMode: 'normal', colorTag: null, elements,
            }],
            activeLayerId: 'L',
            history: { undoDepth: 0, redoDepth: 0 },
            timelines: [],
            activeTimelineId: null,
        } as unknown as ProjectState;
    }
    const snap: TransformSnapshot = { x: 111, y: 222, w: 33, h: 44, rotation: 5, opacity: 0.5 };

    it('empty overrides → returns same reference (无副本)', () => {
        const s = mkState([mkRect()]);
        expect(applyDragOverride(s, new Map())).toBe(s);
    });

    it('null state → null', () => {
        expect(applyDragOverride(null, new Map([['e-1a2b3c4d', snap]]))).toBe(null);
    });

    it('no matching element → returns same reference', () => {
        const s = mkState([mkRect()]);
        expect(applyDragOverride(s, new Map([['nope', snap]]))).toBe(s);
    });

    it('applies snapshot to matched element as a new object', () => {
        const s = mkState([mkRect()]);
        const out = applyDragOverride(s, new Map([['e-1a2b3c4d', snap]]))!;
        expect(out).not.toBe(s);
        const el = out.layers[0].elements[0] as unknown as Record<string, number>;
        expect([el.x, el.y, el.w, el.h, el.rotation, el.opacity]).toEqual([111, 222, 33, 44, 5, 0.5]);
    });

    it('does not mutate the input state (immutability)', () => {
        const s = mkState([mkRect()]);
        applyDragOverride(s, new Map([['e-1a2b3c4d', snap]]));
        const orig = s.layers[0].elements[0] as unknown as Record<string, number>;
        expect([orig.x, orig.y]).toEqual([10, 20]);   // 原 state 元素纹丝不动
    });
});

describe('groupsInMarquee (P4.5b 框选批量选中)', () => {
    // pxPerMs=0.1 → timeMs 0→x0, 500→x50, 1000→x100；rowH=28 → 行中线 i*28+14
    const rows: MarqueeRowView[] = [
        { kind: 'element', elementId: 'e-1', groups: [{ timeMs: 0 }, { timeMs: 1000 }] }, // i0 yc14
        { kind: 'property', elementId: 'e-1' },                                            // i1 yc42（无 groups，跳过）
        { kind: 'element', elementId: 'e-2', groups: [{ timeMs: 500 }] },                  // i2 yc70
    ];

    it('rect covering everything → all element-row group keys', () => {
        const keys = groupsInMarquee(rows, { x0: -10, y0: -10, x1: 200, y1: 200 }, 0.1, 0, 28);
        expect(keys.sort()).toEqual(['e-1:0', 'e-1:1000', 'e-2:500']);
    });

    it('rect bounded by row 0 + x≤50 → only e-1:0', () => {
        // y 0..28 只含 row0（yc14）；x 0..50 只含 timeMs 0（x0），不含 timeMs 1000（x100）
        const keys = groupsInMarquee(rows, { x0: 0, y0: 0, x1: 50, y1: 28 }, 0.1, 0, 28);
        expect(keys).toEqual(['e-1:0']);
    });

    it('rect over second element row → e-2:500', () => {
        const keys = groupsInMarquee(rows, { x0: 0, y0: 56, x1: 100, y1: 84 }, 0.1, 0, 28);
        expect(keys).toEqual(['e-2:500']);
    });

    it('non-overlapping rect → []', () => {
        const keys = groupsInMarquee(rows, { x0: 300, y0: 0, x1: 400, y1: 200 }, 0.1, 0, 28);
        expect(keys).toEqual([]);
    });

    it('normalizes reversed drag (x1<x0 / y1<y0)', () => {
        const keys = groupsInMarquee(rows, { x0: 50, y0: 28, x1: 0, y1: 0 }, 0.1, 0, 28);
        expect(keys).toEqual(['e-1:0']);
    });
});
